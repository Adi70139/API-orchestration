package com.example.flowengine.controller;

import com.example.flowengine.DTO.AssistantChatRequest;
import com.example.flowengine.DTO.AssistantChatResponse;
import com.example.flowengine.service.AppAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
@Tag(name = "Application Assistant", description = "App-scoped chatbot for building and managing Flow Engine resources")
public class AssistantController {

    private final AppAssistantService assistantService;

    @PostMapping("/chat")
    @Operation(summary = "Chat with Flow Engine assistant",
            description = "Answers app questions and proposes or executes allowlisted Flow Engine actions.")
    public AssistantChatResponse chat(@Valid @RequestBody AssistantChatRequest request) {
        return assistantService.chat(request);
    }

    @org.springframework.web.bind.annotation.GetMapping("/tools")
    @Operation(summary = "List assistant tools",
            description = "Returns supported application tools and their required parameters.")
    public Map<String, Object> tools() {
        return assistantService.toolCatalog();
    }
}
