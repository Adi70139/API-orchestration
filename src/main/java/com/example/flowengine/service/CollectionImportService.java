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
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    public FlowDetailedDTO importSwagger(MultipartFile file,
                                         CollectionImportRequest request) throws Exception {
        JsonNode root = readSpec(file);
        JsonNode paths = root.path("paths");
        if (paths.isMissingNode() || !paths.isObject() || paths.isEmpty()) {
            throw new IllegalArgumentException("Invalid Swagger/OpenAPI spec — no 'paths' object found");
        }

        String specName = root.path("info").path("title").asText("Imported Swagger/OpenAPI Spec");
        ModuleEntity module = moduleRepository.findById(request.getModuleId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Module not found: " + request.getModuleId()));

        FlowDefinition flow = new FlowDefinition();
        flow.setName(request.getFlowName());
        flow.setDescription("Imported from Swagger/OpenAPI spec: " + specName);
        flow.setModule(module);
        flow = flowRepository.save(flow);

        List<FlowStep> steps = new ArrayList<>();
        int stepOrder = 1;
        String baseUrl = extractBaseUrl(root);

        Iterator<Map.Entry<String, JsonNode>> pathIterator = paths.fields();
        while (pathIterator.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIterator.next();
            String path = pathEntry.getKey();
            JsonNode pathNode = pathEntry.getValue();

            for (String method : List.of("get", "post", "put", "patch", "delete", "head", "options")) {
                JsonNode operationNode = pathNode.path(method);
                if (operationNode.isMissingNode() || !operationNode.isObject()) {
                    continue;
                }

                FlowStep step = parseSwaggerStep(root, baseUrl, path, method, pathNode, operationNode, flow, stepOrder);
                steps.add(flowStepRepository.save(step));
                stepOrder++;
            }
        }

        if (steps.isEmpty()) {
            throw new IllegalArgumentException("No API operations found in Swagger/OpenAPI spec");
        }

        log.info("Imported {} Swagger/OpenAPI operations into flow '{}' in module '{}'",
                steps.size(), flow.getName(), module.getName());

        return buildResponse(flow, steps);
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private JsonNode readSpec(MultipartFile file) throws Exception {
        try {
            return objectMapper.readTree(file.getInputStream());
        } catch (Exception jsonException) {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            return yamlMapper.readTree(file.getInputStream());
        }
    }

    private FlowStep parseSwaggerStep(JsonNode root,
                                      String baseUrl,
                                      String path,
                                      String method,
                                      JsonNode pathNode,
                                      JsonNode operationNode,
                                      FlowDefinition flow,
                                      int stepOrder) throws Exception {
        FlowStep step = new FlowStep();
        step.setFlow(flow);
        step.setStepOrder(stepOrder);
        step.setMethod(method.toUpperCase());
        step.setName(extractOperationName(operationNode, method, path));
        step.setDescription(operationNode.path("description").asText(operationNode.path("summary").asText(null)));
        step.setUrl(buildSwaggerUrl(baseUrl, path, pathNode, operationNode));
        step.setHeadersJson(extractSwaggerHeaders(pathNode, operationNode));
        step.setBodyJson(extractSwaggerBody(root, operationNode));
        return step;
    }

    private String extractOperationName(JsonNode operationNode, String method, String path) {
        String summary = operationNode.path("summary").asText("");
        if (!summary.isBlank()) {
            return summary;
        }
        String operationId = operationNode.path("operationId").asText("");
        if (!operationId.isBlank()) {
            return operationId;
        }
        return method.toUpperCase() + " " + path;
    }

    private String extractBaseUrl(JsonNode root) {
        JsonNode servers = root.path("servers");
        if (servers.isArray() && !servers.isEmpty()) {
            String serverUrl = servers.get(0).path("url").asText("");
            if (!serverUrl.isBlank()) {
                return convertVariables(serverUrl);
            }
        }

        String host = root.path("host").asText("");
        if (host.isBlank()) {
            return "";
        }
        String scheme = "https";
        JsonNode schemes = root.path("schemes");
        if (schemes.isArray() && !schemes.isEmpty()) {
            scheme = schemes.get(0).asText("https");
        }
        String basePath = root.path("basePath").asText("");
        return scheme + "://" + host + basePath;
    }

    private String buildSwaggerUrl(String baseUrl, String path, JsonNode pathNode, JsonNode operationNode) {
        String url = combineUrl(baseUrl, path);
        List<String> queryParams = new ArrayList<>();
        collectQueryParameters(queryParams, pathNode.path("parameters"));
        collectQueryParameters(queryParams, operationNode.path("parameters"));
        if (!queryParams.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + String.join("&", queryParams);
        }
        return convertVariables(url);
    }

    private void collectQueryParameters(List<String> queryParams, JsonNode parameters) {
        if (!parameters.isArray()) {
            return;
        }
        for (JsonNode parameter : parameters) {
            if ("query".equals(parameter.path("in").asText())) {
                String name = parameter.path("name").asText("");
                if (!name.isBlank()) {
                    queryParams.add(name + "={" + name + "}");
                }
            }
        }
    }

    private String combineUrl(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return path;
        }
        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }
        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }
        return baseUrl + path;
    }

    private String extractSwaggerHeaders(JsonNode pathNode, JsonNode operationNode) throws Exception {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        collectHeaderParameters(headers, pathNode.path("parameters"));
        collectHeaderParameters(headers, operationNode.path("parameters"));

        JsonNode content = operationNode.path("requestBody").path("content");
        if (content.isObject() && !content.isEmpty()) {
            String contentType = content.has("application/json")
                    ? "application/json"
                    : content.fieldNames().next();
            headers.putIfAbsent("Content-Type", contentType);
        }

        return headers.isEmpty() ? null : objectMapper.writeValueAsString(headers);
    }

    private void collectHeaderParameters(Map<String, String> headers, JsonNode parameters) {
        if (!parameters.isArray()) {
            return;
        }
        for (JsonNode parameter : parameters) {
            if ("header".equals(parameter.path("in").asText())) {
                String name = parameter.path("name").asText("");
                if (!name.isBlank()) {
                    headers.put(name, "{" + name + "}");
                }
            }
        }
    }

    private String extractSwaggerBody(JsonNode root, JsonNode operationNode) throws Exception {
        JsonNode requestBody = operationNode.path("requestBody");
        JsonNode content = requestBody.path("content");
        if (content.isObject() && !content.isEmpty()) {
            JsonNode mediaTypeNode = content.has("application/json")
                    ? content.path("application/json")
                    : content.elements().next();
            JsonNode example = mediaTypeNode.path("example");
            if (!example.isMissingNode()) {
                return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(example);
            }
            JsonNode examples = mediaTypeNode.path("examples");
            if (examples.isObject() && examples.fields().hasNext()) {
                JsonNode firstExample = examples.fields().next().getValue().path("value");
                if (!firstExample.isMissingNode()) {
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(firstExample);
                }
            }
            JsonNode sample = sampleFromSchema(root, mediaTypeNode.path("schema"));
            return sample == null ? null : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample);
        }

        JsonNode parameters = operationNode.path("parameters");
        if (parameters.isArray()) {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            for (JsonNode parameter : parameters) {
                if ("body".equals(parameter.path("in").asText())) {
                    JsonNode sample = sampleFromSchema(root, parameter.path("schema"));
                    return sample == null ? null : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sample);
                }
                if ("formData".equals(parameter.path("in").asText())) {
                    String name = parameter.path("name").asText("");
                    if (!name.isBlank()) {
                        body.put(name, sampleValue(parameter));
                    }
                }
            }
            return body.isEmpty() ? null : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        }

        return null;
    }

    private JsonNode sampleFromSchema(JsonNode root, JsonNode schema) {
        if (schema.isMissingNode() || schema.isNull()) {
            return null;
        }
        if (schema.has("$ref")) {
            JsonNode resolved = resolveLocalRef(root, schema.path("$ref").asText());
            return sampleFromSchema(root, resolved);
        }
        if (schema.has("allOf")) {
            com.fasterxml.jackson.databind.node.ObjectNode merged = objectMapper.createObjectNode();
            for (JsonNode item : schema.path("allOf")) {
                JsonNode sample = sampleFromSchema(root, item);
                if (sample != null && sample.isObject()) {
                    merged.setAll((com.fasterxml.jackson.databind.node.ObjectNode) sample);
                }
            }
            return merged;
        }
        if (schema.has("oneOf") && schema.path("oneOf").isArray() && !schema.path("oneOf").isEmpty()) {
            return sampleFromSchema(root, schema.path("oneOf").get(0));
        }
        if (schema.has("anyOf") && schema.path("anyOf").isArray() && !schema.path("anyOf").isEmpty()) {
            return sampleFromSchema(root, schema.path("anyOf").get(0));
        }
        if (schema.has("example")) {
            return schema.path("example");
        }
        if (schema.has("default")) {
            return schema.path("default");
        }
        String type = schema.path("type").asText("");
        if (type.isBlank() && schema.has("properties")) {
            type = "object";
        }

        return switch (type) {
            case "object" -> {
                com.fasterxml.jackson.databind.node.ObjectNode objectNode = objectMapper.createObjectNode();
                JsonNode properties = schema.path("properties");
                if (properties.isObject()) {
                    Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        JsonNode value = sampleFromSchema(root, field.getValue());
                        if (value != null) {
                            objectNode.set(field.getKey(), value);
                        }
                    }
                }
                yield objectNode;
            }
            case "array" -> {
                com.fasterxml.jackson.databind.node.ArrayNode arrayNode = objectMapper.createArrayNode();
                JsonNode item = sampleFromSchema(root, schema.path("items"));
                if (item != null) {
                    arrayNode.add(item);
                }
                yield arrayNode;
            }
            case "integer" -> objectMapper.getNodeFactory().numberNode(0);
            case "number" -> objectMapper.getNodeFactory().numberNode(0.0);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(false);
            default -> objectMapper.getNodeFactory().textNode("");
        };
    }

    private JsonNode resolveLocalRef(JsonNode root, String ref) {
        if (ref == null || !ref.startsWith("#/")) {
            return objectMapper.missingNode();
        }
        return root.at(ref.substring(1));
    }

    private Object sampleValue(JsonNode parameter) {
        if (parameter.has("example")) {
            return parameter.path("example");
        }
        return switch (parameter.path("type").asText("string")) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            default -> "";
        };
    }

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
