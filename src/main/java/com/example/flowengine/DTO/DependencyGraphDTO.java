package com.example.flowengine.DTO;

import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Visual dependency graph for a flow's steps.
 * Nodes = steps, edges = data dependencies via {placeholder} usage.
 *
 * Frontend renders this as a DAG — each node shows the step,
 * each edge is labelled with which placeholder keys flow between steps.
 */
@Data
public class DependencyGraphDTO {

    private Long flowId;
    private String flowName;

    private List<StepNode> nodes;
    private List<DependencyEdge> edges;

    @Data
    public static class StepNode {
        private Long stepId;
        private String stepName;
        private Integer stepOrder;
        private String method;
        private String url;                  // raw (unresolved) url
        private Set<String> usedPlaceholders; // all {keys} this step references
        private Set<String> producedKeys;    // all top-level keys this step's response provides
        // populated from last execution's response body if available
    }

    @Data
    public static class DependencyEdge {
        private Long fromStepId;    // step that produces the value
        private Long toStepId;      // step that consumes it
        private String fromStepName;
        private String toStepName;
        private Set<String> keys;   // which placeholder keys flow across this edge
    }
}