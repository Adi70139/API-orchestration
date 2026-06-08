package com.example.flowengine.recording;

import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.HarImportRequest;
import com.example.flowengine.service.HarImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class BrowserRecordingService {

    private final Map<String, ManagedRecording> recordings = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final HarImportService harImportService;

    public BrowserRecordingService(HarImportService harImportService) {
        this.harImportService = harImportService;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ─── Start ────────────────────────────────────────────────────────────────

    public RecordingSession start(RecordingStartRequest request) throws Exception {
        String id = UUID.randomUUID().toString();
        CdpRecordingSession session = new CdpRecordingSession(request, objectMapper);
        session.start();
        recordings.put(id, new ManagedRecording(id, request, session));
        log.info("Recording started: id={} url={} port={}", id, request.getUrl(), request.getPort());
        return toSession(id, request, session);
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    public RecordingSession getStatus(String id) {
        ManagedRecording r = require(id);
        return toSession(id, r.request(), r.session());
    }

    public List<RecordingSession> list() {
        return recordings.values().stream()
                .map(r -> toSession(r.id(), r.request(), r.session()))
                .toList();
    }

    // ─── Stop + import ────────────────────────────────────────────────────────

    /**
     * Stops recording, applies same filtering as HarImportService,
     * and creates a flow directly from captured requests.
     */
    public FlowDetailedDTO stopAndImport(String id) throws Exception {
        ManagedRecording r = require(id);
        r.session().stop();

        List<RecordedRequest> captured = r.session().snapshot();
        log.info("Recording stopped: id={} captured={} requests", id, captured.size());

        if (captured.isEmpty()) {
            throw new IllegalStateException(
                    "No requests were captured. Make sure you performed actions in the browser.");
        }

        // Build HAR from captured requests — reuse existing HarImportService pipeline
        // so all filtering (Next.js, static assets, error status in body etc.) is applied
        String harJson = buildHar(captured);
        MockMultipartFile file = new MockMultipartFile(
                "file", "recording.har", "application/json", harJson.getBytes());

        HarImportRequest importRequest = new HarImportRequest();
        importRequest.setModuleId(r.request().getModuleId());
        importRequest.setFlowId(r.request().getFlowId());
        importRequest.setFlowName(r.request().getFlowName() != null
                ? r.request().getFlowName() : "Browser Recording");
        importRequest.setIncludeResponseAsLastResponse(true);

        if (r.request().getInclude() != null && !r.request().getInclude().isBlank()) {
            importRequest.setFilterDomain(r.request().getInclude());
        }

        FlowDetailedDTO flow = harImportService.importHar(file, importRequest);
        recordings.remove(id);
        return flow;
    }

    /**
     * Stop recording and return raw captured requests without importing.
     * Useful when user wants to review before committing to a flow.
     */
    public List<RecordedRequest> stopAndPreview(String id) {
        ManagedRecording r = require(id);
        r.session().stop();
        List<RecordedRequest> captured = r.session().snapshot();
        log.info("Recording preview: id={} captured={} requests", id, captured.size());
        return captured;
    }

    /**
     * Import previously previewed requests into a flow.
     */
    public FlowDetailedDTO importPreview(String id, Long moduleId, Long flowId, String flowName) throws Exception {
        ManagedRecording r = require(id);
        List<RecordedRequest> captured = r.session().snapshot();

        String harJson = buildHar(captured);
        MockMultipartFile file = new MockMultipartFile(
                "file", "recording.har", "application/json", harJson.getBytes());

        HarImportRequest importRequest = new HarImportRequest();
        importRequest.setModuleId(moduleId);
        importRequest.setFlowId(flowId);
        importRequest.setFlowName(flowName != null ? flowName : "Browser Recording");
        importRequest.setIncludeResponseAsLastResponse(true);

        FlowDetailedDTO flow = harImportService.importHar(file, importRequest);
        recordings.remove(id);
        return flow;
    }

    public void discard(String id) {
        ManagedRecording r = recordings.remove(id);
        if (r != null && !"STOPPED".equals(r.session().status())) {
            r.session().stop();
        }
    }

    // ─── HAR builder ─────────────────────────────────────────────────────────

    private String buildHar(List<RecordedRequest> requests) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();

        for (RecordedRequest req : requests) {
            List<Map<String, String>> reqHeaders = new ArrayList<>();
            req.getHeaders().forEach((k, v) -> reqHeaders.add(Map.of("name", k, "value", v)));

            List<Map<String, String>> respHeaders = new ArrayList<>();
            req.getResponseHeaders().forEach((k, v) -> respHeaders.add(Map.of("name", k, "value", v)));

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("method", req.getMethod());
            request.put("url", req.getUrl());
            request.put("headers", reqHeaders);
            request.put("cookies", List.of());
            request.put("queryString", List.of());
            request.put("headersSize", -1);
            request.put("bodySize", req.getPostData() != null ? req.getPostData().length() : 0);
            if (req.getPostData() != null) {
                request.put("postData", Map.of(
                        "mimeType", req.getHeaders().getOrDefault("content-type", "application/json"),
                        "text", req.getPostData()
                ));
            }

            String responseText = req.getResponseBody() != null ? req.getResponseBody() : "";
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", req.getStatus());
            response.put("statusText", "OK");
            response.put("headers", respHeaders);
            response.put("cookies", List.of());
            response.put("content", Map.of(
                    "mimeType", req.getMimeType() != null ? req.getMimeType() : "application/json",
                    "text", responseText
            ));
            response.put("redirectURL", "");
            response.put("headersSize", -1);
            response.put("bodySize", responseText.length());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("_resourceType", "fetch");
            entry.put("request", request);
            entry.put("response", response);
            entry.put("time", 0);
            entry.put("timings", Map.of("send", 0, "wait", 0, "receive", 0));
            entries.add(entry);
        }

        Map<String, Object> har = Map.of(
                "log", Map.of(
                        "version", "1.2",
                        "creator", Map.of("name", "Flow Engine Browser Recorder", "version", "1.0"),
                        "entries", entries
                )
        );

        return objectMapper.writeValueAsString(har);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private RecordingSession toSession(String id, RecordingStartRequest req, CdpRecordingSession session) {
        RecordingSession s = new RecordingSession();
        s.setId(id);
        s.setStatus(session.status());
        s.setUrl(req.getUrl());
        s.setInclude(req.getInclude());
        s.setPort(req.getPort());
        s.setRequestCount(session.capturedCount());
        return s;
    }

    private ManagedRecording require(String id) {
        ManagedRecording r = recordings.get(id);
        if (r == null) throw new IllegalArgumentException("Recording not found: " + id);
        return r;
    }

    private record ManagedRecording(String id, RecordingStartRequest request, CdpRecordingSession session) {}
}
