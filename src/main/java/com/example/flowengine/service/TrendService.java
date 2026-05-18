package com.example.flowengine.service;

import com.example.flowengine.DTO.DependencyGraphDTO;
import com.example.flowengine.DTO.FlowHistoryDTO;
import com.example.flowengine.DTO.StepTrendDTO;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowExecution;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.StepExecution;
import com.example.flowengine.repository.FlowExecutionRepository;
import com.example.flowengine.repository.FlowRepository;
import com.example.flowengine.repository.FlowStepRepository;
import com.example.flowengine.repository.StepExecutionRepository;
import com.example.flowengine.utils.PlaceholderUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrendService {

    private static final int TREND_WINDOW = 20; // last N runs for trend calculations

    private final FlowRepository flowRepository;
    private final FlowStepRepository flowStepRepository;
    private final FlowExecutionRepository flowExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;
    private final ObjectMapper objectMapper;

    // ── Execution History ─────────────────────────────────────────────────────

    public FlowHistoryDTO getFlowHistory(Long flowId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        List<FlowExecution> executions = flowExecutionRepository
                .findByFlowIdOrderByStartedAtDesc(flowId);

        FlowHistoryDTO dto = new FlowHistoryDTO();
        dto.setFlowId(flowId);
        dto.setFlowName(flow.getName());
        dto.setTotalRuns(executions.size());
        dto.setTrend(computeFlowTrend(executions));
        dto.setRuns(executions.stream()
                .map(this::mapToRunRecord)
                .collect(Collectors.toList()));
        return dto;
    }

    public List<StepTrendDTO> getStepTrends(Long flowId) {
        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flowId);
        return steps.stream()
                .map(step -> computeStepTrend(step, TREND_WINDOW))
                .collect(Collectors.toList());
    }

    // ── Dependency Graph ──────────────────────────────────────────────────────

    public DependencyGraphDTO getDependencyGraph(Long flowId) {
        FlowDefinition flow = flowRepository.findById(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not found: " + flowId));

        List<FlowStep> steps = flowStepRepository.findByFlowIdOrderByStepOrder(flowId);

        // Get last execution's step responses to populate producedKeys
        Map<Long, String> lastResponseByStepId = getLastResponseByStepId(flowId);

        // Build nodes
        List<DependencyGraphDTO.StepNode> nodes = steps.stream()
                .map(step -> buildNode(step, lastResponseByStepId.get(step.getId())))
                .collect(Collectors.toList());

        // Build edges — for each step, find which prior step produces each placeholder it uses
        List<DependencyGraphDTO.DependencyEdge> edges = buildEdges(steps, nodes);

        DependencyGraphDTO graph = new DependencyGraphDTO();
        graph.setFlowId(flowId);
        graph.setFlowName(flow.getName());
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    // ── Private: Trend Computation ────────────────────────────────────────────

    private FlowHistoryDTO.FlowTrendSummary computeFlowTrend(List<FlowExecution> executions) {
        FlowHistoryDTO.FlowTrendSummary summary = new FlowHistoryDTO.FlowTrendSummary();
        if (executions.isEmpty()) {
            summary.setPassRatePercent(0);
            summary.setTrend("STABLE");
            return summary;
        }

        List<FlowExecution> window = executions.stream()
                .limit(TREND_WINDOW).collect(Collectors.toList());

        long passCount = window.stream()
                .filter(e -> "PASS".equals(e.getStatus().name())).count();
        double passRate = (passCount * 100.0) / window.size();
        summary.setPassRatePercent(Math.round(passRate * 10.0) / 10.0);

        // Average duration
        OptionalDouble avgMs = window.stream()
                .filter(e -> e.getStartedAt() != null && e.getFinishedAt() != null)
                .mapToLong(e -> Duration.between(e.getStartedAt(), e.getFinishedAt()).toMillis())
                .average();
        summary.setAvgDurationMs((long) avgMs.orElse(0));

        // Fail streaks
        int currentStreak = 0;
        for (FlowExecution e : executions) { // newest first
            if ("FAIL".equals(e.getStatus().name())) currentStreak++;
            else break;
        }
        summary.setCurrentFailStreak(currentStreak);

        int longestStreak = 0, streak = 0;
        for (FlowExecution e : executions) {
            if ("FAIL".equals(e.getStatus().name())) {
                streak++;
                longestStreak = Math.max(longestStreak, streak);
            } else {
                streak = 0;
            }
        }
        summary.setLongestFailStreak(longestStreak);

        // Trend direction: compare pass rate of first half vs second half of window
        if (window.size() >= 6) {
            int half = window.size() / 2;
            // window is newest first — second half is older
            long recentPasses = window.subList(0, half).stream()
                    .filter(e -> "PASS".equals(e.getStatus().name())).count();
            long olderPasses = window.subList(half, window.size()).stream()
                    .filter(e -> "PASS".equals(e.getStatus().name())).count();
            double recentRate = (recentPasses * 100.0) / half;
            double olderRate = (olderPasses * 100.0) / (window.size() - half);
            if (recentRate > olderRate + 10) summary.setTrend("IMPROVING");
            else if (recentRate < olderRate - 10) summary.setTrend("DEGRADING");
            else summary.setTrend("STABLE");
        } else {
            summary.setTrend("STABLE");
        }

        return summary;
    }

    private StepTrendDTO computeStepTrend(FlowStep step, int window) {
        List<StepExecution> executions = stepExecutionRepository
                .findLastNByStepId(step.getId(), window);

        StepTrendDTO dto = new StepTrendDTO();
        dto.setStepId(step.getId());
        dto.setStepName(step.getName());
        dto.setStepOrder(step.getStepOrder());
        dto.setTotalRuns(executions.size());

        if (executions.isEmpty()) {
            dto.setPassRatePercent(0);
            dto.setResultTimeline(List.of());
            return dto;
        }

        long passCount = executions.stream().filter(StepExecution::isSuccess).count();
        long failCount = executions.size() - passCount;
        dto.setPassCount((int) passCount);
        dto.setFailCount((int) failCount);
        dto.setPassRatePercent(Math.round((passCount * 100.0 / executions.size()) * 10.0) / 10.0);

        // Duration stats
        LongSummaryStatistics durStats = executions.stream()
                .filter(e -> e.getDurationMs() != null)
                .mapToLong(StepExecution::getDurationMs)
                .summaryStatistics();
        dto.setAvgDurationMs((long) durStats.getAverage());
        dto.setMinDurationMs(durStats.getMin() == Long.MAX_VALUE ? 0 : durStats.getMin());
        dto.setMaxDurationMs(durStats.getMax() == Long.MIN_VALUE ? 0 : durStats.getMax());

        // Current fail streak (executions are newest first from query)
        int currentStreak = 0;
        for (StepExecution e : executions) {
            if (!e.isSuccess()) currentStreak++;
            else break;
        }
        dto.setCurrentFailStreak(currentStreak);

        // Flaky: pass rate between 20% and 80% over the window
        dto.setFlaky(dto.getPassRatePercent() > 20 && dto.getPassRatePercent() < 80);

        // Timeline: reverse to oldest→newest for sparkline
        List<Boolean> timeline = new ArrayList<>();
        for (int i = executions.size() - 1; i >= 0; i--) {
            timeline.add(executions.get(i).isSuccess());
        }
        dto.setResultTimeline(timeline);

        // Most common error
        executions.stream()
                .filter(e -> !e.isSuccess() && e.getErrorMessage() != null)
                .collect(Collectors.groupingBy(StepExecution::getErrorMessage, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> dto.setMostCommonError(e.getKey()));

        return dto;
    }

    // ── Private: History Mapping ──────────────────────────────────────────────

    private FlowHistoryDTO.RunRecord mapToRunRecord(FlowExecution execution) {
        FlowHistoryDTO.RunRecord record = new FlowHistoryDTO.RunRecord();
        record.setExecutionId(execution.getId());
        record.setStatus(execution.getStatus().name());
        record.setStartedAt(execution.getStartedAt());
        record.setFinishedAt(execution.getFinishedAt());
        if (execution.getStartedAt() != null && execution.getFinishedAt() != null) {
            record.setDurationMs(Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toMillis());
        }

        List<StepExecution> stepExecutions = stepExecutionRepository
                .findByFlowExecutionIdOrderByStepOrderAsc(execution.getId());

        record.setSteps(stepExecutions.stream().map(se -> {
            FlowHistoryDTO.StepRunRecord sr = new FlowHistoryDTO.StepRunRecord();
            sr.setStepId(se.getStepId());
            sr.setStepName(se.getStepName());
            sr.setStepOrder(se.getStepOrder());
            sr.setSuccess(se.isSuccess());
            sr.setStatusCode(se.getStatusCode());
            sr.setDurationMs(se.getDurationMs());
            sr.setErrorMessage(se.getErrorMessage());
            sr.setTotalAttempts(se.getTotalAttempts());
            return sr;
        }).collect(Collectors.toList()));

        return record;
    }

    // ── Private: Dependency Graph ─────────────────────────────────────────────

    private DependencyGraphDTO.StepNode buildNode(FlowStep step, String lastResponseBody) {
        DependencyGraphDTO.StepNode node = new DependencyGraphDTO.StepNode();
        node.setStepId(step.getId());
        node.setStepName(step.getName());
        node.setStepOrder(step.getStepOrder());
        node.setMethod(step.getMethod());
        node.setUrl(step.getUrl());

        // Collect all placeholders this step references across url, headers, body, assertions
        Set<String> used = PlaceholderUtils.extractParams(
                step.getUrl(),
                step.getHeadersJson(),
                step.getBodyJson(),
                step.getAssertionsJson()
        );
        node.setUsedPlaceholders(used);

        // Populate produced keys from last known response
        node.setProducedKeys(extractTopLevelKeys(lastResponseBody));

        return node;
    }

    private List<DependencyGraphDTO.DependencyEdge> buildEdges(
            List<FlowStep> steps,
            List<DependencyGraphDTO.StepNode> nodes) {

        List<DependencyGraphDTO.DependencyEdge> edges = new ArrayList<>();

        // Index nodes by stepId for quick lookup
        Map<Long, DependencyGraphDTO.StepNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(DependencyGraphDTO.StepNode::getStepId, n -> n));

        for (int i = 1; i < steps.size(); i++) {
            FlowStep consumer = steps.get(i);
            DependencyGraphDTO.StepNode consumerNode = nodeMap.get(consumer.getId());
            if (consumerNode.getUsedPlaceholders().isEmpty()) continue;

            // For each placeholder this step uses, find the earliest prior step that produces it
            // Key can come from any prior step — we track all of them
            Map<Long, Set<String>> producerToKeys = new LinkedHashMap<>();

            for (String placeholder : consumerNode.getUsedPlaceholders()) {
                // Walk backwards through prior steps to find who produces this key
                for (int j = i - 1; j >= 0; j--) {
                    FlowStep producer = steps.get(j);
                    DependencyGraphDTO.StepNode producerNode = nodeMap.get(producer.getId());

                    // Check if this producer provides the key (exact match or dot-prefix match)
                    boolean produces = producerNode.getProducedKeys().stream()
                            .anyMatch(k -> k.equals(placeholder) || k.startsWith(placeholder + "."));

                    if (produces) {
                        producerToKeys
                                .computeIfAbsent(producer.getId(), id -> new LinkedHashSet<>())
                                .add(placeholder);
                        break; // found the provider — PlaceholderUtils uses last-write-wins but
                        // earliest provider is the most meaningful for the graph
                    }
                }
            }

            // Create one edge per producer→consumer relationship
            for (Map.Entry<Long, Set<String>> entry : producerToKeys.entrySet()) {
                DependencyGraphDTO.StepNode producerNode = nodeMap.get(entry.getKey());
                DependencyGraphDTO.DependencyEdge edge = new DependencyGraphDTO.DependencyEdge();
                edge.setFromStepId(entry.getKey());
                edge.setFromStepName(producerNode.getStepName());
                edge.setToStepId(consumer.getId());
                edge.setToStepName(consumer.getName());
                edge.setKeys(entry.getValue());
                edges.add(edge);
            }
        }

        return edges;
    }

    private Set<String> extractTopLevelKeys(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return Set.of();
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (!root.isObject()) return Set.of();
            // Return flattened dot-notation keys from PlaceholderUtils
            Map<String, String> flat = PlaceholderUtils.buildLookupMap(List.of(responseBody));
            return flat.keySet();
        } catch (Exception e) {
            return Set.of();
        }
    }

    private Map<Long, String> getLastResponseByStepId(Long flowId) {
        // Get the most recent execution for this flow
        List<FlowExecution> recent = flowExecutionRepository
                .findByFlowIdOrderByStartedAtDesc(flowId, PageRequest.of(0, 1));
        if (recent.isEmpty()) return Map.of();

        FlowExecution lastExecution = recent.get(0);
        List<StepExecution> stepExecutions = stepExecutionRepository
                .findByFlowExecutionIdOrderByStepOrderAsc(lastExecution.getId());

        return stepExecutions.stream()
                .filter(se -> se.getResponseBody() != null)
                .collect(Collectors.toMap(
                        StepExecution::getStepId,
                        StepExecution::getResponseBody,
                        (a, b) -> a // keep first on conflict
                ));
    }
}