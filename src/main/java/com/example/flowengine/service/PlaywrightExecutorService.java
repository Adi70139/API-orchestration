package com.example.flowengine.service;

import com.example.flowengine.DTO.FlowExecutionResult;
import com.example.flowengine.DTO.StepExecutionResult;
import com.example.flowengine.constants.ExecutionStatus;
import com.example.flowengine.entity.FlowDefinition;
import com.example.flowengine.entity.FlowExecution;
import com.example.flowengine.entity.FlowStep;
import com.example.flowengine.entity.StepExecution;
import com.example.flowengine.repository.FlowExecutionRepository;
import com.example.flowengine.repository.StepExecutionRepository;
import com.example.flowengine.service.UIAutomationService.AutomationStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlaywrightExecutorService {

    private final ObjectMapper objectMapper;
    private final FlowExecutionRepository flowExecutionRepository;
    private final StepExecutionRepository stepExecutionRepository;

    @Value("${ui.automation.headless:false}")
    private boolean headless;

    @Value("${ui.automation.slow-mo-ms:800}")
    private int slowMoMs;

    public FlowExecutionResult executeUIFlow(FlowDefinition flow, List<FlowStep> steps) {
        return executeUIFlow(flow, steps, null);
    }

    public FlowExecutionResult executeUIFlow(FlowDefinition flow, List<FlowStep> steps, Long flowExecutionId) {
        long flowStart = System.currentTimeMillis();
        List<StepExecutionResult> stepResults = new ArrayList<>();

        log.info("[PlaywrightExecutor] Starting flow='{}' steps={} headless={} flowExecutionId={}",
                flow.getName(), steps.size(), headless, flowExecutionId);

        // Mark flow IN_PROGRESS
        updateFlowStatus(flowExecutionId, ExecutionStatus.IN_PROGRESS, false);

        // Load step executions so we can update each one
        Map<Long, StepExecution> stepExecutionMap = loadStepExecutions(flowExecutionId);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(headless)
                            .setSlowMo(slowMoMs)
            );

            BrowserContext context = browser.newContext(
                    new Browser.NewContextOptions().setViewportSize(1280, 800)
            );
            Page page = context.newPage();

            // Navigate to base URL from first step
            String baseUrl = steps.isEmpty() ? "" : steps.get(0).getUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                log.info("[PlaywrightExecutor] Navigating to {}", baseUrl);
                page.navigate(baseUrl);
                page.waitForLoadState(LoadState.NETWORKIDLE);
            }

            for (FlowStep step : steps) {
                StepExecution stepExecution = stepExecutionMap.get(step.getId());
                StepExecutionResult result = executeUIStep(page, step, stepExecution);
                stepResults.add(result);

                if (!result.isSuccess()) {
                    log.warn("[PlaywrightExecutor] Step '{}' failed — stopping: {}",
                            step.getName(), result.getErrorMessage());
                    try { page.screenshot(); } catch (Exception ignored) {}
                    // Mark remaining steps as FAIL
                    markRemainingStepsFailed(steps, stepResults.size(), stepExecutionMap,
                            "Previous step failed: " + result.getErrorMessage());
                    break;
                }
            }

            browser.close();

        } catch (Exception e) {
            log.error("[PlaywrightExecutor] Browser launch failed", e);
            StepExecutionResult errorResult = new StepExecutionResult();
            errorResult.setSuccess(false);
            errorResult.setErrorMessage("Browser failed to launch: " + e.getMessage() +
                    ". Run: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args=\"install chromium\"");
            stepResults.add(errorResult);
        }

        long totalMs = System.currentTimeMillis() - flowStart;
        boolean allPassed = stepResults.stream().allMatch(StepExecutionResult::isSuccess);

        // Update flow execution final status — this is what stops frontend polling
        updateFlowStatus(flowExecutionId, allPassed ? ExecutionStatus.PASS : ExecutionStatus.FAIL, true);
        log.info("[PlaywrightExecutor] Flow '{}' {} in {}ms",
                flow.getName(), allPassed ? "PASSED" : "FAILED", totalMs);

        FlowExecutionResult result = new FlowExecutionResult();
        result.setFlowId(flow.getId());
        result.setFlowName(flow.getName());
        result.setStepResults(stepResults);
        result.setAllStepsPassed(allPassed);
        result.setTotalDurationMs(totalMs);
        result.setFlowExecutionId(flowExecutionId);
        return result;
    }

    private StepExecutionResult executeUIStep(Page page, FlowStep step, StepExecution stepExecution) {
        long start = System.currentTimeMillis();
        StepExecutionResult result = new StepExecutionResult();
        result.setStepId(step.getId());
        result.setStepName(step.getName());

        // Mark step IN_PROGRESS
        updateStepStatus(stepExecution, ExecutionStatus.IN_PROGRESS, null, null, null);

        try {
            if (step.getBodyJson() == null || step.getBodyJson().isBlank()) {
                updateStepStatus(stepExecution, ExecutionStatus.PASS,
                        "No action defined", 200, System.currentTimeMillis() - start);
                result.setSuccess(true);
                result.setResponseBody("No action defined");
                result.setDurationMs(System.currentTimeMillis() - start);
                return result;
            }

            AutomationStep action = objectMapper.readValue(step.getBodyJson(), AutomationStep.class);
            log.info("[PlaywrightExecutor] Step '{}': {} → {}",
                    step.getName(), action.action, action.locator);

            // Initial delay before first attempt — same semantics as HTTP steps
            int initialDelay = step.getInitialDelayMs() != null ? step.getInitialDelayMs() : 0;
            if (initialDelay > 0) {
                log.info("[PlaywrightExecutor] Step '{}' initial delay {}ms", step.getName(), initialDelay);
                page.waitForTimeout(initialDelay);
            }

            // Retry loop — reuses retryCount from step config (max 5 enforced at entity level)
            int maxAttempts = 1 + (step.getRetryCount() != null ? step.getRetryCount() : 0);
            String output = null;
            Exception lastError = null;

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    output = dispatch(page, action);
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < maxAttempts) {
                        log.warn("[PlaywrightExecutor] Step '{}' attempt {}/{} failed: {} — retrying",
                                step.getName(), attempt, maxAttempts, e.getMessage());
                        page.waitForTimeout(1000); // brief pause before retry
                    }
                }
            }

            if (lastError != null) throw lastError;

            long duration = System.currentTimeMillis() - start;

            updateStepStatus(stepExecution, ExecutionStatus.PASS, output, 200, duration);

            result.setSuccess(true);
            result.setStatusCode(200);
            result.setResponseBody(output);
            result.setDurationMs(duration);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("[PlaywrightExecutor] Step '{}' failed: {}", step.getName(), e.getMessage());

            updateStepStatus(stepExecution, ExecutionStatus.FAIL, e.getMessage(), 0, duration);

            result.setSuccess(false);
            result.setStatusCode(0);
            result.setErrorMessage(e.getMessage());
            result.setDurationMs(duration);
        }

        return result;
    }

    /** Package-visible so UIAutomationService can reuse it for iterative generation. */
    String dispatch(Page page, AutomationStep action) {
        String loc = action.locator;
        String val = action.value;

        return switch (action.action.toLowerCase()) {
            case "navigate" -> {
                String url = action.url != null ? action.url : val;
                page.navigate(url);
                page.waitForLoadState(LoadState.NETWORKIDLE);
                yield "Navigated to " + url;
            }
            case "click" -> {
                resolve(page, loc).click();
                // Wait for any navigation triggered by the click to settle
                try { page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(5000)); }
                catch (Exception ignored) {} // timeout is fine — not all clicks navigate
                yield "Clicked: " + loc;
            }
            case "fill" -> {
                Locator el = resolve(page, loc);
                el.clear();
                el.fill(val != null ? val : "");
                yield "Filled '" + val + "' into: " + loc;
            }
            case "select" -> {
                resolve(page, loc).selectOption(val);
                yield "Selected '" + val + "' in: " + loc;
            }
            case "check"   -> { resolve(page, loc).check();   yield "Checked: " + loc; }
            case "uncheck" -> { resolve(page, loc).uncheck(); yield "Unchecked: " + loc; }
            case "hover"   -> { resolve(page, loc).hover();   yield "Hovered: " + loc; }
            case "waitforselector" -> {
                page.waitForSelector(loc);
                yield "Element appeared: " + loc;
            }
            case "waitforurl" -> {
                String target = action.url != null ? action.url : val;
                page.waitForURL("**" + target + "**");
                yield "URL matched: " + target;
            }
            case "screenshot" -> {
                page.screenshot();
                yield "Screenshot taken";
            }
            case "assertvisible" -> {
                // Locator may be null if element was on a post-navigation page not scraped.
                // Fall back to getByText using value field.
                Locator assertLoc = (loc != null && !loc.isBlank())
                        ? resolve(page, loc)
                        : page.getByText(val, new Page.GetByTextOptions().setExact(false)).first();
                assertLoc.waitFor(new Locator.WaitForOptions().setTimeout(10000));
                if (!assertLoc.isVisible())
                    throw new AssertionError("Element not visible: " + (loc != null ? loc : val));
                yield "Visible: " + (loc != null ? loc : val);
            }
            case "asserttext" -> {
                Locator textLoc = (loc != null && !loc.isBlank())
                        ? resolve(page, loc)
                        : page.getByText(val, new Page.GetByTextOptions().setExact(false)).first();
                textLoc.waitFor(new Locator.WaitForOptions().setTimeout(10000));
                String actual = textLoc.innerText().trim();
                if (!actual.contains(val))
                    throw new AssertionError("Expected '" + val + "' but got '" + actual + "'");
                yield "Text matched: '" + val + "'";
            }
            case "asserturl" -> {
                String currentUrl = page.url();
                if (!currentUrl.contains(val))
                    throw new AssertionError("URL '" + currentUrl + "' doesn't contain '" + val + "'");
                yield "URL contains: " + val;
            }
            default -> {
                log.warn("[PlaywrightExecutor] Unknown action '{}', skipping", action.action);
                yield "Skipped: " + action.action;
            }
        };
    }

    // ── Locator resolution ────────────────────────────────────────────────────

    private Locator resolve(Page page, String locatorStr) {
        if (locatorStr == null || locatorStr.isBlank())
            throw new IllegalArgumentException("Locator is null or blank");

        // Handle compact notation formats: "getByRole: button {name: \"Log In\"}"
        if (locatorStr.contains(":") && !locatorStr.contains(":\"") && !locatorStr.startsWith("http")) {
            // Likely compact notation like "getByRole: button" or "getByLabel: Email"
            String[] parts = locatorStr.split(":", 2);
            if (parts.length == 2) {
                String method = parts[0].trim();
                String rest = parts[1].trim();

                switch (method) {
                    case "getByRole" -> {
                        return parseCompactGetByRole(page, locatorStr);
                    }
                    case "getByLabel" -> {
                        if (rest.startsWith("{")) {
                            // Extract from braces
                            int start = rest.indexOf('"');
                            int end = rest.lastIndexOf('"');
                            if (start >= 0 && end > start) {
                                String label = rest.substring(start + 1, end);
                                return page.getByLabel(label);
                            }
                        } else {
                            // Simple format like "getByLabel: Email"
                            return page.getByLabel(rest.replace("\"", ""));
                        }
                    }
                    case "getByPlaceholder" -> {
                        if (rest.startsWith("{")) {
                            int start = rest.indexOf('"');
                            int end = rest.lastIndexOf('"');
                            if (start >= 0 && end > start) {
                                String placeholder = rest.substring(start + 1, end);
                                return page.getByPlaceholder(placeholder);
                            }
                        } else {
                            return page.getByPlaceholder(rest.replace("\"", ""));
                        }
                    }
                    case "getByText" -> {
                        if (rest.startsWith("{")) {
                            int start = rest.indexOf('"');
                            int end = rest.lastIndexOf('"');
                            if (start >= 0 && end > start) {
                                String text = rest.substring(start + 1, end);
                                return page.getByText(text, new Page.GetByTextOptions().setExact(true)).first();
                            }
                        } else {
                            return page.getByText(rest.replace("\"", ""), new Page.GetByTextOptions().setExact(true)).first();
                        }
                    }
                }
            }
        }

        // Original function-call notation support
        if (locatorStr.startsWith("getByTestId("))
            return page.getByTestId(extractFirstQuoted(locatorStr));

        if (locatorStr.startsWith("getByLabel("))
            return page.getByLabel(extractFirstQuoted(locatorStr));

        if (locatorStr.startsWith("getByPlaceholder("))
            return page.getByPlaceholder(extractFirstQuoted(locatorStr));

        if (locatorStr.startsWith("getByRole(")) {
            String role = extractFirstQuoted(locatorStr);
            int nameIdx = locatorStr.indexOf("name:");
            if (nameIdx >= 0) {
                String name = extractQuotedAfter(locatorStr, nameIdx);
                return page.getByRole(ariaRole(role),
                        new Page.GetByRoleOptions().setName(name).setExact(false));
            }
            return page.getByRole(ariaRole(role));
        }

        if (locatorStr.startsWith("getByText(")) {
            String text = extractFirstQuoted(locatorStr);
            // Use first() to handle pages where the same text appears multiple times
            return page.getByText(text, new Page.GetByTextOptions().setExact(true)).first();
        }

        if (locatorStr.startsWith("locator("))
            return page.locator(extractFirstQuoted(locatorStr));

        // Raw CSS/XPath fallback
        return page.locator(locatorStr);
    }

    private Locator parseCompactGetByRole(Page page, String compactNotation) {
        // Format: "getByRole: button {name: \"Log In\"}"
        // Extract role and name if present
        String afterColon = compactNotation.substring("getByRole:".length()).trim();

        int braceIdx = afterColon.indexOf('{');
        String role = (braceIdx >= 0 ? afterColon.substring(0, braceIdx) : afterColon).trim();

        if (braceIdx >= 0 && afterColon.contains("name:")) {
            String bracedPart = afterColon.substring(braceIdx);
            int nameStart = bracedPart.indexOf("name:");
            if (nameStart >= 0) {
                String afterName = bracedPart.substring(nameStart + 5).trim();
                int quoteStart = afterName.indexOf('"');
                int quoteEnd = afterName.lastIndexOf('"');
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    String name = afterName.substring(quoteStart + 1, quoteEnd);
                    return page.getByRole(ariaRole(role),
                            new Page.GetByRoleOptions().setName(name).setExact(false));
                }
            }
        }

        return page.getByRole(ariaRole(role));
    }

    private String extractFirstQuoted(String s) {
        int start = s.indexOf('"');
        if (start < 0) start = s.indexOf('\'');
        if (start < 0) return s;
        int end = s.indexOf('"', start + 1);
        if (end < 0) end = s.indexOf('\'', start + 1);
        if (end < 0) return s;
        return s.substring(start + 1, end);
    }

    private String extractQuotedAfter(String s, int fromIndex) {
        String sub = s.substring(fromIndex);
        return extractFirstQuoted(sub);
    }

    private AriaRole ariaRole(String role) {
        return switch (role.toLowerCase()) {
            case "button"   -> AriaRole.BUTTON;
            case "link"     -> AriaRole.LINK;
            case "checkbox" -> AriaRole.CHECKBOX;
            case "radio"    -> AriaRole.RADIO;
            case "textbox"  -> AriaRole.TEXTBOX;
            case "combobox" -> AriaRole.COMBOBOX;
            case "menuitem" -> AriaRole.MENUITEM;
            case "tab"      -> AriaRole.TAB;
            case "heading"  -> AriaRole.HEADING;
            default         -> AriaRole.BUTTON;
        };
    }

    // ── DB status helpers ─────────────────────────────────────────────────────

    private void updateFlowStatus(Long flowExecutionId, ExecutionStatus status, boolean finished) {
        if (flowExecutionId == null) return;
        flowExecutionRepository.findById(flowExecutionId).ifPresent(fe -> {
            fe.setStatus(status);
            if (finished) fe.setFinishedAt(LocalDateTime.now());
            flowExecutionRepository.save(fe);
            log.info("[PlaywrightExecutor] FlowExecution[{}] → {}", flowExecutionId, status);
        });
    }

    private void updateStepStatus(StepExecution se, ExecutionStatus status,
                                  String responseBody, Integer statusCode, Long durationMs) {
        if (se == null) return;
        se.setStatus(status);
        if (status == ExecutionStatus.IN_PROGRESS) {
            se.setStartedAt(LocalDateTime.now());
        } else {
            se.setFinishedAt(LocalDateTime.now());
            se.setSuccess(status == ExecutionStatus.PASS);
            if (statusCode != null) se.setStatusCode(statusCode);
            if (durationMs != null) se.setDurationMs(durationMs);
            if (responseBody != null) {
                if (status == ExecutionStatus.PASS) se.setResponseBody(responseBody);
                else se.setErrorMessage(responseBody);
            }
        }
        stepExecutionRepository.save(se);
    }

    private Map<Long, StepExecution> loadStepExecutions(Long flowExecutionId) {
        if (flowExecutionId == null) return Map.of();
        return stepExecutionRepository
                .findByFlowExecutionIdOrderByStepOrderAsc(flowExecutionId)
                .stream()
                .collect(Collectors.toMap(StepExecution::getStepId, se -> se));
    }

    private void markRemainingStepsFailed(List<FlowStep> steps, int executedCount,
                                          Map<Long, StepExecution> stepExecutionMap, String reason) {
        for (int i = executedCount; i < steps.size(); i++) {
            StepExecution se = stepExecutionMap.get(steps.get(i).getId());
            updateStepStatus(se, ExecutionStatus.FAIL, reason, 0, 0L);
        }
    }
}