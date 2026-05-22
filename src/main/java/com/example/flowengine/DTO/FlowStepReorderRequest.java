package com.example.flowengine.DTO;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class FlowStepReorderRequest {

    @NotEmpty
    @Valid
    private List<StepOrderUpdate> steps;

    @Data
    public static class StepOrderUpdate {
        @NotNull
        private Long stepId;

        @NotNull
        @Positive
        private Integer stepOrder;
    }
}
