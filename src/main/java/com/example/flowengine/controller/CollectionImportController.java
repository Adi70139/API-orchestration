package com.example.flowengine.controller;

import com.example.flowengine.DTO.CollectionImportRequest;
import com.example.flowengine.DTO.FlowDetailedDTO;
import com.example.flowengine.service.CollectionImportService;
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
@Tag(name = "Import", description = "Import Postman collections as flows")
public class CollectionImportController {

    private final CollectionImportService collectionImportService;

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
}