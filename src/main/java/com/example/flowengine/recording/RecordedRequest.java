package com.example.flowengine.recording;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class RecordedRequest {
    private String requestId;
    private String startedAt;
    private String initiator;
    private String method;
    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private String postData;
    private int status;
    private String mimeType;
    private Map<String, String> responseHeaders = new LinkedHashMap<>();
    private String responseBody;
    private boolean responseBodyBase64Encoded;
}