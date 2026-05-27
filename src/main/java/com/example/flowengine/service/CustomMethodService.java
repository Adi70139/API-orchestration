package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.example.flowengine.constants.MethodType;
import com.example.flowengine.entity.CustomMethod;
import com.example.flowengine.entity.StepMethod;
import com.example.flowengine.repository.CustomMethodRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.example.flowengine.repository.StepMethodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomMethodService {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_SCRIPT_RETRIES = 2;

    private final CustomMethodRepository customMethodRepository;
    private final StepMethodRepository stepMethodRepository;
    private final FlowStepRepository flowStepRepository;
    private final MethodExecutorService methodExecutorService;
    private final ObjectMapper objectMapper;
    private final OkHttpClient okHttpClient;

    @Value("${llm.provider:ollama}")
    private String llmProvider;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:deepseek-coder:6.7b}")
    private String ollamaModel;

    @Value("${groq.api.key:}")
    private String groqApiKey;

    @Value("${groq.model:llama-3.1-8b-instant}")
    private String groqModel;

    // ─── List ─────────────────────────────────────────────────────────────────

    public List<CustomMethodDTO> listGlobal() {
        return customMethodRepository.findByGlobalTrueOrderByNameAsc()
                .stream().map(this::toDTO).toList();
    }

    public List<CustomMethodDTO> listAll() {
        return customMethodRepository.findAllByOrderByCreatedAtDesc()
                .stream().map(this::toDTO).toList();
    }

    public CustomMethodDTO getById(Long id) {
        return toDTO(findById(id));
    }

    // ─── LLM generation ───────────────────────────────────────────────────────

    /**
     * Generates a Groovy script via LLM, compile-checks it, retries if invalid.
     * Saved as draft (global=false) — user must test then explicitly save.
     */
    @Transactional
    public CustomMethodDTO generate(GenerateMethodRequest request) {
        log.info("Generating Groovy script for method '{}'", request.getName());

        String script = generateAndValidateScript(request.getName(), request.getDescription(),
                request.getParameters(), null);

        CustomMethod method = new CustomMethod();
        method.setName(request.getName());
        method.setDescription(request.getDescription());
        method.setType(MethodType.USER_DEFINED);
        method.setGroovyScript(script);
        method.setLlmPromptDescription(request.getDescription());
        method.setGlobal(false);

        if (request.getParameters() != null) {
            try {
                method.setParameterDefinitionsJson(objectMapper.writeValueAsString(request.getParameters()));
            } catch (Exception e) {
                log.warn("Could not serialize parameter definitions: {}", e.getMessage());
            }
        }

        return toDTO(customMethodRepository.save(method));
    }

    // ─── Edit ─────────────────────────────────────────────────────────────────

    /**
     * Edit a user-defined method.
     * - If groovyScript is provided → validate and save it directly (user manually edited)
     * - If groovyScript is null but description changed → regenerate via LLM
     * - Name/description/parameters always updated regardless
     */
    @Transactional
    public CustomMethodDTO edit(Long methodId, EditMethodRequest request) {
        CustomMethod method = findById(methodId);
        if (method.getType() != MethodType.USER_DEFINED) {
            throw new IllegalArgumentException("Cannot edit builtin methods");
        }

        if (request.getName() != null) method.setName(request.getName());
        if (request.getDescription() != null) method.setDescription(request.getDescription());

        if (request.getParameters() != null) {
            try {
                method.setParameterDefinitionsJson(objectMapper.writeValueAsString(request.getParameters()));
            } catch (Exception e) {
                log.warn("Could not serialize parameters: {}", e.getMessage());
            }
        }

        if (request.getGroovyScript() != null && !request.getGroovyScript().isBlank()) {
            // User provided script directly — just compile-check it
            String compilationError = compileCheck(request.getGroovyScript());
            if (compilationError != null) {
                throw new IllegalArgumentException("Script compilation failed: " + compilationError);
            }
            method.setGroovyScript(request.getGroovyScript());
            log.info("Method '{}' script updated manually", method.getName());
        } else if (request.getDescription() != null) {
            // Description changed — regenerate
            String newScript = generateAndValidateScript(
                    method.getName(), request.getDescription(), request.getParameters(), null);
            method.setGroovyScript(newScript);
            method.setLlmPromptDescription(request.getDescription());
            log.info("Method '{}' script regenerated from updated description", method.getName());
        }

        return toDTO(customMethodRepository.save(method));
    }

    // ─── Test ─────────────────────────────────────────────────────────────────

    public MethodExecutionResult test(TestMethodRequest request) {
        CustomMethod method = findById(request.getMethodId());
        Map<String, String> params = request.getParameters() != null ? request.getParameters() : Map.of();
        return methodExecutorService.testMethod(method, params);
    }

    // ─── Save (promote draft → global) ────────────────────────────────────────

    @Transactional
    public CustomMethodDTO save(Long methodId) {
        CustomMethod method = findById(methodId);
        method.setGlobal(true);
        log.info("Method '{}' (id={}) promoted to global", method.getName(), methodId);
        return toDTO(customMethodRepository.save(method));
    }

    // ─── Discard (delete draft) ────────────────────────────────────────────────

    /**
     * Deletes a method. For global methods, also removes all step attachments.
     * Use this when user doesn't want the generated method.
     */
    @Transactional
    public void discard(Long methodId) {
        CustomMethod method = findById(methodId);
        // Remove all step attachments first
        List<StepMethod> attachments = stepMethodRepository.findByMethodId(methodId);
        if (!attachments.isEmpty()) {
            stepMethodRepository.deleteAll(attachments);
            log.info("Removed {} step attachment(s) for method '{}'", attachments.size(), method.getName());
        }
        customMethodRepository.deleteById(methodId);
        log.info("Discarded method '{}' (id={})", method.getName(), methodId);
    }

    // ─── Attach / detach to step ──────────────────────────────────────────────

    @Transactional
    public void attachToStep(Long stepId, AttachMethodRequest request) {
        flowStepRepository.findById(stepId)
                .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));
        CustomMethod method = findById(request.getMethodId());

        // Auto-assign execution order — append after last existing method on this step
        List<StepMethod> existing = stepMethodRepository.findByStepIdOrderByExecutionOrder(stepId);
        int nextOrder = existing.isEmpty() ? 1
                : existing.stream().mapToInt(StepMethod::getExecutionOrder).max().getAsInt() + 1;

        StepMethod sm = new StepMethod();
        sm.setStep(flowStepRepository.getReferenceById(stepId));
        sm.setMethod(method);
        sm.setExecutionOrder(nextOrder);

        if (request.getParameterBindings() != null) {
            try {
                sm.setParameterBindingsJson(objectMapper.writeValueAsString(request.getParameterBindings()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid parameterBindings: " + e.getMessage());
            }
        }

        stepMethodRepository.save(sm);
        log.info("Attached method '{}' to step {} at order {}", method.getName(), stepId, nextOrder);
    }

    @Transactional
    public void detachFromStep(Long stepMethodId) {
        stepMethodRepository.deleteById(stepMethodId);
    }

    public List<StepMethod> getStepMethods(Long stepId) {
        return stepMethodRepository.findByStepIdOrderByExecutionOrder(stepId);
    }

    // ─── Script generation + compile + runtime check ─────────────────────────

    private String generateAndValidateScript(String name, String description,
                                             List<GenerateMethodRequest.ParameterDefinition> parameters, String previousError) {

        // Build sample params for runtime test — use obvious dummy values per type
        Map<String, String> sampleParams = buildSampleParams(parameters);

        for (int attempt = 1; attempt <= MAX_SCRIPT_RETRIES; attempt++) {
            String prompt = buildPrompt(name, description, parameters, previousError);
            String llmResponse;
            try {
                llmResponse = "groq".equals(llmProvider) ? callGroq(prompt) : callOllama(prompt);
            } catch (Exception e) {
                throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
            }

            String script = extractScript(llmResponse);
            log.info("Attempt {}/{} — generated script ({} chars)", attempt, MAX_SCRIPT_RETRIES, script.length());

            // Step 1: compile check
            String compilationError = compileCheck(script);
            if (compilationError != null) {
                log.warn("Compilation failed on attempt {}: {}", attempt, compilationError);
                previousError = "COMPILATION ERROR: " + compilationError;
                continue;
            }

            // Step 2: runtime test with sample params
            String runtimeError = runtimeCheck(script, sampleParams);
            if (runtimeError != null) {
                log.warn("Runtime test failed on attempt {}: {}", attempt, runtimeError);
                previousError = "RUNTIME ERROR with sample params " + sampleParams + ": " + runtimeError;
                continue;
            }

            log.info("Script passed compile + runtime check on attempt {}", attempt);
            return script;
        }

        throw new RuntimeException(
                "LLM could not generate a valid Groovy script after " + MAX_SCRIPT_RETRIES +
                        " attempts. Try rephrasing the description or edit the script manually after generation.");
    }

    /**
     * Compile-checks without executing.
     * @return null if valid, error string if invalid
     */
    private String compileCheck(String script) {
        try {
            new groovy.lang.GroovyShell().parse(script);
            return null;
        } catch (org.codehaus.groovy.control.CompilationFailedException e) {
            String msg = e.getMessage();
            int newline = msg.indexOf('\n');
            return newline > 0 ? msg.substring(0, newline).trim() : msg.trim();
        }
    }

    /**
     * Actually executes the script with sample params to catch runtime errors.
     * @return null if execution succeeded, error string if it failed
     */
    private String runtimeCheck(String script, Map<String, String> sampleParams) {
        try {
            methodExecutorService.runGroovy(script, sampleParams);
            return null;
        } catch (Exception e) {
            // Strip Groovy stack noise — keep just the useful part
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            int causeIdx = msg.indexOf("Caused by:");
            if (causeIdx > 0) msg = msg.substring(causeIdx);
            return msg.length() > 300 ? msg.substring(0, 300) : msg;
        }
    }

    /**
     * Builds obvious sample values per parameter type for runtime testing.
     */
    private Map<String, String> buildSampleParams(List<GenerateMethodRequest.ParameterDefinition> parameters) {
        Map<String, String> sample = new LinkedHashMap<>();
        if (parameters == null) return sample;
        for (var p : parameters) {
            String val = switch (p.getType() != null ? p.getType().toLowerCase() : "string") {
                case "number", "integer", "int" -> "5";
                case "boolean"                  -> "true";
                case "list"                     -> "a,b,c";
                default                         -> "test_" + p.getName(); // string
            };
            sample.put(p.getName(), val);
        }
        return sample;
    }

    // ─── Prompt ───────────────────────────────────────────────────────────────

    private String buildPrompt(String name, String description,
                               List<GenerateMethodRequest.ParameterDefinition> parameters, String previousError) {

        StringBuilder paramsBlock = new StringBuilder();
        if (parameters != null && !parameters.isEmpty()) {
            for (var p : parameters) {
                paramsBlock.append("  - ").append(p.getName())
                        .append(" (").append(p.getType()).append(")")
                        .append(p.isRequired() ? " [required]" : " [optional]")
                        .append(": ").append(p.getDescription()).append("\n");
            }
        } else {
            paramsBlock.append("  (none)\n");
        }

        String errorSection = previousError != null
                ? "\nPREVIOUS ATTEMPT FAILED — fix this error:\n" + previousError + "\n"
                : "";

        return """
            You are a Groovy script generator. Generate a valid, executable Groovy script.
            %s
            METHOD NAME: %s
            DESCRIPTION: %s

            PARAMETERS (available in the script as a Map<String, String> called `params`):
            %s

            CRITICAL — how to access params (this is the only correct way):
              def value = params.get("paramName")
              def withDefault = params.get("paramName") ?: "defaultValue"

            DO NOT use params["paramName"] — it does NOT work. Always use params.get("paramName").

            RETURN VALUE — script must return one of:
              - A single value (String, number) → becomes {method.result}
              - A Map of String keys → each key becomes {method.keyName}

            RULES:
            1. Only use params.get("name") to access parameters — never params["name"]
            2. Always handle null params with ?: fallback
            3. No class definitions, no @annotations, no package statements
            4. Imports allowed but only java.* or groovy.*
            5. No println or print statements

            EXAMPLE — random pick from comma-separated list:
            def idList = params.get("idList") ?: ""
            def ids = idList.split(",")
            return ids[new Random().nextInt(ids.length)].trim()

            EXAMPLE — map return:
            def length = Integer.parseInt(params.get("length") ?: "16")
            def chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            def token = (1..length).collect { chars[new Random().nextInt(chars.size())] }.join()
            return ["token": token, "length": String.valueOf(length)]

            Return ONLY the raw Groovy script. No markdown, no ```, no explanation.
            """.formatted(errorSection, name, description, paramsBlock.toString());
    }

    private String extractScript(String llmResponse) {
        String cleaned = llmResponse
                .replaceAll("(?s)```groovy\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
        if (cleaned.isBlank()) throw new RuntimeException("LLM returned empty script");
        return cleaned;
    }

    // ─── LLM calls ────────────────────────────────────────────────────────────

    private String callOllama(String prompt) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("model", ollamaModel, "prompt", prompt, "stream", false));
        Request req = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Ollama error: HTTP " + response.code());
            return objectMapper.readTree(response.body().string()).path("response").asText();
        }
    }

    private String callGroq(String prompt) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "model", groqModel,
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "max_tokens", 1024,
                "temperature", 0.1
        ));
        Request req = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(RequestBody.create(body, JSON))
                .addHeader("Authorization", "Bearer " + groqApiKey)
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Groq error: HTTP " + response.code());
            return objectMapper.readTree(response.body().string())
                    .path("choices").get(0).path("message").path("content").asText();
        }
    }

    // ─── Mappers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private CustomMethodDTO toDTO(CustomMethod m) {
        CustomMethodDTO dto = new CustomMethodDTO();
        dto.setId(m.getId());
        dto.setName(m.getName());
        dto.setDescription(m.getDescription());
        dto.setType(m.getType());
        dto.setBuiltinType(m.getBuiltinType());
        dto.setGlobal(m.isGlobal());
        dto.setCreatedAt(m.getCreatedAt());
        if (m.getType() == MethodType.USER_DEFINED) {
            dto.setGroovyScript(m.getGroovyScript());
        }
        if (m.getParameterDefinitionsJson() != null) {
            try {
                List<CustomMethodDTO.ParameterDefinitionDTO> params = objectMapper.readValue(
                        m.getParameterDefinitionsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(
                                List.class, CustomMethodDTO.ParameterDefinitionDTO.class));
                dto.setParameters(params);
            } catch (Exception e) {
                log.warn("Could not deserialize parameter definitions for method {}", m.getId());
            }
        }
        return dto;
    }

    private CustomMethod findById(Long id) {
        return customMethodRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Method not found: " + id));
    }
}