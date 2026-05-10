package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BulkExecutionRequest {

    @NotEmpty
    private List<Long> ids; // moduleIds or flowIds
}