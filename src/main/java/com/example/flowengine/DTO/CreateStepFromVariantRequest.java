package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class CreateStepFromVariantRequest {

    // Identify the variant by name (e.g. "Variant 2") OR by index (0-based) — at least one is required.
    // Name takes precedence if both are given.
    private String variantName;
    private Integer variantIndex;

    // Optional — defaults to "<source step name> (<variant name>)"
    private String name;
}