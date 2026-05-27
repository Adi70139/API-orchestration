package com.example.flowengine.controller;

import com.example.flowengine.DTO.*;
import com.example.flowengine.service.CustomMethodService;
import com.example.flowengine.service.EncryptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Custom Methods", description = "Pre-step methods — builtin and LLM-generated")
public class CustomMethodController {

    private final CustomMethodService customMethodService;
    private final EncryptionService encryptionService;

    // ─── Method library ───────────────────────────────────────────────────────

    @GetMapping("/methods")
    @Operation(
            summary = "List saved (global) methods",
            description = "Returns all methods available for use in any flow.")
    public List<CustomMethodDTO> listGlobal() {
        return customMethodService.listGlobal();
    }

    @GetMapping("/methods/all")
    @Operation(
            summary = "List all methods including drafts",
            description = "Returns global methods and unsaved drafts.")
    public List<CustomMethodDTO> listAll() {
        return customMethodService.listAll();
    }

    @GetMapping("/methods/{methodId}")
    @Operation(summary = "Get method detail")
    public CustomMethodDTO getById(@PathVariable Long methodId) {
        return customMethodService.getById(methodId);
    }

    // ─── LLM generation ───────────────────────────────────────────────────────

    @PostMapping("/methods/generate")
    @Operation(
            summary = "Generate a user-defined method via LLM",
            description = "Describe what the method should do. LLM generates a Groovy script, " +
                    "compile-checked before saving. Retries automatically if script is invalid. " +
                    "Saved as a draft — test it, then save or discard.")
    public CustomMethodDTO generate(@Valid @RequestBody GenerateMethodRequest request) {
        return customMethodService.generate(request);
    }

    // ─── Edit ─────────────────────────────────────────────────────────────────

    @PutMapping("/methods/{methodId}")
    @Operation(
            summary = "Edit a user-defined method",
            description = "Update name, description, parameters, or script. " +
                    "If groovyScript is provided → validated and saved directly. " +
                    "If groovyScript is null but description changed → LLM regenerates the script.")
    public CustomMethodDTO edit(
            @PathVariable Long methodId,
            @RequestBody EditMethodRequest request) {
        return customMethodService.edit(methodId, request);
    }

    // ─── Test ─────────────────────────────────────────────────────────────────

    @PostMapping("/methods/test")
    @Operation(
            summary = "Test a method with concrete parameter values",
            description = "Run a method with raw parameter values to verify it works before attaching to a step.")
    public MethodExecutionResult test(@Valid @RequestBody TestMethodRequest request) {
        return customMethodService.test(request);
    }

    // ─── Save (promote draft → global) ────────────────────────────────────────

    @PostMapping("/methods/{methodId}/save")
    @Operation(
            summary = "Save method as global",
            description = "Promotes a draft method to global — available for all flows.")
    public CustomMethodDTO save(@PathVariable Long methodId) {
        return customMethodService.save(methodId);
    }

    // ─── Discard ──────────────────────────────────────────────────────────────

    @DeleteMapping("/methods/{methodId}/discard")
    @Operation(
            summary = "Discard a draft method",
            description = "Deletes a method and removes all its step attachments. " +
                    "Use when user doesn't want the generated method.")
    public ResponseEntity<Void> discard(@PathVariable Long methodId) {
        customMethodService.discard(methodId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/methods/{methodId}")
    @Operation(summary = "Delete a method (alias for discard)")
    public ResponseEntity<Void> delete(@PathVariable Long methodId) {
        customMethodService.discard(methodId);
        return ResponseEntity.noContent().build();
    }

    // ─── DB password helper ───────────────────────────────────────────────────

    @PostMapping("/methods/encrypt-password")
    @Operation(
            summary = "Encrypt a DB password",
            description = "Encrypts a plain-text password using AES-GCM for use in DB_QUERY method bindings.")
    public Map<String, String> encryptPassword(@RequestBody Map<String, String> body) {
        String plain = body.get("password");
        if (plain == null || plain.isBlank())
            throw new IllegalArgumentException("password is required");
        return Map.of("encryptedPassword", encryptionService.encrypt(plain));
    }

    // ─── Step attachment ──────────────────────────────────────────────────────

    @PostMapping("/flows/{flowId}/steps/{stepId}/methods")
    @Operation(
            summary = "Attach a method to a step",
            description = "Attaches a method to run before the step executes. " +
                    "Parameter bindings support {placeholder} syntax from previous step responses.")
    public ResponseEntity<Void> attachToStep(
            @PathVariable Long stepId,
            @Valid @RequestBody AttachMethodRequest request) {
        customMethodService.attachToStep(stepId, request);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/step-methods/{stepMethodId}")
    @Operation(summary = "Detach a method from a step")
    public ResponseEntity<Void> detachFromStep(@PathVariable Long stepMethodId) {
        customMethodService.detachFromStep(stepMethodId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/flows/{flowId}/steps/{stepId}/methods")
    @Operation(
            summary = "List methods attached to a step",
            description = "Returns all methods attached to a step in execution order.")
    public List<StepMethodDTO> getStepMethods(@PathVariable Long stepId) {
        return customMethodService.getStepMethods(stepId);
    }
}