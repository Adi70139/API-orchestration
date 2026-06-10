package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.example.flowengine.entity.ModuleEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppAssistantService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Set<String> READ_ACTIONS = Set.of(
            "list_modules", "list_flows", "get_flow", "list_steps",
            "list_environments", "get_environment"
    );
    private static final Map<String, List<String>> REQUIRED_ARGUMENTS = Map.ofEntries(
            Map.entry("create_module", List.of("name")),
            Map.entry("update_module", List.of("moduleId", "name")),
            Map.entry("delete_module", List.of("moduleId")),
            Map.entry("get_flow", List.of("flowId")),
            Map.entry("create_flow", List.of("name", "module")),
            Map.entry("update_flow", List.of("flowId", "name")),
            Map.entry("delete_flow", List.of("flowId")),
            Map.entry("duplicate_flow", List.of("flowId")),
            Map.entry("list_steps", List.of("flowId")),
            Map.entry("create_step", List.of("flowId", "name", "method", "url")),
            Map.entry("update_step", List.of("stepId", "name", "method", "url")),
            Map.entry("delete_step", List.of("stepId")),
            Map.entry("duplicate_step", List.of("flowId", "stepId")),
            Map.entry("reorder_steps", List.of("flowId", "steps")),
            Map.entry("list_environments", List.of("moduleId")),
            Map.entry("get_environment", List.of("environmentId")),
            Map.entry("create_environment", List.of("moduleId", "name")),
            Map.entry("update_environment", List.of("environmentId", "name")),
            Map.entry("delete_environment", List.of("environmentId")),
            Map.entry("set_flow_environment", List.of("flowId", "environmentId")),
            Map.entry("clear_flow_environment", List.of("flowId")),
            Map.entry("run_flow", List.of("flowId")),
            Map.entry("run_module", List.of("moduleId"))
    );

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;
    private final ModuleService moduleService;
    private final FlowService flowService;
    private final FlowStepService flowStepService;
    private final EnvironmentService environmentService;
    private final ExecutorService executorService;

    @Value("${llm.provider:ollama}")
    private String llmProvider;
    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    @Value("${ollama.model:llama3.2}")
    private String ollamaModel;
    @Value("${groq.api.key:}")
    private String groqApiKey;
    @Value("${groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    public Map<String, Object> toolCatalog() {
        Map<String, Object> tools = new LinkedHashMap<>();
        READ_ACTIONS.forEach(action -> tools.put(action, Map.of(
                "readOnly", true,
                "requiredParameters", REQUIRED_ARGUMENTS.getOrDefault(action, List.of()))));
        REQUIRED_ARGUMENTS.forEach((action, required) -> tools.putIfAbsent(action, Map.of(
                "readOnly", false,
                "requiredParameters", required)));
        return Map.of("tools", tools);
    }

    public AssistantChatResponse chat(AssistantChatRequest request) {
        try {
            log.info("[Assistant] Request received: executeActions={} message='{}'",
                    request.getExecuteActions(), request.getMessage());
            String rawModelResponse = callModel(buildPrompt(request));
            log.info("[Assistant] Raw model response: {}", rawModelResponse);
            JsonNode plan = parsePlan(rawModelResponse);
            AssistantChatResponse response = new AssistantChatResponse();
            List<PlannedAction> plannedActions = normalizePlan(request.getMessage(), plan);
            log.info("[Assistant] Normalized actions: {}", plannedActions);

            boolean executeMutations = Boolean.TRUE.equals(request.getExecuteActions());
            boolean confirmationRequired = false;
            for (PlannedAction planned : plannedActions) {
                String action = planned.action();
                Map<String, Object> arguments = planned.arguments();
                AssistantChatResponse.ActionResult result = new AssistantChatResponse.ActionResult();
                result.setAction(action);
                result.setArguments(arguments);

                List<String> missing = missingArguments(action, arguments);
                if (!missing.isEmpty()) {
                    log.info("[Assistant] Action '{}' missing required parameters: {}", action, missing);
                    result.setStatus("MISSING_PARAMETERS");
                    result.setError("Missing required parameters: " + missing);
                    response.getMissingParameters().addAll(missing);
                } else if (!READ_ACTIONS.contains(action) && !executeMutations) {
                    log.info("[Assistant] Action '{}' proposed and awaiting confirmation", action);
                    result.setStatus("PROPOSED");
                    confirmationRequired = true;
                } else {
                    executeAction(result, action, arguments);
                }
                response.getActions().add(result);
            }
            response.setConfirmationRequired(confirmationRequired);
            response.setReply(buildVerifiedReply(plan.path("reply").asText(""), response));
            log.info("[Assistant] Response: reply='{}' confirmationRequired={} actionCount={}",
                    response.getReply(), response.isConfirmationRequired(), response.getActions().size());
            return response;
        } catch (Exception e) {
            log.error("Assistant request failed", e);
            throw new RuntimeException("Assistant request failed: " + e.getMessage(), e);
        }
    }

    private void executeAction(AssistantChatResponse.ActionResult result, String action, Map<String, Object> args) {
        try {
            log.info("[Assistant] Executing tool '{}' with arguments {}", action, args);
            Object output = switch (action) {
                case "list_modules" -> moduleService.getAll();
                case "create_module" -> moduleService.create(convert(args, ModuleEntity.class));
                case "update_module" -> moduleService.update(convert(args, ModuleUpdateRequest.class), longArg(args, "moduleId"));
                case "delete_module" -> {
                    moduleService.delete(longArg(args, "moduleId"));
                    yield Map.of("deleted", true);
                }
                case "list_flows" -> flowService.getAll();
                case "get_flow" -> flowService.getById(longArg(args, "flowId"));
                case "create_flow" -> flowService.create(convert(args, FlowRequest.class));
                case "update_flow" -> flowService.update(convert(args, FlowRequest.class), longArg(args, "flowId"));
                case "delete_flow" -> {
                    flowService.delete(longArg(args, "flowId"));
                    yield Map.of("deleted", true);
                }
                case "duplicate_flow" -> flowService.duplicateFlow(
                        longArg(args, "flowId"), convert(args, DuplicateFlowRequest.class));
                case "list_steps" -> flowStepService.getByFlowId(longArg(args, "flowId"));
                case "list_environments" -> environmentService.getByModuleId(longArg(args, "moduleId"));
                case "get_environment" -> environmentService.getById(longArg(args, "environmentId"));
                case "create_step" -> flowStepService.create(longArg(args, "flowId"), convert(args, FlowStepRequest.class));
                case "update_step" -> flowStepService.update(longArg(args, "stepId"), convert(args, FlowStepRequest.class));
                case "delete_step" -> {
                    flowStepService.delete(longArg(args, "stepId"));
                    yield Map.of("deleted", true);
                }
                case "duplicate_step" -> flowStepService.duplicate(
                        longArg(args, "flowId"), longArg(args, "stepId"),
                        convert(args, DuplicateFlowStepRequest.class));
                case "reorder_steps" -> flowStepService.reorder(
                        longArg(args, "flowId"), convert(args, FlowStepReorderRequest.class));
                case "create_environment" -> environmentService.create(
                        longArg(args, "moduleId"), convert(args, EnvironmentRequest.class));
                case "update_environment" -> environmentService.update(
                        longArg(args, "environmentId"), convert(args, EnvironmentRequest.class));
                case "delete_environment" -> {
                    environmentService.delete(longArg(args, "environmentId"));
                    yield Map.of("deleted", true);
                }
                case "set_flow_environment" -> flowService.setDefaultEnvironment(
                        longArg(args, "flowId"), longArg(args, "environmentId"));
                case "clear_flow_environment" -> flowService.clearDefaultEnvironment(longArg(args, "flowId"));
                case "run_flow" -> executorService.runFlow(
                        longArg(args, "flowId"), optionalLongArg(args, "environmentId"));
                case "run_module" -> executorService.runModule(
                        longArg(args, "moduleId"), optionalLongArg(args, "environmentId"));
                default -> throw new IllegalArgumentException("Unsupported assistant action: " + action);
            };
            result.setStatus("EXECUTED");
            result.setResult(output);
            log.info("[Assistant] Tool '{}' executed successfully", action);
        } catch (Exception e) {
            result.setStatus("FAILED");
            result.setError(e.getMessage());
            log.error("[Assistant] Tool '{}' failed: {}", action, e.getMessage(), e);
        }
    }

    private List<PlannedAction> normalizePlan(String message, JsonNode plan) {
        List<PlannedAction> actions = new ArrayList<>();
        for (JsonNode actionNode : plan.path("actions")) {
            String action = actionNode.path("action").asText();
            if (!action.isBlank() && (READ_ACTIONS.contains(action) || REQUIRED_ARGUMENTS.containsKey(action))) {
                actions.add(new PlannedAction(action, objectMapper.convertValue(
                        actionNode.path("arguments"), new com.fasterxml.jackson.core.type.TypeReference<>() {})));
            }
        }

        // Small local models sometimes answer correctly in prose but select an unrelated read tool.
        // Recover the unambiguous common creation intent without trusting that contradictory tool call.
        Matcher createModule = Pattern.compile(
                "(?i)\\bcreate\\s+(?:a\\s+)?module(?:\\s+(?:named|called))?\\s+[\"']?([^\"']+?)[\"']?\\s*$")
                .matcher(message.trim());
        if (createModule.find() && actions.stream().noneMatch(a -> "create_module".equals(a.action()))) {
            actions.removeIf(a -> "list_modules".equals(a.action()));
            actions.add(new PlannedAction("create_module", new LinkedHashMap<>(
                    Map.of("name", createModule.group(1).trim()))));
        }

        if (isListModulesIntent(message)
                && actions.stream().noneMatch(a -> "list_modules".equals(a.action()))) {
            actions.removeIf(a -> !READ_ACTIONS.contains(a.action()));
            actions.add(new PlannedAction("list_modules", new LinkedHashMap<>()));
        }
        return actions;
    }

    private boolean isListModulesIntent(String message) {
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("module")
                && (normalized.contains("list") || normalized.contains("show")
                || normalized.contains("all modules") || normalized.contains("what modules"));
    }

    private List<String> missingArguments(String action, Map<String, Object> args) {
        return REQUIRED_ARGUMENTS.getOrDefault(action, List.of()).stream()
                .filter(key -> {
                    Object value = args.get(key);
                    return value == null || (value instanceof String text && text.isBlank())
                            || (value instanceof List<?> list && list.isEmpty());
                })
                .toList();
    }

    private String buildVerifiedReply(String modelReply, AssistantChatResponse response) {
        if (!response.getMissingParameters().isEmpty()) {
            return "Please provide the following required information: "
                    + String.join(", ", response.getMissingParameters()) + ".";
        }
        long failed = response.getActions().stream().filter(a -> "FAILED".equals(a.getStatus())).count();
        if (failed > 0) {
            return "I could not complete " + failed + " action(s). Please review the errors shown below.";
        }
        long executed = response.getActions().stream().filter(a -> "EXECUTED".equals(a.getStatus())).count();
        if (executed > 0) {
            if (response.getActions().size() == 1
                    && "list_modules".equals(response.getActions().get(0).getAction())) {
                return formatModuleList(response.getActions().get(0).getResult());
            }
            return "Completed " + executed + " application action(s) successfully.";
        }
        long proposed = response.getActions().stream().filter(a -> "PROPOSED".equals(a.getStatus())).count();
        if (proposed > 0) {
            return "I prepared " + proposed + " action(s). Please confirm to execute them.";
        }
        return modelReply.isBlank() ? "How can I help with the Flow Engine application?" : modelReply;
    }

    private String formatModuleList(Object result) {
        JsonNode modules = objectMapper.valueToTree(result);
        if (!modules.isArray() || modules.isEmpty()) {
            return "There are no modules yet.";
        }
        List<String> names = new ArrayList<>();
        modules.forEach(module -> names.add(module.path("name").asText("Unnamed module")));
        return "Modules (" + names.size() + "): " + String.join(", ", names) + ".";
    }

    private String buildPrompt(AssistantChatRequest request) throws Exception {
        List<Map<String, String>> history = new ArrayList<>();
        if (request.getHistory() != null) {
            request.getHistory().stream().limit(12).forEach(message ->
                    history.add(Map.of(
                            "role", message.getRole() == null ? "user" : message.getRole(),
                            "content", message.getContent() == null ? "" : message.getContent())));
        }

        return """
                You are the Flow Engine application assistant. Answer ONLY questions related to this application.
                Politely refuse unrelated questions. Help users build and manage modules, flows, steps, environments,
                imports, executions, schedules, assertions, skip conditions, custom methods, reports, trends,
                browser recordings, step reordering, placeholders, retries, polling, and previous-step body inheritance.

                Return ONLY JSON:
                {"reply":"concise helpful response","actions":[{"action":"action_name","arguments":{}}]}
                Use actions only when the user asks to inspect or change application data. Ask for missing required
                information in reply and return no action. Never invent IDs.

                ALLOWED ACTIONS:
                list_modules {}
                create_module {name, description}
                update_module {moduleId, name, description}
                delete_module {moduleId}
                list_flows {}
                get_flow {flowId}
                create_flow {name, description, module, environmentId}
                update_flow {flowId, name, description}
                delete_flow {flowId}
                duplicate_flow {flowId, name, targetModuleId}
                list_steps {flowId}
                list_environments {moduleId}
                get_environment {environmentId}
                create_step {flowId, name, description, method, url, headersJson, bodyJson,
                  inheritBodyFromPreviousStep, bodySourceStepId, retryCount, retryDelayMs, initialDelayMs,
                  pollUntilSuccess, pollIntervalMs, pollMaxAttempts, pollExpectedStatus, assertions, skipCondition}
                update_step {stepId, name, description, method, url, headersJson, bodyJson,
                  inheritBodyFromPreviousStep, bodySourceStepId, retryCount, retryDelayMs, initialDelayMs,
                  pollUntilSuccess, pollIntervalMs, pollMaxAttempts, pollExpectedStatus, assertions, skipCondition}
                delete_step {stepId}
                duplicate_step {flowId, stepId, name}
                reorder_steps {flowId, steps:[{stepId,stepOrder}]}
                create_environment {moduleId, name, variables}
                update_environment {environmentId, name, variables}
                delete_environment {environmentId}
                set_flow_environment {flowId, environmentId}
                clear_flow_environment {flowId}
                run_flow {flowId, environmentId}
                run_module {moduleId, environmentId}

                Important app rules:
                - Flows belong to modules. Flow create uses the module NAME in "module".
                - New steps append automatically. Reorder must include every flow step exactly once.
                - Placeholders use {fieldName} and resolve from earlier responses/environment variables.
                - Body inheritance uses a previous step response; bodyJson supplies deep-merged overrides.
                - Do not expose secrets or request environment variable values in replies.
                - Destructive or modifying actions will be confirmed by the application before execution.
                - NEVER claim an action succeeded. The backend produces success messages after executing tools.
                - If a required parameter is missing, ask one concise question and return the intended action
                  with all known arguments. Do not replace it with a list/read action.

                Conversation history:
                %s

                User message:
                %s
                """.formatted(objectMapper.writeValueAsString(history), request.getMessage());
    }

    private String callModel(String prompt) throws Exception {
        log.info("[Assistant] Calling LLM provider='{}' model='{}'",
                llmProvider, "groq".equalsIgnoreCase(llmProvider) ? groqModel : ollamaModel);
        if ("groq".equalsIgnoreCase(llmProvider)) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", groqModel,
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "temperature", 0.1,
                    "max_tokens", 3000));
            Request request = new Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer " + groqApiKey)
                    .post(RequestBody.create(body, JSON)).build();
            try (Response response = okHttpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) throw new IllegalStateException("Groq error: HTTP " + response.code());
                log.info("[Assistant] Groq call completed with HTTP {}", response.code());
                return objectMapper.readTree(response.body().string())
                        .path("choices").get(0).path("message").path("content").asText();
            }
        }

        String body = objectMapper.writeValueAsString(Map.of(
                "model", ollamaModel, "prompt", prompt, "stream", false, "format", "json"));
        Request request = new Request.Builder().url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(body, JSON)).build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("Ollama error: HTTP " + response.code());
            log.info("[Assistant] Ollama call completed with HTTP {}", response.code());
            return objectMapper.readTree(response.body().string()).path("response").asText();
        }
    }

    private JsonNode parsePlan(String raw) throws Exception {
        String clean = raw.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        int start = clean.indexOf('{');
        int end = clean.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("Assistant returned invalid JSON");
        JsonNode plan = objectMapper.readTree(clean.substring(start, end + 1));
        if (!plan.has("actions") || !plan.path("actions").isArray()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) plan).set("actions", objectMapper.createArrayNode());
        }
        return plan;
    }

    private <T> T convert(Map<String, Object> args, Class<T> type) {
        return objectMapper.convertValue(args, type);
    }

    private Long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) throw new IllegalArgumentException("Missing required argument: " + key);
        return Long.valueOf(value.toString());
    }

    private Long optionalLongArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : Long.valueOf(value.toString());
    }

    private record PlannedAction(String action, Map<String, Object> arguments) {}
}
