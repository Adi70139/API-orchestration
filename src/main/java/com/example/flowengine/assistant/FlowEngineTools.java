package com.example.flowengine.assistant;

import com.example.flowengine.DTO.*;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.service.*;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * All tools exposed to the LangChain4j AI agent.
 *
 * Destructive tools (delete/update) require confirmed=true.
 * When confirmed=false the tool returns a confirmation prompt instead of executing —
 * the LLM then surfaces that to the user and waits for explicit approval.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowEngineTools {

    private static final String CONFIRM_MSG =
            "NEEDS_CONFIRMATION: %s. Reply 'yes' or 'confirm' to proceed.";

    private final ModuleService moduleService;
    private final FlowService flowService;
    private final FlowStepService flowStepService;
    private final EnvironmentService environmentService;
    private final ExecutorService executorService;

    // ── Modules ──────────────────────────────────────────────────────────────

    @Tool("List all modules in the workspace. Returns id, name, description, and flow count for each.")
    public String listModules() {
        log.info("[Tool] listModules");
        var modules = moduleService.getAll();
        if (modules.isEmpty()) return "No modules found.";
        StringBuilder sb = new StringBuilder("Modules:\n");
        modules.forEach(m -> sb.append(String.format("  - [id=%d] %s: %s (%d flows)\n",
                m.getId(), m.getName(),
                m.getDescription() != null ? m.getDescription() : "no description",
                m.getFlowCount() != null ? m.getFlowCount() : 0)));
        return sb.toString();
    }

    @Tool("Create a new module with the given name and optional description.")
    public String createModule(
            @P("Name of the module to create") String name,
            @P("Optional description of the module") String description) {
        log.info("[Tool] createModule name={}", name);
        ModuleEntity module = new ModuleEntity();
        module.setName(name);
        module.setDescription(description);
        ModuleEntity created = moduleService.create(module);
        return String.format("Module created: [id=%d] %s", created.getId(), created.getName());
    }

    @Tool("Update an existing module's name and/or description. Requires user confirmation before executing.")
    public String updateModule(
            @P("ID of the module to update") Long moduleId,
            @P("New name for the module") String name,
            @P("New description for the module") String description,
            @P("Set to true only after the user has explicitly confirmed this update") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Update module [id=%d] — set name='%s', description='%s'",
                            moduleId, name, description));
        }
        log.info("[Tool] updateModule id={}", moduleId);
        ModuleUpdateRequest req = new ModuleUpdateRequest();
        req.setName(name);
        req.setDescription(description);
        var updated = moduleService.update(req, moduleId);
        return String.format("Module updated: [id=%d] %s", updated.getId(), updated.getName());
    }

    @Tool("Delete a module and all its flows and steps. Requires user confirmation before executing.")
    public String deleteModule(
            @P("ID of the module to delete") Long moduleId,
            @P("Set to true only after the user has explicitly confirmed this deletion") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Delete module [id=%d] — this will also delete all its flows and steps", moduleId));
        }
        log.info("[Tool] deleteModule id={}", moduleId);
        moduleService.delete(moduleId);
        return "Module " + moduleId + " deleted.";
    }

    // ── Flows ─────────────────────────────────────────────────────────────────

    @Tool("List all flows across all modules. Returns id, name, and module name for each.")
    public String listFlows() {
        log.info("[Tool] listFlows");
        var flows = flowService.getAll();
        if (flows.isEmpty()) return "No flows found.";
        StringBuilder sb = new StringBuilder("Flows:\n");
        flows.forEach(f -> sb.append(String.format("  - [id=%d] %s (module: %s)\n",
                f.getId(), f.getName(), f.getModuleName())));
        return sb.toString();
    }

    @Tool("Get full details of a single flow including its steps.")
    public String getFlow(@P("ID of the flow") Long flowId) {
        log.info("[Tool] getFlow id={}", flowId);
        var flow = flowService.getById(flowId);
        StringBuilder sb = new StringBuilder(String.format(
                "Flow [id=%d] '%s' (module: %s, %d steps)\n",
                flow.getId(), flow.getName(), flow.getModuleName(),
                flow.getSteps() != null ? flow.getSteps().size() : 0));
        if (flow.getSteps() != null) {
            flow.getSteps().forEach(s -> sb.append(String.format(
                    "  Step %d: [id=%d] %s %s %s\n",
                    s.getStepOrder(), s.getId(), s.getName(), s.getMethod(), s.getUrl())));
        }
        return sb.toString();
    }

    @Tool("Create a new flow inside a module.")
    public String createFlow(
            @P("Name for the new flow") String name,
            @P("Name of the module this flow belongs to") String moduleName,
            @P("Optional description of the flow") String description) {
        log.info("[Tool] createFlow name={} module={}", name, moduleName);
        FlowRequest req = new FlowRequest();
        req.setName(name);
        req.setModule(moduleName);
        req.setDescription(description);
        var created = flowService.create(req);
        return String.format("Flow created: [id=%d] %s in module '%s'", created.getId(), created.getName(), moduleName);
    }

    @Tool("Update a flow's name or description. Requires user confirmation before executing.")
    public String updateFlow(
            @P("ID of the flow to update") Long flowId,
            @P("New name for the flow") String name,
            @P("New description") String description,
            @P("Module name (required even if unchanged)") String moduleName,
            @P("Set to true only after the user has explicitly confirmed this update") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Update flow [id=%d] — set name='%s'", flowId, name));
        }
        log.info("[Tool] updateFlow id={}", flowId);
        FlowRequest req = new FlowRequest();
        req.setName(name);
        req.setModule(moduleName);
        req.setDescription(description);
        var updated = flowService.update(req, flowId);
        return String.format("Flow updated: [id=%d] %s", updated.getId(), updated.getName());
    }

    @Tool("Delete a flow by its ID. Requires user confirmation before executing.")
    public String deleteFlow(
            @P("ID of the flow to delete") Long flowId,
            @P("Set to true only after the user has explicitly confirmed this deletion") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Delete flow [id=%d] — this will also delete all its steps", flowId));
        }
        log.info("[Tool] deleteFlow id={}", flowId);
        flowService.delete(flowId);
        return "Flow " + flowId + " deleted.";
    }

    @Tool("Duplicate an existing flow, optionally giving it a new name.")
    public String duplicateFlow(
            @P("ID of the flow to duplicate") Long flowId,
            @P("Optional name for the duplicated flow") String newName) {
        log.info("[Tool] duplicateFlow id={}", flowId);
        DuplicateFlowRequest req = new DuplicateFlowRequest();
        req.setName(newName);
        var created = flowService.duplicateFlow(flowId, req);
        return String.format("Flow duplicated: [id=%d] %s", created.getId(), created.getName());
    }

    // ── Steps ─────────────────────────────────────────────────────────────────

    @Tool("List all steps in a flow, ordered by stepOrder.")
    public String listSteps(@P("ID of the flow") Long flowId) {
        log.info("[Tool] listSteps flowId={}", flowId);
        var steps = flowStepService.getByFlowId(flowId);
        if (steps.isEmpty()) return "No steps found in flow " + flowId;
        StringBuilder sb = new StringBuilder(String.format("Steps in flow %d:\n", flowId));
        steps.forEach(s -> sb.append(String.format("  %d. [id=%d] %s — %s %s\n",
                s.getStepOrder(), s.getId(), s.getName(), s.getMethod(), s.getUrl())));
        return sb.toString();
    }

    @Tool("Create a new HTTP step in a flow.")
    public String createStep(
            @P("ID of the flow to add the step to") Long flowId,
            @P("Name of the step") String name,
            @P("HTTP method: GET, POST, PUT, PATCH, or DELETE") String method,
            @P("URL for the step. Use {placeholder} syntax to reference previous step response fields") String url,
            @P("Optional description") String description,
            @P("Optional JSON string for request headers") String headersJson,
            @P("Optional JSON string for request body") String bodyJson) {
        log.info("[Tool] createStep flowId={} method={} url={}", flowId, method, url);
        FlowStepRequest req = new FlowStepRequest();
        req.setName(name);
        req.setDescription(description);
        req.setMethod(method);
        req.setUrl(url);
        req.setHeadersJson(headersJson);
        req.setBodyJson(bodyJson);
        var created = flowStepService.create(flowId, req);
        return String.format("Step created: [id=%d] '%s' at order %d in flow %d",
                created.getId(), created.getName(), created.getStepOrder(), flowId);
    }

    @Tool("Update an existing step's properties. Requires user confirmation before executing.")
    public String updateStep(
            @P("ID of the step to update") Long stepId,
            @P("Name of the step") String name,
            @P("HTTP method: GET, POST, PUT, PATCH, or DELETE") String method,
            @P("URL for the step") String url,
            @P("Optional description") String description,
            @P("Optional JSON string for request headers") String headersJson,
            @P("Optional JSON string for request body") String bodyJson,
            @P("Set to true only after the user has explicitly confirmed this update") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Update step [id=%d] — set name='%s', method=%s, url='%s'",
                            stepId, name, method, url));
        }
        log.info("[Tool] updateStep id={}", stepId);
        FlowStepRequest req = new FlowStepRequest();
        req.setName(name);
        req.setDescription(description);
        req.setMethod(method);
        req.setUrl(url);
        req.setHeadersJson(headersJson);
        req.setBodyJson(bodyJson);
        var updated = flowStepService.update(stepId, req);
        return String.format("Step updated: [id=%d] '%s'", updated.getId(), updated.getName());
    }

    @Tool("Delete a step by its ID. Requires user confirmation before executing.")
    public String deleteStep(
            @P("ID of the step to delete") Long stepId,
            @P("Set to true only after the user has explicitly confirmed this deletion") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Delete step [id=%d]", stepId));
        }
        log.info("[Tool] deleteStep id={}", stepId);
        flowStepService.delete(stepId);
        return "Step " + stepId + " deleted.";
    }

    @Tool("Duplicate a step within a flow.")
    public String duplicateStep(
            @P("ID of the flow containing the step") Long flowId,
            @P("ID of the step to duplicate") Long stepId,
            @P("Optional name for the duplicated step") String newName) {
        log.info("[Tool] duplicateStep flowId={} stepId={}", flowId, stepId);
        DuplicateFlowStepRequest req = new DuplicateFlowStepRequest();
        req.setName(newName);
        var created = flowStepService.duplicate(flowId, stepId, req);
        return String.format("Step duplicated: [id=%d] '%s'", created.getId(), created.getName());
    }

    // ── Environments ──────────────────────────────────────────────────────────

    @Tool("List all environments for a module.")
    public String listEnvironments(@P("ID of the module") Long moduleId) {
        log.info("[Tool] listEnvironments moduleId={}", moduleId);
        var envs = environmentService.getByModuleId(moduleId);
        if (envs.isEmpty()) return "No environments found for module " + moduleId;
        StringBuilder sb = new StringBuilder(String.format("Environments for module %d:\n", moduleId));
        envs.forEach(e -> sb.append(String.format("  - [id=%d] %s\n", e.getId(), e.getName())));
        return sb.toString();
    }

    @Tool("Create a new environment for a module.")
    public String createEnvironment(
            @P("ID of the module") Long moduleId,
            @P("Name for the environment, e.g. DEV, STAGING, PROD") String name) {
        log.info("[Tool] createEnvironment moduleId={} name={}", moduleId, name);
        EnvironmentRequest req = new EnvironmentRequest();
        req.setName(name);
        var created = environmentService.create(moduleId, req);
        return String.format("Environment created: [id=%d] %s", created.getId(), created.getName());
    }

    @Tool("Delete an environment by its ID. Requires user confirmation before executing.")
    public String deleteEnvironment(
            @P("ID of the environment to delete") Long environmentId,
            @P("Set to true only after the user has explicitly confirmed this deletion") boolean confirmed) {
        if (!confirmed) {
            return String.format(CONFIRM_MSG,
                    String.format("Delete environment [id=%d]", environmentId));
        }
        log.info("[Tool] deleteEnvironment id={}", environmentId);
        environmentService.delete(environmentId);
        return "Environment " + environmentId + " deleted.";
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    @Tool("Run all steps in a flow sequentially. Returns pass/fail result with step details.")
    public String runFlow(
            @P("ID of the flow to run") Long flowId,
            @P("Optional environment ID to use for variable resolution") Long environmentId) {
        log.info("[Tool] runFlow id={}", flowId);
        var result = executorService.runFlow(flowId, environmentId);
        return String.format("Flow '%s' %s in %dms. %d/%d steps passed.%s",
                result.getFlowName(),
                result.isAllStepsPassed() ? "PASSED" : "FAILED",
                result.getTotalDurationMs(),
                result.getStepResults().stream().filter(s -> s.isSuccess()).count(),
                result.getStepResults().size(),
                result.isAllStepsPassed() ? "" : buildFailureSummary(result));
    }

    @Tool("Run all flows in a module sequentially. Returns aggregate pass/fail result.")
    public String runModule(
            @P("ID of the module to run") Long moduleId,
            @P("Optional environment ID to use for variable resolution") Long environmentId) {
        log.info("[Tool] runModule id={}", moduleId);
        var result = executorService.runModule(moduleId, environmentId);
        return String.format("Module '%s' %s in %dms. %d/%d flows passed.",
                result.getModuleName(),
                result.isAllFlowsPassed() ? "PASSED" : "FAILED",
                result.getTotalDurationMs(),
                result.getFlowResults().stream().filter(f -> f.isAllStepsPassed()).count(),
                result.getFlowResults().size());
    }

    private String buildFailureSummary(FlowExecutionResult result) {
        StringBuilder sb = new StringBuilder(" Failed steps:");
        result.getStepResults().stream()
                .filter(s -> !s.isSuccess())
                .forEach(s -> sb.append(String.format(" [%s: %s]",
                        s.getStepName(),
                        s.getErrorMessage() != null ? s.getErrorMessage() : "HTTP " + s.getStatusCode())));
        return sb.toString();
    }

    // ── Import guidance ───────────────────────────────────────────────────────
    // File uploads can't go through the chat API directly — they need multipart form.
    // These tools tell the user how to use the existing import endpoints.

    @Tool("Tell the user how to import a Postman collection, Swagger/OpenAPI spec, or HAR file into a module as a flow.")
    public String getImportInstructions(
            @P("Type of import: 'postman', 'swagger', or 'har'") String importType) {
        return switch (importType.toLowerCase().trim()) {
            case "postman" -> """
                    To import a Postman collection:
                    POST /import/postman  (multipart/form-data)
                      - file: your Postman collection JSON file
                      - moduleId: the ID of the module to import into
                      - flowName: name for the new flow
                    Postman {{variables}} are automatically converted to {variables}.
                    """;
            case "swagger", "openapi" -> """
                    To import a Swagger/OpenAPI spec:
                    POST /import/swagger  (multipart/form-data)
                      - file: your OpenAPI JSON or YAML file
                      - moduleId: the ID of the module to import into
                      - flowName: name for the new flow
                    Each API operation becomes one step in the flow.
                    """;
            case "har" -> """
                    To import a HAR recording:
                    POST /import/har  (multipart/form-data)
                      - file: your HAR file (export from browser DevTools Network tab)
                      - moduleId: the ID of the module to import into
                      - flowName: name for the new flow (optional if flowId given)
                      - flowId: existing flow ID to append steps to (optional)
                      - filterDomain: only import requests from this domain (optional)
                    Only XHR/Fetch calls are imported — static assets are filtered out.
                    How to export HAR: Chrome DevTools → Network tab → right-click → Save all as HAR with content
                    """;
            default -> "Supported import types: 'postman', 'swagger', 'har'. Which one do you need?";
        };
    }
}