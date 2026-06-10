package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class AssistantChatRequest {
    @NotBlank
    private String message;
    private Boolean executeActions = false;
    private List<ChatMessage> history;

    @Data
    public static class ChatMessage {
        private String role;
        private String content;
    }
}
