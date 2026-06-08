package com.example.flowengine.recording;

import lombok.Data;

import java.util.Set;

@Data
public class RecordingStartRequest {

    // URL to open in Chrome
    private String url = "about:blank";

    // CDP remote debugging port — must be free on the server
    private int port = 9222;

    // If true, attach to an already-running Chrome instead of launching new one
    private boolean attach = false;

    // Only record requests whose URL contains this string (e.g. "api.", "/api/")
    // null = record everything
    private String include;

    // Chrome binary path — null = auto-detect
    private String chromePath;

    // Headers to redact in output (still captured but value replaced with <redacted>)
    // Empty by default — we want full fidelity for flow creation
    private Set<String> redactedHeaders = Set.of();

    // Flow Engine specific — where to import captured requests
    private Long moduleId;
    private Long flowId;      // null = create new flow
    private String flowName;  // used if flowId is null
}