package com.example.flowengine.controller;

import com.example.flowengine.DTO.GenerateSkipConditionRequest;
import com.example.flowengine.DTO.GenerateSkipConditionResponse;
import com.example.flowengine.service.SkipConditionGeneratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/skip-condition")
@RequiredArgsConstructor
@Tag(name = "Skip Condition", description = "AI-powered skip condition generation")
public class SkipConditionGeneratorController {

    private final SkipConditionGeneratorService skipConditionGeneratorService;

    @PostMapping("/generate")
    @Operation(
            summary = "Generate skip condition from natural language",
            description = "Send one or more previous step response bodies and a plain English description of when to skip — returns a structured skipCondition ready to save on a step"
    )
    public GenerateSkipConditionResponse generate(
            @Valid @RequestBody GenerateSkipConditionRequest request) {
        return skipConditionGeneratorService.generate(request);
    }
}