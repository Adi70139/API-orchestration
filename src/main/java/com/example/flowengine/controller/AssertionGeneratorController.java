package com.example.flowengine.controller;

import com.example.flowengine.DTO.GenerateAssertionsRequest;
import com.example.flowengine.DTO.GenerateAssertionsResponse;
import com.example.flowengine.service.AssertionGeneratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/assertions")
@RequiredArgsConstructor
@Tag(name = "Assertions", description = "AI-powered assertion generation")
public class AssertionGeneratorController {

    private final AssertionGeneratorService assertionGeneratorService;

    @PostMapping("/generate")
    @Operation(
            summary = "Generate assertions from natural language",
            description = "Send a response body and plain English description — returns structured assertions ready to save on a step"
    )
    public GenerateAssertionsResponse generate(
            @Valid @RequestBody GenerateAssertionsRequest request) {
        return assertionGeneratorService.generateAssertions(request);
    }
}