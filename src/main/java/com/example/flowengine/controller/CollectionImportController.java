package com.example.flowengine.controller;

import com.example.flowengine.DTO.CollectionImportRequest;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.DTO.HarImportRequest;
import com.example.flowengine.service.CollectionImportService;
import com.example.flowengine.service.HarImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/import")
@RequiredArgsConstructor
@Tag(name = "Import", description = "Import Postman collections, Swagger/OpenAPI specs and HAR recordings as flows")
public class CollectionImportController {

    private final CollectionImportService collectionImportService;
    private final HarImportService harImportService;

    @PostMapping(value = "/postman", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Import Postman Collection",
            description = "Upload a Postman collection JSON file to create a flow with steps. " +
                    "Postman {{variables}} are automatically converted to {variables}."
    )
    public FlowDetailedDTO importPostmanCollection(
            @RequestPart("file") MultipartFile file,
            @RequestPart("moduleId") String moduleId,
            @RequestPart("flowName") String flowName) throws Exception {

        CollectionImportRequest request = new CollectionImportRequest();
        request.setModuleId(Long.parseLong(moduleId));
        request.setFlowName(flowName);

        return collectionImportService.importCollection(file, request);
    }

    @PostMapping(value = "/swagger", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Import Swagger/OpenAPI Spec",
            description = "Upload a Swagger/OpenAPI JSON or YAML file to create a flow with one step per API operation."
    )
    public FlowDetailedDTO importSwagger(
            @RequestPart("file") MultipartFile file,
            @RequestPart("moduleId") String moduleId,
            @RequestPart("flowName") String flowName) throws Exception {

        CollectionImportRequest request = new CollectionImportRequest();
        request.setModuleId(Long.parseLong(moduleId));
        request.setFlowName(flowName);

        return collectionImportService.importSwagger(file, request);
    }

    @PostMapping(value = "/har", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Import HAR recording",
            description = """
                    Upload a HAR (HTTP Archive) file exported from browser DevTools Network tab.
                    Only XHR/Fetch API calls are imported — static assets, HTML, CSS, JS are filtered out.
                    Each request becomes a flow step with URL, method, headers and body pre-filled.
                    Response bodies are stored as lastResponseBody so assertions and skip conditions work immediately.

                    How to export HAR:
                    Chrome/Edge: DevTools → Network tab → right-click any request → Save all as HAR with content
                    Firefox: DevTools → Network tab → gear icon → Save All As HAR
                    Safari: DevTools → Network tab → Export button
                    """
    )
    public FlowDetailedDTO importHar(
            @RequestPart("file") MultipartFile file,
            @RequestPart("moduleId") String moduleId,
            @RequestPart(value = "flowId", required = false) String flowId,
            @RequestPart(value = "flowName", required = false) String flowName,
            @RequestPart(value = "filterDomain", required = false) String filterDomain,
            @RequestPart(value = "includeResponseAsLastResponse", required = false) String includeResponse
    ) throws Exception {
        HarImportRequest request = new HarImportRequest();
        request.setModuleId(Long.parseLong(moduleId));
        if (flowId != null && !flowId.isBlank()) request.setFlowId(Long.parseLong(flowId));
        if (flowName != null && !flowName.isBlank()) request.setFlowName(flowName);
        if (filterDomain != null && !filterDomain.isBlank()) request.setFilterDomain(filterDomain);
        if (includeResponse != null) request.setIncludeResponseAsLastResponse(Boolean.parseBoolean(includeResponse));
        return harImportService.importHar(file, request);
    }
}