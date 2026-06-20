package com.example.flowengine.recording;

import com.example.flowengine.DTO.FlowDetailedDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/record")
@RequiredArgsConstructor
@Tag(name = "Browser Recording", description = "Launch a browser, record API calls, import as flow")
public class BrowserRecordingController {

    private final BrowserRecordingService recordingService;

    /**
     * Start a recording session.
     * Launches Chrome, opens the target URL, starts capturing all network requests.
     *
     * POST /record/start
     * {
     *   "url": "https://your-app.com",
     *   "include": "api.your-app.com",   // optional domain filter
     *   "moduleId": 1,                    // required for import
     *   "flowName": "My Recording",       // optional
     *   "port": 9222                      // optional, default 9222
     * }
     */
    @PostMapping("/start")
    @Operation(
            summary = "Start browser recording",
            description = "Launches Chrome and opens the target URL. " +
                    "All XHR/Fetch requests made in the browser are captured. " +
                    "Returns a sessionId — use it to stop and import."
    )
    public RecordingSession start(@RequestBody RecordingStartRequest request) throws Exception {
        return recordingService.start(request);
    }

    /**
     * Get status of a recording session — how many requests captured so far.
     * GET /record/{sessionId}
     */
    @GetMapping("/{sessionId}")
    @Operation(summary = "Get recording status", description = "Returns current status and request count.")
    public RecordingSession status(@PathVariable String sessionId) {
        return recordingService.getStatus(sessionId);
    }

    /**
     * List all active recording sessions.
     * GET /record
     */
    @GetMapping
    @Operation(summary = "List all recording sessions")
    public List<RecordingSession> list() {
        return recordingService.list();
    }

    /**
     * Stop recording and immediately create a flow from captured requests.
     * POST /record/{sessionId}/stop
     */
    @PostMapping("/{sessionId}/stop")
    @Operation(
            summary = "Stop recording and import as flow",
            description = "Stops the browser recording and creates a flow with all captured API calls as steps. " +
                    "Same filtering as HAR import — static assets, failed requests, framework calls excluded. " +
                    "Chrome is closed automatically."
    )
    public FlowDetailedDTO stopAndImport(@PathVariable String sessionId) throws Exception {
        return recordingService.stopAndImport(sessionId);
    }

    /**
     * Stop recording and return captured requests for review before importing.
     * POST /record/{sessionId}/preview
     */
    @PostMapping("/{sessionId}/preview")
    @Operation(
            summary = "Stop and preview captured requests",
            description = "Stops recording and returns all captured requests for review. " +
                    "Use /import after reviewing to create the flow."
    )
    public List<RecordedRequest> stopAndPreview(@PathVariable String sessionId) {
        return recordingService.stopAndPreview(sessionId);
    }

    /**
     * Import previewed requests into a flow.
     * POST /record/{sessionId}/import
     */
    @PostMapping("/{sessionId}/import")
    @Operation(
            summary = "Import previewed requests as flow",
            description = "Creates a flow from previously previewed requests."
    )
    public FlowDetailedDTO importPreview(
            @PathVariable String sessionId,
            @RequestParam Long moduleId,
            @RequestParam(required = false) Long flowId,
            @RequestParam(required = false) String flowName) throws Exception {
        return recordingService.importPreview(sessionId, moduleId, flowId, flowName);
    }

    /**
     * Pause an active recording — browser stays open, capture is suspended.
     * POST /record/{sessionId}/pause
     */
    @PostMapping("/{sessionId}/pause")
    @Operation(
            summary = "Pause recording",
            description = "Suspends capture without closing the browser or losing what's already been " +
                    "recorded. Use this to navigate, log in, or click around without those actions " +
                    "becoming flow steps. Call /resume to continue capturing."
    )
    public RecordingSession pause(@PathVariable String sessionId) {
        return recordingService.pause(sessionId);
    }

    /**
     * Resume a paused recording.
     * POST /record/{sessionId}/resume
     */
    @PostMapping("/{sessionId}/resume")
    @Operation(summary = "Resume recording", description = "Resumes capture on a paused recording session.")
    public RecordingSession resume(@PathVariable String sessionId) {
        return recordingService.resume(sessionId);
    }

    /**
     * Discard a recording session without importing.
     * DELETE /record/{sessionId}
     */
    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Discard recording", description = "Stops the browser and discards all captured requests.")
    public ResponseEntity<Void> discard(@PathVariable String sessionId) {
        recordingService.discard(sessionId);
        return ResponseEntity.noContent().build();
    }
}