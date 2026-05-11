package com.example.flowengine.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AssertionResult {
    private String path;
    private boolean passed;
    private String message;
}