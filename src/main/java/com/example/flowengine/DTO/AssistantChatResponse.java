package com.example.flowengine.DTO;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AssistantChatResponse {
    private String reply;
    private boolean confirmationRequired;
    private List<String> missingParameters = new ArrayList<>();
    private List<ActionResult> actions = new ArrayList<>();

    @Data
    public static class ActionResult {
        private String action;
        private Map<String, Object> arguments;
        private String status;
        private Object result;
        private String error;
    }
}
