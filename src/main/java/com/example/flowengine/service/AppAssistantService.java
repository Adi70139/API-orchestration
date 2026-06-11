package com.example.flowengine.service;

import com.example.flowengine.DTO.AssistantChatRequest;
import com.example.flowengine.DTO.AssistantChatResponse;
import com.example.flowengine.assistant.FlowEngineAssistant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thin adapter between the REST controller and the LangChain4j agent.
 * Keeps the same external contract (AssistantChatRequest / AssistantChatResponse)
 * so the frontend needs zero changes.
 *
 * The old hand-rolled JSON-prompt approach has been replaced by LangChain4j AiServices
 * with proper @Tool function-calling. The LLM now calls typed Java methods directly
 * instead of returning a JSON blob that we try to parse and execute.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppAssistantService {

    private static final Set<String> READ_ACTIONS = Set.of(
            "list_modules", "list_flows", "get_flow", "list_steps",
            "list_environments", "get_environment"
    );

    private final FlowEngineAssistant assistant;

    public Map<String, Object> toolCatalog() {
        // Kept for backwards-compatibility with the /assistant/tools endpoint.
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("list_modules",       Map.of("readOnly", true,  "description", "List all modules"));
        tools.put("create_module",      Map.of("readOnly", false, "requiredParameters", List.of("name")));
        tools.put("update_module",      Map.of("readOnly", false, "requiredParameters", List.of("moduleId", "name")));
        tools.put("delete_module",      Map.of("readOnly", false, "requiredParameters", List.of("moduleId")));
        tools.put("list_flows",         Map.of("readOnly", true,  "description", "List all flows"));
        tools.put("get_flow",           Map.of("readOnly", true,  "requiredParameters", List.of("flowId")));
        tools.put("create_flow",        Map.of("readOnly", false, "requiredParameters", List.of("name", "moduleName")));
        tools.put("update_flow",        Map.of("readOnly", false, "requiredParameters", List.of("flowId", "name", "moduleName")));
        tools.put("delete_flow",        Map.of("readOnly", false, "requiredParameters", List.of("flowId")));
        tools.put("duplicate_flow",     Map.of("readOnly", false, "requiredParameters", List.of("flowId")));
        tools.put("list_steps",         Map.of("readOnly", true,  "requiredParameters", List.of("flowId")));
        tools.put("create_step",        Map.of("readOnly", false, "requiredParameters", List.of("flowId", "name", "method", "url")));
        tools.put("update_step",        Map.of("readOnly", false, "requiredParameters", List.of("stepId", "name", "method", "url")));
        tools.put("delete_step",        Map.of("readOnly", false, "requiredParameters", List.of("stepId")));
        tools.put("duplicate_step",     Map.of("readOnly", false, "requiredParameters", List.of("flowId", "stepId")));
        tools.put("list_environments",  Map.of("readOnly", true,  "requiredParameters", List.of("moduleId")));
        tools.put("create_environment", Map.of("readOnly", false, "requiredParameters", List.of("moduleId", "name")));
        tools.put("delete_environment", Map.of("readOnly", false, "requiredParameters", List.of("environmentId")));
        tools.put("run_flow",           Map.of("readOnly", false, "requiredParameters", List.of("flowId")));
        tools.put("run_module",         Map.of("readOnly", false, "requiredParameters", List.of("moduleId")));
        return Map.of("tools", tools);
    }

    public AssistantChatResponse chat(AssistantChatRequest request) {
        log.info("[Assistant] message='{}'", request.getMessage());
        try {
            // Build the message — prepend history if provided so the agent has context.
            // LangChain4j handles its own memory internally, but we also accept history
            // from the frontend for stateless/session-less clients.
            String userMessage = buildMessageWithHistory(request);
            String reply = assistant.chat(userMessage);
            log.info("[Assistant] reply='{}'", reply);

            // Wrap in the existing response shape. Since LangChain4j handles tool execution
            // internally (no proposal/confirmation round-trip), actions are always EXECUTED.
            // confirmationRequired is always false — tool calls happen inline.
            AssistantChatResponse response = new AssistantChatResponse();
            response.setReply(reply);
            response.setConfirmationRequired(false);
            return response;

        } catch (Exception e) {
            log.error("[Assistant] Failed", e);
            AssistantChatResponse error = new AssistantChatResponse();
            error.setReply("Sorry, I encountered an error: " + e.getMessage());
            return error;
        }
    }

    private String buildMessageWithHistory(AssistantChatRequest request) {
        if (request.getHistory() == null || request.getHistory().isEmpty()) {
            return request.getMessage();
        }
        // Prepend recent history as context for stateless clients.
        // LangChain4j's own MessageWindowChatMemory handles in-process sessions,
        // so this is only needed when history is explicitly passed.
        StringBuilder sb = new StringBuilder();
        request.getHistory().stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .limit(10)
                .forEach(m -> sb.append("[")
                        .append("user".equals(m.getRole()) ? "User" : "Assistant")
                        .append("]: ").append(m.getContent()).append("\n"));
        sb.append("[User]: ").append(request.getMessage());
        return sb.toString();
    }
}