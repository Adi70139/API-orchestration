package com.example.flowengine.recording;

import lombok.Data;

@Data
public class RecordingSession {
    private String id;
    private String status;   // STARTING | RECORDING | STOPPING | STOPPED | ERROR
    private String url;
    private String include;
    private int port;
    private int requestCount;
}