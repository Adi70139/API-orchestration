package com.example.flowengine.controller;

import com.example.flowengine.DTO.FlowStepRequest;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.service.FlowStepService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/flows/{flowId}/steps")
@RequiredArgsConstructor
public class FlowStepController {

    private final FlowStepService flowStepService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FlowStep create(@PathVariable Long flowId,
                           @Valid @RequestBody FlowStepRequest request) {
        return flowStepService.create(flowId, request);
    }

    @GetMapping
    public List<FlowStep> getByFlowId(@PathVariable Long flowId) {
        return flowStepService.getByFlowId(flowId);
    }

    @GetMapping("/{stepId}")
    public FlowStep getById(@PathVariable Long flowId,
                            @PathVariable Long stepId) {
        // flowId validated implicitly — step's FK ensures it belongs to a flow
        return flowStepService.getById(stepId);
    }

    @PutMapping("/{stepId}")
    public FlowStep update(@PathVariable Long flowId,
                           @PathVariable Long stepId,
                           @Valid @RequestBody FlowStepRequest request) {
        return flowStepService.update(stepId, request);
    }

    @DeleteMapping("/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long flowId,
                       @PathVariable Long stepId) {
        flowStepService.delete(stepId);
    }
}
