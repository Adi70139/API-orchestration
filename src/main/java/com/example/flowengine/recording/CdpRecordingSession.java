package com.example.flowengine.recording;

import com.example.flowengine.recording.RecordedRequest;
import com.example.flowengine.recording.RecordingStartRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.logging.Logger;

class CdpRecordingSession {
    private static final Logger log = Logger.getLogger(CdpRecordingSession.class.getName());
    private static final int STARTUP_TIMEOUT_MILLIS = 15_000;
    private static final int CDP_COMMAND_TIMEOUT_SECONDS = 2;

    private final ObjectMapper objectMapper;
    private final RecordingStartRequest options;
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Map<String, RecordedRequest> activeRequests = new ConcurrentHashMap<>();
    private final List<RecordedRequest> completedRequests = new CopyOnWriteArrayList<>();
    private final Set<String> redactedHeaders;
    private WebSocket webSocket;
    private Process chromeProcess;
    private volatile String status = "STARTING";

    CdpRecordingSession(RecordingStartRequest options, ObjectMapper objectMapper) {
        this.options = options;
        this.objectMapper = objectMapper;
        this.redactedHeaders = options.getRedactedHeaders() == null
                ? Set.of()
                : options.getRedactedHeaders().stream()
                .map(header -> header.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
    }

    void start() throws Exception {
        if (!options.isAttach()) {
            chromeProcess = launchChrome();
        }
        String wsUrl = options.isAttach() ? waitForPageWebSocket() : waitForNewPageWebSocket();

        // CRITICAL: connect() and enableNetwork() must NOT run on the same thread
        // that will block waiting for the response. Java HttpClient's WebSocket
        // delivers messages on the ForkJoin pool — if we .join() from a ForkJoin
        // thread we deadlock. Run setup on a dedicated thread instead.
        ExecutorService setupExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<Void> setupFuture = CompletableFuture.runAsync(() -> {
            try {
                connect(wsUrl);
                enableNetwork();
                status = "RECORDING";
                navigateToStartUrl();
                log.info("[CDP] Setup complete — now recording");
            } catch (Exception e) {
                status = "ERROR: " + e.getMessage();
                log.warning("[CDP] Setup failed: " + e.getMessage());
            }
        }, setupExecutor);

        // Wait for setup to complete before returning to caller
        try {
            setupFuture.orTimeout(15, TimeUnit.SECONDS).join();
        } finally {
            setupExecutor.shutdown();
        }
    }

    List<RecordedRequest> snapshot() {
        return List.copyOf(completedRequests);
    }

    int capturedCount() {
        return completedRequests.size() + activeRequests.size();
    }

    String status() {
        return status;
    }

    void stop() {
        status = "STOPPING";
        try {
            // Drain any requests that received a response but loadingFinished hasn't fired yet
            activeRequests.forEach((requestId, recorded) -> {
                // Only drain requests that have at least a status code — means response was received
                if (recorded.getStatus() > 0) {
                    completedRequests.add(recorded);
                }
            });
            activeRequests.clear();

            flushResponseBodies();
        } finally {
            pending.values().forEach(future -> future.completeExceptionally(
                    new IllegalStateException("Recording stopped")));
            pending.clear();
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "recording stopped")
                        .orTimeout(CDP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(ignored -> null);
            }
            if (chromeProcess != null) {
                chromeProcess.destroy();
                try {
                    if (!chromeProcess.waitFor(2, TimeUnit.SECONDS)) {
                        chromeProcess.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    chromeProcess.destroyForcibly();
                }
            }
            status = "STOPPED";
        }
    }

    private Process launchChrome() throws IOException {
        String chromePath = options.getChromePath() == null || options.getChromePath().isBlank()
                ? detectChrome()
                : options.getChromePath();
        Path profile = Files.createTempDirectory("curl-extractor-profile-");
        List<String> command = new ArrayList<>();
        command.add(chromePath);
        command.add("--remote-debugging-port=" + options.getPort());
        command.add("--user-data-dir=" + profile);
        command.add("--no-first-run");
        command.add("--no-default-browser-check");
        command.add("about:blank");
        
        if (Boolean.parseBoolean(System.getenv("UI_AUTOMATION_HEADLESS"))) {
            command.add("--headless=new");
            command.add("--no-sandbox");
            command.add("--disable-gpu");
            command.add("--disable-dev-shm-usage");
        }
        
        return new ProcessBuilder(command).start();
    }

    private String detectChrome() {
        List<String> candidates = List.of(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe"
        );
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        
        // Fallback: Use Playwright's downloaded Chromium if available
        try {
            try (com.microsoft.playwright.Playwright playwright = com.microsoft.playwright.Playwright.create()) {
                String executablePath = playwright.chromium().executablePath().toString();
                log.info("[CDP] Using Playwright Chromium executable: " + executablePath);
                return executablePath;
            }
        } catch (Exception e) {
            log.warning("[CDP] Could not retrieve Playwright Chromium path: " + e.getMessage());
        }

        return "google-chrome";
    }

    private String waitForPageWebSocket() throws Exception {
        URI listUri = URI.create("http://127.0.0.1:" + options.getPort() + "/json/list");
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        Exception last = null;
        boolean triedCreateTarget = false;

        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest request = HttpRequest.newBuilder(listUri).GET().build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode targets = objectMapper.readTree(response.body());
                for (JsonNode target : targets) {
                    if ("page".equals(target.path("type").asText()) && target.hasNonNull("webSocketDebuggerUrl")) {
                        return target.path("webSocketDebuggerUrl").asText();
                    }
                }
                if (!triedCreateTarget) {
                    triedCreateTarget = true;
                    String createdTarget = createPageTarget();
                    if (createdTarget != null) {
                        return createdTarget;
                    }
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Could not find a debuggable Chrome page on port " + options.getPort(), last);
    }

    private String waitForNewPageWebSocket() throws Exception {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        Exception last = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                String wsUrl = createPageTarget();
                if (wsUrl != null) {
                    return wsUrl;
                }
            } catch (Exception e) {
                last = e;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Could not create a debuggable Chrome page on port " + options.getPort(), last);
    }

    private String createPageTarget() {
        String encodedUrl = URLEncoder.encode("about:blank", StandardCharsets.UTF_8);
        URI newTargetUri = URI.create("http://127.0.0.1:" + options.getPort() + "/json/new?" + encodedUrl);

        String wsUrl = createPageTarget(newTargetUri, "PUT");
        if (wsUrl != null) {
            return wsUrl;
        }
        return createPageTarget(newTargetUri, "GET");
    }

    private String createPageTarget(URI uri, String method) {
        try {
            HttpRequest request = "PUT".equals(method)
                    ? HttpRequest.newBuilder(uri).PUT(HttpRequest.BodyPublishers.noBody()).build()
                    : HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return null;
            }
            JsonNode target = objectMapper.readTree(response.body());
            return target.hasNonNull("webSocketDebuggerUrl")
                    ? target.path("webSocketDebuggerUrl").asText()
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void connect(String wsUrl) {
        log.info("[CDP] Connecting to WebSocket: " + wsUrl);
        webSocket = http.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new CdpListener())
                .join();
        log.info("[CDP] WebSocket connected");
    }

    private void enableNetwork() {
        log.info("[CDP] Enabling Network and Page domains");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("maxPostDataSize", 10_000_000);
        send("Network.enable", params)
                .orTimeout(CDP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
        send("Page.enable", objectMapper.createObjectNode())
                .orTimeout(CDP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
    }

    private void navigateToStartUrl() {
        String url = options.getUrl();
        if (url == null || url.isBlank() || "about:blank".equals(url)) {
            return;
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("url", url);
        send("Page.navigate", params)
                .orTimeout(CDP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
    }

    private CompletableFuture<JsonNode> send(String method, JsonNode params) {
        int id = nextId.getAndIncrement();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            webSocket.sendText(objectMapper.writeValueAsString(message), true);
        } catch (JsonProcessingException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private void onMessage(JsonNode message) {
        if (message.has("id")) {
            CompletableFuture<JsonNode> future = pending.remove(message.path("id").asInt());
            if (future != null) {
                future.complete(message);
            }
            return;
        }

        String method = message.path("method").asText();
        JsonNode params = message.path("params");
        switch (method) {
            case "Network.requestWillBeSent" -> onRequestWillBeSent(params);
            case "Network.requestWillBeSentExtraInfo" -> onRequestExtraInfo(params);
            case "Network.responseReceived" -> onResponseReceived(params);
            case "Network.loadingFinished" -> onLoadingFinished(params);
            case "Network.loadingFailed" -> activeRequests.remove(params.path("requestId").asText());
            default -> {
            }
        }
    }

    private void onRequestWillBeSent(JsonNode params) {
        JsonNode request = params.path("request");
        String url = request.path("url").asText();
        if (!shouldRecord(url)) {
            return;
        }
        log.info("[CDP] Request: " + request.path("method").asText() + " " + url);

        RecordedRequest recorded = new RecordedRequest();
        recorded.setRequestId(params.path("requestId").asText());
        recorded.setStartedAt(Instant.now().toString());
        recorded.setMethod(request.path("method").asText());
        recorded.setUrl(url);
        recorded.getHeaders().putAll(headersFrom(request.path("headers")));
        if (request.hasNonNull("postData")) {
            recorded.setPostData(request.path("postData").asText());
        }
        recorded.setInitiator(params.path("initiator").path("type").asText(null));
        activeRequests.put(recorded.getRequestId(), recorded);
    }

    private void onRequestExtraInfo(JsonNode params) {
        RecordedRequest recorded = activeRequests.get(params.path("requestId").asText());
        if (recorded != null) {
            recorded.getHeaders().putAll(headersFrom(params.path("headers")));
        }
    }

    private void onResponseReceived(JsonNode params) {
        RecordedRequest recorded = activeRequests.get(params.path("requestId").asText());
        if (recorded == null) {
            return;
        }
        JsonNode response = params.path("response");
        recorded.setStatus(response.path("status").asInt());
        recorded.setMimeType(response.path("mimeType").asText(null));
        recorded.getResponseHeaders().putAll(headersFrom(response.path("headers")));
    }

    private void onLoadingFinished(JsonNode params) {
        RecordedRequest recorded = activeRequests.remove(params.path("requestId").asText());
        if (recorded != null) {
            log.info("[CDP] LoadingFinished: " + recorded.getMethod() + " " + recorded.getUrl() + " status=" + recorded.getStatus());
            completedRequests.add(recorded);
        }
    }

    private Map<String, String> headersFrom(JsonNode headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null || !headers.isObject()) {
            return result;
        }
        headers.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            String value = entry.getValue().asText();
            if (redactedHeaders.contains(name.toLowerCase(Locale.ROOT))) {
                value = "<redacted>";
            }
            result.put(name, value);
        });
        return result;
    }

    private boolean shouldRecord(String url) {
        String include = options.getInclude();
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("chrome:")) {
            return false;
        }
        return include == null || include.isBlank() || url.contains(include);
    }

    private void flushResponseBodies() {
        for (RecordedRequest request : snapshot()) {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("requestId", request.getRequestId());
            try {
                JsonNode response = send("Network.getResponseBody", params)
                        .orTimeout(CDP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .join();
                JsonNode result = response.path("result");
                if (result.has("body")) {
                    request.setResponseBody(result.path("body").asText());
                    request.setResponseBodyBase64Encoded(result.path("base64Encoded").asBoolean(false));
                }
            } catch (Exception ignored) {
                request.setResponseBody(null);
            }

            if (request.getPostData() == null) {
                try {
                    JsonNode response = send("Network.getRequestPostData", params)
                            .orTimeout(CDP_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .join();
                    if (response.path("result").has("postData")) {
                        request.setPostData(response.path("result").path("postData").asText());
                    }
                } catch (Exception ignored) {
                    request.setPostData(null);
                }
            }
        }
    }

    private final class CdpListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("[CDP] WebSocket onOpen — requesting first message");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            // Request next message FIRST — before any processing that might throw
            webSocket.request(1);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                try {
                    JsonNode msg = objectMapper.readTree(raw);
                    // Log all events except command responses to see what CDP is sending
                    if (!msg.has("id")) {
                        String method = msg.path("method").asText("");
                        if (!method.isBlank()) {
                            log.fine("[CDP] Event: " + method);
                        }
                    }
                    onMessage(msg);
                } catch (Exception e) {
                    log.warning("[CDP] Failed to parse message: " + e.getMessage() + " raw=" + raw.substring(0, Math.min(200, raw.length())));
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("[CDP] WebSocket closed: " + statusCode + " " + reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warning("[CDP] WebSocket error: " + error.getMessage());
            status = "ERROR: " + error.getMessage();
        }
    }
}
