package com.example.flowengine.controller;

import com.example.flowengine.DTO.*;
import com.example.flowengine.service.AppAssistantService;
import com.example.flowengine.service.CollectionImportService;
import com.example.flowengine.service.HarImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
@Tag(name = "Application Assistant", description = "App-scoped chatbot for building and managing Flow Engine resources")
public class AssistantController {

    private final AppAssistantService assistantService;
    private final CollectionImportService collectionImportService;
    private final HarImportService harImportService;

    @PostMapping("/chat")
    @Operation(summary = "Chat with Flow Engine assistant")
    public AssistantChatResponse chat(@Valid @RequestBody AssistantChatRequest request) {
        return assistantService.chat(request);
    }

    @GetMapping("/tools")
    @Operation(summary = "List assistant tools")
    public Map<String, Object> tools() {
        return assistantService.toolCatalog();
    }

    /**
     * Single upload endpoint for the chat UI.
     * Auto-detects file type from extension/content-type and routes to the correct importer.
     * Returns the created FlowDetailedDTO so the UI can navigate straight to the new flow.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Upload a file via the assistant chat UI",
            description = """
                    Auto-detects the file type and imports it as a flow:
                    - .json with "info._postman_id"  → Postman collection import
                    - .json/.yaml/.yml with "openapi" or "swagger" field → Swagger/OpenAPI import
                    - .har → HAR recording import
                    
                    Required fields: file, moduleId, flowName
                    Optional: filterDomain (HAR only), flowId (HAR only — append to existing flow)
                    """
    )
    public FlowDetailedDTO upload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("moduleId") String moduleId,
            @RequestPart("flowName") String flowName,
            @RequestPart(value = "flowId", required = false) String flowId,
            @RequestPart(value = "filterDomain", required = false) String filterDomain
    ) throws Exception {

        String filename = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";
        String contentType = file.getContentType() != null
                ? file.getContentType().toLowerCase() : "";

        log.info("[Assistant/upload] file={} size={} moduleId={} flowName={}",
                filename, file.getSize(), moduleId, flowName);

        // Detect type from extension first, then peek at content
        ImportType type = detectImportType(filename, contentType, file.getBytes());

        return switch (type) {
            case POSTMAN -> {
                CollectionImportRequest req = new CollectionImportRequest();
                req.setModuleId(Long.parseLong(moduleId));
                req.setFlowName(flowName);
                yield collectionImportService.importCollection(file, req);
            }
            case SWAGGER -> {
                CollectionImportRequest req = new CollectionImportRequest();
                req.setModuleId(Long.parseLong(moduleId));
                req.setFlowName(flowName);
                yield collectionImportService.importSwagger(file, req);
            }
            case HAR -> {
                HarImportRequest req = new HarImportRequest();
                req.setModuleId(Long.parseLong(moduleId));
                req.setFlowName(flowName);
                if (flowId != null && !flowId.isBlank()) req.setFlowId(Long.parseLong(flowId));
                if (filterDomain != null && !filterDomain.isBlank()) req.setFilterDomain(filterDomain);
                req.setIncludeResponseAsLastResponse(true);
                yield harImportService.importHar(file, req);
            }
        };
    }

    private enum ImportType { POSTMAN, SWAGGER, HAR }

    private ImportType detectImportType(String filename, String contentType, byte[] content) {
        // HAR — unambiguous extension
        if (filename.endsWith(".har")) return ImportType.HAR;

        // Peek at content as string (first 2KB is enough)
        String snippet = new String(content, 0, Math.min(content.length, 2048))
                .replaceAll("\\s+", " ");

        if (snippet.contains("\"_postman_id\"") || snippet.contains("\"info\"") && snippet.contains("\"schema\"") && snippet.contains("postman"))
            return ImportType.POSTMAN;

        if (snippet.contains("\"openapi\"") || snippet.contains("\"swagger\"") ||
                snippet.contains("openapi:") || snippet.contains("swagger:"))
            return ImportType.SWAGGER;

        // YAML swagger/openapi
        if (filename.endsWith(".yaml") || filename.endsWith(".yml"))
            return ImportType.SWAGGER;

        // Default JSON to Postman (most common case)
        if (filename.endsWith(".json")) return ImportType.POSTMAN;

        throw new IllegalArgumentException(
                "Cannot detect file type for '" + filename + "'. " +
                        "Expected a .har, .json (Postman/OpenAPI), or .yaml/.yml (OpenAPI) file.");
    }
}