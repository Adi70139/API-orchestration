package com.example.flowengine.service;

import com.example.flowengine.DTO.CollectionImportRequest;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.example.flowengine.repository.ModuleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionImportService {

    private final ModuleRepository moduleRepository;
    private final FlowRepository flowRepository;
    private final FlowStepRepository flowStepRepository;
    private final ObjectMapper objectMapper;

    public FlowDetailedDTO importCollection(MultipartFile file,
                                            CollectionImportRequest request) throws Exception {
        // Parse uploaded JSON
        JsonNode root = objectMapper.readTree(file.getInputStream());

        // Support both Postman v2.0 and v2.1
        JsonNode info = root.path("info");
        String collectionName = info.path("name").asText("Imported Collection");

        JsonNode items = root.path("item");
        if (items.isMissingNode() || !items.isArray()) {
            throw new IllegalArgumentException(
                    "Invalid Postman collection — no 'item' array found");
        }

        // Resolve module
        ModuleEntity module = moduleRepository.findById(request.getModuleId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Module not found: " + request.getModuleId()));

        // Create flow
        FlowDefinition flow = new FlowDefinition();
        flow.setName(request.getFlowName());
        flow.setDescription("Imported from Postman collection: " + collectionName);
        flow.setModule(module);
        flow = flowRepository.save(flow);

        // Parse and create steps
        List<FlowStep> steps = new ArrayList<>();
        int stepOrder = 1;

        for (JsonNode item : items) {
            if (!item.has("request")) {
                log.warn("Skipping item '{}' — no request found", item.path("name").asText());
                continue;
            }

            try {
                FlowStep step = parseStep(item, flow, stepOrder);
                steps.add(flowStepRepository.save(step));
                stepOrder++;
            } catch (IllegalArgumentException e) {
                // Skip steps with missing/invalid URLs — log and continue
                log.warn("Skipping step '{}' — {}", item.path("name").asText(), e.getMessage());
            }
        }

        log.info("Imported {} steps into flow '{}' in module '{}'",
                steps.size(), flow.getName(), module.getName());

        return buildResponse(flow, steps);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private FlowStep parseStep(JsonNode item, FlowDefinition flow, int stepOrder) throws Exception {
        String stepName = item.path("name").asText("Step " + stepOrder);
        JsonNode requestNode = item.path("request");

        String method = requestNode.path("method").asText("GET").toUpperCase();

        // Log the raw URL node to see what we're getting
        JsonNode urlNode = requestNode.path("url");
        log.info("Parsing step '{}' — URL node: {}", stepName, urlNode.toString());

        String url = extractUrl(urlNode);

        // Fail clearly if URL is empty
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "Could not extract URL for step '" + stepName + "'. " +
                            "Raw URL node: " + urlNode.toString()
            );
        }

        String headersJson = extractHeaders(requestNode.path("header"));
        String bodyJson = extractBody(requestNode.path("body"));

        FlowStep step = new FlowStep();
        step.setFlow(flow);
        step.setName(stepName);
        step.setStepOrder(stepOrder);
        step.setMethod(method);
        step.setUrl(url);
        step.setHeadersJson(headersJson);
        step.setBodyJson(bodyJson);

        return step;
    }

    private String extractUrl(JsonNode urlNode) {
        if (urlNode.isMissingNode() || urlNode.isNull()
                || (urlNode.isTextual() && urlNode.asText().isBlank())) {
            return null;
        }

        String raw = null;

        if (urlNode.isTextual()) {
            raw = urlNode.asText();
        } else if (urlNode.isObject()) {
            raw = urlNode.path("raw").asText("");
            if (raw.isBlank()) {
                // Build from parts
                // ... existing build-from-parts logic ...
            }
        }

        if (raw == null || raw.isBlank()) return null;

        // Strip cURL artifacts — anything after whitespace + backslash or newline
        raw = raw.replaceAll("\\s*\\\\?\\s*\\n.*", "").trim();

        // Strip trailing junk like %27 \\ --header etc.
        raw = raw.replaceAll("\\s+--.*$", "").trim();
        raw = raw.replaceAll("%27\\s.*$", "").trim();

        return convertVariables(raw);
    }

    private String extractHeaders(JsonNode headerNode) throws Exception {
        if (headerNode.isMissingNode() || !headerNode.isArray() || headerNode.size() == 0) {
            return null;
        }

        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (JsonNode header : headerNode) {
            if (header.path("disabled").asBoolean(false)) continue;
            String key = header.path("key").asText();
            String value = convertVariables(header.path("value").asText());
            if (!key.isBlank()) {
                headers.put(key, value);
            }
        }

        return headers.isEmpty() ? null : objectMapper.writeValueAsString(headers);
    }

    private String extractBody(JsonNode bodyNode) throws Exception {
        if (bodyNode.isMissingNode() || bodyNode.isNull()) return null;

        String mode = bodyNode.path("mode").asText("");

        return switch (mode) {
            case "raw" -> {
                String raw = bodyNode.path("raw").asText("");
                yield raw.isBlank() ? null : convertVariables(raw);
            }
            case "urlencoded" -> {
                JsonNode urlencoded = bodyNode.path("urlencoded");
                if (!urlencoded.isArray() || urlencoded.size() == 0) yield null;
                java.util.Map<String, String> formData = new java.util.LinkedHashMap<>();
                for (JsonNode param : urlencoded) {
                    if (!param.path("disabled").asBoolean(false)) {
                        formData.put(
                                param.path("key").asText(),
                                convertVariables(param.path("value").asText())
                        );
                    }
                }
                yield formData.isEmpty() ? null : objectMapper.writeValueAsString(formData);
            }
            case "formdata" -> {
                JsonNode formdata = bodyNode.path("formdata");
                if (!formdata.isArray() || formdata.size() == 0) yield null;
                java.util.Map<String, String> formMap = new java.util.LinkedHashMap<>();
                for (JsonNode param : formdata) {
                    if (!param.path("disabled").asBoolean(false)) {
                        formMap.put(
                                param.path("key").asText(),
                                convertVariables(param.path("value").asText())
                        );
                    }
                }
                yield formMap.isEmpty() ? null : objectMapper.writeValueAsString(formMap);
            }
            default -> null;
        };
    }

    /**
     * Convert Postman {{variable}} syntax to our {variable} syntax.
     */
    private String convertVariables(String input) {
        if (input == null) return null;
        return input.replaceAll("\\{\\{([^}]+)}}", "{$1}");
    }

    // ── Response ──────────────────────────────────────────────────────────────

    private FlowDetailedDTO buildResponse(FlowDefinition flow, List<FlowStep> steps) {
        FlowDetailedDTO dto = new FlowDetailedDTO();
        dto.setId(flow.getId());
        dto.setName(flow.getName());
        dto.setDescription(flow.getDescription());
        dto.setModuleId(flow.getModule().getId());
        dto.setModuleName(flow.getModule().getName());

        dto.setSteps(steps.stream().map(step -> {
            FlowDetailedDTO.FlowStepDetailDTO stepDTO = new FlowDetailedDTO.FlowStepDetailDTO();
            stepDTO.setId(step.getId());
            stepDTO.setName(step.getName());
            stepDTO.setStepOrder(step.getStepOrder());
            stepDTO.setMethod(step.getMethod());
            stepDTO.setUrl(step.getUrl());
            stepDTO.setHeadersJson(step.getHeadersJson());
            stepDTO.setBodyJson(step.getBodyJson());
            return stepDTO;
        }).collect(java.util.stream.Collectors.toList()));

        return dto;
    }
}