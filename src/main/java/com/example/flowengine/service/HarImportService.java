package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.HarImportRequest;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.example.flowengine.repository.ModuleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class HarImportService {

    private static final Set<String> SKIP_EXTENSIONS = Set.of(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".woff", ".woff2", ".ttf", ".eot", ".map", ".webp", ".avif"
    );

    private static final Set<String> SKIP_MIME_PREFIXES = Set.of(
            "text/html", "text/css", "application/javascript", "text/javascript",
            "image/", "font/", "audio/", "video/"
    );

    private static final Set<String> API_RESOURCE_TYPES = Set.of("xhr", "fetch", "other");

    private static final Set<String> SKIP_URL_SEGMENTS = Set.of(
            "/_next/", "/__nextjs", "/node_modules/", "/.nuxt/"
    );

    // Only strip truly useless headers — keep cookie, authorization, content-type
    private static final Set<String> SKIP_HEADERS = Set.of(
            "host", "connection", "content-length", "accept-encoding",
            "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
            "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform",
            "upgrade-insecure-requests"
    );

    private final ModuleRepository moduleRepository;
    private final FlowRepository flowRepository;
    private final FlowStepRepository flowStepRepository;
    private final FlowService flowService;
    private final ObjectMapper objectMapper;

    // ─── Main import ──────────────────────────────────────────────────────────

    @Transactional
    public FlowDetailedDTO importHar(MultipartFile file, HarImportRequest request) throws Exception {
        JsonNode root = objectMapper.readTree(file.getInputStream());
        JsonNode entries = root.path("log").path("entries");

        if (entries.isMissingNode() || !entries.isArray()) {
            throw new IllegalArgumentException("Invalid HAR file — no log.entries array found");
        }

        ModuleEntity module = moduleRepository.findById(request.getModuleId())
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + request.getModuleId()));

        FlowDefinition flow;
        if (request.getFlowId() != null) {
            flow = flowRepository.findById(request.getFlowId())
                    .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + request.getFlowId()));
            log.info("Appending HAR entries to existing flow '{}'", flow.getName());
        } else {
            flow = new FlowDefinition();
            flow.setName(request.getFlowName() != null ? request.getFlowName()
                    : "HAR Import - " + file.getOriginalFilename());
            flow.setDescription("Imported from HAR recording");
            flow.setModule(module);
            flow = flowRepository.save(flow);
            log.info("Created new flow '{}' for HAR import", flow.getName());
        }

        int stepOrder = flowStepRepository.findByFlowIdOrderByStepOrder(flow.getId()).stream()
                .mapToInt(FlowStep::getStepOrder).max().orElse(0) + 1;

        int created = 0, skipped = 0;
        // Track fingerprints to deduplicate identical requests
        Set<String> seenFingerprints = new LinkedHashSet<>();

        for (JsonNode entry : entries) {
            JsonNode req = entry.path("request");
            if (req.isMissingNode()) continue;

            String url = req.path("url").asText("");
            String method = req.path("method").asText("GET").toUpperCase();
            Map<String, String> headers = extractHeaderMap(req.path("headers"));

            if (!shouldInclude(entry, url, method, headers)) {
                skipped++;
                continue;
            }

            if (request.getFilterDomain() != null && !request.getFilterDomain().isBlank()) {
                if (!url.contains(request.getFilterDomain())) {
                    skipped++;
                    continue;
                }
            }

            // Deduplication — build fingerprint from method + cleaned URL + normalized body
            String rawBody = req.path("postData").path("text").asText("").trim();
            String fingerprint = method + "|" + cleanUrl(url) + "|" + normalizeBody(rawBody);
            if (!seenFingerprints.add(fingerprint)) {
                log.debug("Skipping duplicate: {} {}", method, url);
                skipped++;
                continue;
            }

            try {
                FlowStep step = buildStep(entry, req, headers, flow, stepOrder,
                        request.isIncludeResponseAsLastResponse());
                flowStepRepository.save(step);
                stepOrder++;
                created++;
            } catch (Exception e) {
                log.warn("Skipping HAR entry '{}' — {}", url, e.getMessage());
                skipped++;
            }
        }

        log.info("HAR import complete — {} steps created, {} entries skipped (incl. duplicates)", created, skipped);

        if (created == 0) {
            throw new IllegalArgumentException(
                    "No importable API requests found in this HAR file. " +
                            created + " steps created, " + skipped + " entries skipped.\n" +
                            "Common reasons:\n" +
                            "- HAR only contains frontend framework traffic (Next.js RSC, React, Angular)\n" +
                            "- All requests are static assets (JS, CSS, images)\n" +
                            "- All requests failed (4xx/5xx)\n" +
                            "Tip: Use 'filterDomain' to target only your backend API domain."
            );
        }

        return flowService.getById(flow.getId());
    }

    // ─── Filtering ────────────────────────────────────────────────────────────

    private boolean shouldInclude(JsonNode entry, String url, String method, Map<String, String> headers) {
        if (url.isBlank()) return false;
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("chrome-extension:")) return false;

        for (String seg : SKIP_URL_SEGMENTS) {
            if (url.contains(seg)) return false;
        }

        String pathOnly = url.split("\\?")[0].toLowerCase();
        for (String ext : SKIP_EXTENSIONS) {
            if (pathOnly.endsWith(ext)) return false;
        }

        if ("OPTIONS".equals(method) || "HEAD".equals(method)) return false;

        // Next.js framework calls
        if ("1".equals(headers.get("rsc"))) return false;
        if (url.contains("_rsc=")) return false;
        if (headers.containsKey("next-router-prefetch")) return false;
        if (headers.containsKey("next-action")) return false;

        // Failed HTTP responses
        int status = entry.path("response").path("status").asInt(0);
        if (status >= 400) return false;

        // HTTP 200 but body signals error
        if (status == 200) {
            String responseText = entry.path("response").path("content").path("text").asText("").trim();
            if (!responseText.isBlank() && responseText.startsWith("{")) {
                try {
                    if (hasErrorStatusInBody(objectMapper.readTree(responseText))) return false;
                } catch (Exception ignored) {}
            }
        }

        // Chrome resourceType
        JsonNode resourceType = entry.path("_resourceType");
        if (!resourceType.isMissingNode()) {
            if (!API_RESOURCE_TYPES.contains(resourceType.asText("").toLowerCase())) return false;
        }

        // Response MIME
        String mime = entry.path("response").path("content").path("mimeType").asText("").toLowerCase();
        if (!mime.isBlank()) {
            for (String skip : SKIP_MIME_PREFIXES) {
                if (mime.startsWith(skip)) return false;
            }
        }

        return true;
    }

    // ─── Body normalization for deduplication ─────────────────────────────────

    /**
     * Normalizes request body for fingerprinting.
     * For requests wrapping an inner API call (e.g. encryptedPayload pattern),
     * uses the inner apiURL + method as the fingerprint key so that
     * identical wrapper calls with the same inner intent are deduplicated,
     * while different inner calls are preserved even on the same outer endpoint.
     */
    private String normalizeBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "";
        try {
            JsonNode body = objectMapper.readTree(rawBody);
            // Detect wrapper pattern: { encryptedPayload: { apiURL, method, payload } }
            JsonNode payload = body.path("encryptedPayload");
            if (!payload.isMissingNode() && payload.isObject()) {
                String innerUrl = payload.path("apiURL").asText("");
                String innerMethod = payload.path("method").asText("");
                JsonNode innerPayload = payload.path("payload");
                // If inner payload is empty ({} or []) — use only apiURL+method as fingerprint
                // This deduplicates repeated metadata/config fetches with no actual data
                if (isEmptyNode(innerPayload)) {
                    return "WRAPPED|" + innerMethod + "|" + innerUrl;
                }
                // Inner payload has data — include it to preserve distinct business calls
                return "WRAPPED|" + innerMethod + "|" + innerUrl + "|" + innerPayload.toString();
            }
        } catch (Exception ignored) {}
        // Not a wrapper pattern — use raw body as-is
        return rawBody;
    }

    private boolean isEmptyNode(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return true;
        if (node.isObject()) return node.size() == 0;
        if (node.isArray()) return node.size() == 0;
        return false;
    }

    // ─── Body error check ─────────────────────────────────────────────────────

    private boolean hasErrorStatusInBody(JsonNode body) {
        String[] statusFields = {"status", "statusCode", "code", "responseCode", "errorCode", "httpStatus"};
        Set<String> errorStrings = Set.of(
                "error", "fail", "failed", "failure", "exception",
                "500", "501", "502", "503", "504",
                "400", "401", "403", "404", "405", "409", "422", "429"
        );
        for (String field : statusFields) {
            JsonNode node = body.path(field);
            if (node.isMissingNode()) continue;
            if (node.isNumber() && node.asInt() >= 400) return true;
            if (node.isTextual()) {
                String val = node.asText("").trim().toLowerCase();
                if (errorStrings.contains(val)) return true;
                try { if (Integer.parseInt(val) >= 400) return true; } catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }

    // ─── Step builder ─────────────────────────────────────────────────────────

    private FlowStep buildStep(JsonNode entry, JsonNode req, Map<String, String> headers,
                               FlowDefinition flow, int stepOrder, boolean captureResponse) throws Exception {
        FlowStep step = new FlowStep();
        step.setFlow(flow);
        step.setStepOrder(stepOrder);
        step.setMethod(req.path("method").asText("GET").toUpperCase());

        String rawUrl = req.path("url").asText("");
        step.setUrl(cleanUrl(rawUrl));
        step.setName(deriveName(rawUrl, stepOrder));

        Map<String, String> filteredHeaders = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (!SKIP_HEADERS.contains(k)) filteredHeaders.put(k, v);
        });
        if (!filteredHeaders.isEmpty()) {
            step.setHeadersJson(objectMapper.writeValueAsString(filteredHeaders));
        }

        JsonNode postData = req.path("postData");
        if (!postData.isMissingNode() && postData.has("text")) {
            String bodyText = postData.path("text").asText("").trim();
            if (!bodyText.isBlank()) {
                try {
                    JsonNode parsed = objectMapper.readTree(bodyText);
                    step.setBodyJson(objectMapper.writeValueAsString(parsed));
                } catch (Exception e) {
                    step.setBodyJson(bodyText);
                }
            }
        }

        if (captureResponse) {
            String responseText = entry.path("response").path("content").path("text").asText("").trim();
            if (!responseText.isBlank() && isJsonResponse(entry, responseText)) {
                step.setLastResponseBody(responseText);
            }
        }

        return step;
    }

    private boolean isJsonResponse(JsonNode entry, String responseText) {
        String mime = entry.path("response").path("content").path("mimeType").asText("").toLowerCase();
        if (mime.contains("json")) return true;
        if (responseText.startsWith("{") || responseText.startsWith("[")) {
            try { objectMapper.readTree(responseText); return true; } catch (Exception e) { return false; }
        }
        return false;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, String> extractHeaderMap(JsonNode headersNode) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (headersNode.isArray()) {
            for (JsonNode h : headersNode) {
                String name = h.path("name").asText("").toLowerCase();
                String value = h.path("value").asText("");
                if (!name.isBlank()) headers.put(name, value);
            }
        }
        return headers;
    }

    private String cleanUrl(String url) {
        return url
                .replaceAll("[?&]_rsc=[^&]*", "")
                .replaceAll("[?&]_t=[^&]*", "")
                .replaceAll("[?&]_=[^&]*", "")
                .replaceAll("\\?$", "")
                .replaceAll("&&", "&")
                .replaceAll("\\?&", "?");
    }

    private String deriveName(String url, int stepOrder) {
        try {
            String path = url.split("\\?")[0];
            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i].trim();
                if (!part.isBlank()
                        && !part.matches("[\\da-f]{8}-[\\da-f]{4}-.*")
                        && !part.matches("\\d+")) {
                    return "Step " + stepOrder + " - " + part.replaceAll("[_-]", " ");
                }
            }
        } catch (Exception ignored) {}
        return "Step " + stepOrder;
    }
}