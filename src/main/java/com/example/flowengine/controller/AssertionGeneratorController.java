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

    /**
     * Schema button — deterministically generates type assertions for ALL fields
     * in the step's last response. Merges into existing assertions, never wipes body assertions.
     * POST /assertions/schema/{stepId}
     */
    @PostMapping("/schema/{stepId}")
    @Operation(
            summary = "Generate schema assertions",
            description = "Extracts data type assertions for every field in the step's last response. " +
                    "Merges into existing assertions — existing body/field assertions are untouched."
    )
    public GenerateAssertionsResponse generateSchema(@PathVariable Long stepId) {
        return assertionGeneratorService.generateSchema(stepId);
    }

    /**
     * Field-level assertion via natural language — LLM merges into existing assertions.
     * POST /assertions/generate
     */
    @PostMapping("/generate")
    @Operation(
            summary = "Generate field assertions from natural language",
            description = "Describe what to assert in plain English. LLM merges into existing assertions — " +
                    "same field path replaces, new paths are added, everything else preserved."
    )
    public GenerateAssertionsResponse generate(@Valid @RequestBody GenerateAssertionsRequest request) {
        return assertionGeneratorService.generateAssertions(request);
    }

    /**
     * Get current cumulative assertions for a step (what's been built up so far).
     * GET /assertions/{stepId}
     */
    @GetMapping("/{stepId}")
    @Operation(
            summary = "Get current assertions",
            description = "Returns the current cumulative assertions JSON for a step. " +
                    "This is what's been built up via schema + field generation calls."
    )
    public GenerateAssertionsResponse getCurrent(@PathVariable Long stepId) {
        return assertionGeneratorService.getCurrentAssertions(stepId);
    }
}