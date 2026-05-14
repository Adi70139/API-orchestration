package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BulkExecutionRequest {

    @NotEmpty
    private List<Long> ids; // moduleIds or flowIds

    // Must be same size as ids, use null for items with no env
    // e.g. ids: [1, 2], envIds: [3, null] — module 1 uses env 3, module 2 uses no env
    private List<Long> envIds;
}