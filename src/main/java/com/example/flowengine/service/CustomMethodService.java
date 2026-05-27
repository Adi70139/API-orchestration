package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.example.flowengine.constants.MethodType;
import com.example.flowengine.entity.CustomMethod;
import com.example.flowengine.entity.StepMethod;
import com.example.flowengine.repository.CustomMethodRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.example.flowengine.repository.StepMethodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.GroovyShell;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.codehaus.groovy.control.CompilationFailedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

        StepMethod sm = new StepMethod();
        sm.setStep(flowStepRepository.getReferenceById(stepId));
        sm.setMethod(method);
        sm.setExecutionOrder(request.getExecutionOrder() != null ? request.getExecutionOrder() : 1);

        if (request.getParameterBindings() != null) {
            try {
                sm.setParameterBindingsJson(objectMapper.writeValueAsString(request.getParameterBindings()));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid parameterBindings: " + e.getMessage());
            }
        }

        stepMethodRepository.save(sm);
        log.info("Attached method '{}' to step {} at order {}", method.getName(), stepId, sm.getExecutionOrder());
    }

    @Transactional
    public void detachFromStep(Long stepMethodId) {
        stepMethodRepository.deleteById(stepMethodId);
    }

    public List<StepMethod> getStepMethods(Long stepId) {
        return stepMethodRepository.findByStepIdOrderByExecutionOrder(stepId);
    }

    // ─── Script generation + compile-check ───────────────────────────────────

    private String generateAndValidateScript(String name, String description,
                                             List<GenerateMethodRequest.ParameterDefinition> parameters, String previousError) {
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

            String compilationError = compileCheck(script);
            if (compilationError == null) {
                log.info("Script compiled successfully on attempt {}", attempt);
                return script;
            }

            log.warn("Script compilation failed on attempt {}: {}", attempt, compilationError);
            previousError = compilationError; // feed back into next prompt
        }

        throw new RuntimeException(
                "LLM could not generate a valid Groovy script after " + MAX_SCRIPT_RETRIES +
                        " attempts. Try rephrasing the description or editing the script manually after generation.");
    }

    /**
     * Compile-checks a Groovy script without executing it.
     * @return null if valid, error message if invalid
     */
    private String compileCheck(String script) {
        try {
            new GroovyShell().parse(script);
            return null;
        } catch (CompilationFailedException e) {
            // Return a concise error — strip internal groovy stack details
            String msg = e.getMessage();
            int newline = msg.indexOf('\n');
            return newline > 0 ? msg.substring(0, newline).trim() : msg.trim();
        }
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
                ? "\nPREVIOUS ATTEMPT FAILED WITH THIS COMPILATION ERROR — fix it:\n" + previousError + "\n"
                : "";

        return """
            You are a Groovy script generator. Generate a valid, compilable Groovy script.
            %s
            METHOD NAME: %s
            DESCRIPTION: %s

            PARAMETERS (access via params["name"]):
            %s

            STRICT RULES — follow exactly or the script will fail:
            1. Access params like this: params["paramName"] or params.get("paramName")
            2. For default values use: def x = params["name"] ?: "default"
            3. Return EITHER a Map (named outputs) OR a single value (becomes {method.result})
            4. No class definitions, no @annotations, no package statements
            5. Use only standard Groovy/Java — no external libraries
            6. Imports are allowed but only java.* or groovy.* packages

            CORRECT EXAMPLES:

            // Single value return:
            def ids = params["idList"].split(",")
            return ids[new Random().nextInt(ids.length)].trim()

            // Map return:
            def length = (params["length"] ?: "16").toInteger()
            def chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
            def token = (1..length).collect { chars[new Random().nextInt(chars.size())] }.join()
            return ["token": token, "length": length.toString()]

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