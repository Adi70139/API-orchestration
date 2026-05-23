package com.example.flowengine.DTO;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AssertionResult {
    private String path;
    private boolean passed;
    private String message;
    private boolean critical = true; // default true — non-critical failures don't stop the flow

    // Constructor for backward compatibility (existing callers pass 3 args)
    public AssertionResult(String path, boolean passed, String message) {
        this.path = path;
        this.passed = passed;
        this.message = message;
        this.critical = false;
    }

    // Constructor with explicit critical flag
    public AssertionResult(String path, boolean passed, String message, boolean critical) {
        this.path = path;
        this.passed = passed;
        this.message = message;
        this.critical = critical;
    }
}