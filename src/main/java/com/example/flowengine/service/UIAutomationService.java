package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates Playwright UI automation flows from natural language.
 *
 * Flow:
 *  1. Launch Playwright headless Chromium, navigate to URL
 *  2. Extract all interactive elements with best locators
 *  3. Send element map + user's NL steps to Groq LLM
 *  4. LLM returns structured step list (action + locator + value)
 *  5. Generate a Playwright Java test and save as flow steps
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UIAutomationService {

    final FlowService flowService;
    final FlowStepService flowStepService;
    final ObjectMapper objectMapper;
    final PlaywrightExecutorService playwrightExecutorService;

    @Value("${ui.automation.headless:false}")
    boolean headless;

    @Value("${groq.api.key:}")
    String groqApiKey;
    @Value("${groq.model:llama-3.3-70b-versatile}")
    String groqModel;

    /** Injected JS that walks the DOM and returns interactive elements with best locators. */
    static final String SCRAPE_JS = """
            () => {
              const results = [];
              const selectors = [
                'button', 'input', 'select', 'textarea', 'a[href]',
                '[role="button"]', '[role="link"]', '[role="checkbox"]',
                '[role="radio"]', '[role="combobox"]', '[role="textbox"]',
                '[role="menuitem"]', '[tabindex]:not([tabindex="-1"])'
              ];
              const seen = new Set();
              document.querySelectorAll(selectors.join(',')).forEach(el => {
                if (!el.offsetParent && el.tagName !== 'BODY') return;
                const rect = el.getBoundingClientRect();
                if (rect.width === 0 || rect.height === 0) return;

                const tag        = el.tagName.toLowerCase();
                const role       = el.getAttribute('role') || el.tagName.toLowerCase();
                const testId     = el.getAttribute('data-testid') || el.getAttribute('data-test') || el.getAttribute('data-cy') || '';
                const id         = el.id || '';
                const name       = el.getAttribute('name') || '';
                const type       = el.getAttribute('type') || '';
                const placeholder= el.getAttribute('placeholder') || '';
                const ariaLabel  = el.getAttribute('aria-label') || '';
                const text       = (el.innerText || el.value || el.textContent || '').trim().slice(0, 80);
                const labelEl    = id ? document.querySelector('label[for="'+id+'"]') : null;
                const labelText  = labelEl ? labelEl.innerText.trim() : '';

                let bestLocator = '';
                let bestLocatorType = '';
                if (testId) {
                  bestLocator = 'getByTestId("' + testId + '")';
                  bestLocatorType = 'testId';
                } else if (ariaLabel) {
                  bestLocator = 'getByRole("' + role + '", { name: "' + ariaLabel + '" })';
                  bestLocatorType = 'role+aria';
                } else if (labelText) {
                  bestLocator = 'getByLabel("' + labelText + '")';
                  bestLocatorType = 'label';
                } else if (placeholder) {
                  bestLocator = 'getByPlaceholder("' + placeholder + '")';
                  bestLocatorType = 'placeholder';
                } else if (text && (tag === 'button' || tag === 'a' || role === 'button' || role === 'link')) {
                  const roleName = (tag === 'button' || role === 'button') ? 'button' : 'link';
                  bestLocator = 'getByRole("' + roleName + '", { name: "' + text.trim() + '" })';
                  bestLocatorType = 'role+text';
                } else if (id) {
                  bestLocator = 'locator("#' + id + '")';
                  bestLocatorType = 'id';
                } else if (name) {
                  bestLocator = 'locator("[name=\\'' + name + '\\']")';
                  bestLocatorType = 'name';
                }

                if (!bestLocator) return;
                if (seen.has(bestLocator)) return;
                seen.add(bestLocator);

                const desc = (ariaLabel || labelText || placeholder || text || name || id || tag).slice(0, 60);

                results.push({
                  tag, role, label: labelText, placeholder, testId,
                  ariaLabel, id, name, type, text: text.slice(0, 40),
                  bestLocator, bestLocatorType,
                  description: desc + (type ? ' (' + type + ')' : '')
                });
              });
              return JSON.stringify(results);
            }
            """;

    /**
     * Scrapes interactive elements from the page's CURRENT state.
     * Callable repeatedly on the same Page after navigation/clicks for iterative generation.
     */
    @SuppressWarnings("unchecked")
    List<UIAutomationResult.ExtractedElement> scrapeCurrentPage(Page page) {
        List<UIAutomationResult.ExtractedElement> elements = new ArrayList<>();
        try {
            String json = (String) page.evaluate(SCRAPE_JS);
            List<Map<String, Object>> raw = objectMapper.readValue(json, new TypeReference<>() {});
            for (Map<String, Object> m : raw) {
                UIAutomationResult.ExtractedElement el = new UIAutomationResult.ExtractedElement();
                el.setTag((String) m.getOrDefault("tag", ""));
                el.setRole((String) m.getOrDefault("role", ""));
                el.setLabel((String) m.getOrDefault("label", ""));
                el.setPlaceholder((String) m.getOrDefault("placeholder", ""));
                el.setTestId((String) m.getOrDefault("testId", ""));
                el.setBestLocator((String) m.getOrDefault("bestLocator", ""));
                el.setDescription((String) m.getOrDefault("description", ""));
                elements.add(el);
            }
        } catch (Exception e) {
            log.warn("[UIAutomation] scrapeCurrentPage failed: {}", e.getMessage());
        }
        return elements;
    }

    public UIAutomationResult generateAutomation(UIAutomationRequest request) throws Exception {
        log.info("[UIAutomation] Starting for url={} module={}", request.getUrl(), request.getModuleName());

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(headless);
            try (Browser browser = playwright.chromium().launch(opts)) {
                BrowserContext ctx = browser.newContext(
                        new Browser.NewContextOptions()
                                .setViewportSize(1280, 800)
                                .setIgnoreHTTPSErrors(true)); // bypass VPN/corporate proxy cert interception

                applyAuth(ctx, request);
                Page page = ctx.newPage();

                log.info("[UIAutomation] Navigating to {}", request.getUrl());
                page.navigate(request.getUrl());
                page.waitForLoadState();
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

                // Scrape-only mode — single snapshot, no LLM
                if ("__SCRAPE_ONLY__".equals(request.getSteps())) {
                    List<UIAutomationResult.ExtractedElement> elements = scrapeCurrentPage(page);
                    UIAutomationResult result = new UIAutomationResult();
                    result.setFlowName(request.getFlowName());
                    result.setModuleName(request.getModuleName());
                    result.setStepCount(0);
                    result.setExtractedElements(elements);
                    result.setSummary(String.format("Found %d interactive elements on %s",
                            elements.size(), request.getUrl()));
                    return result;
                }

                return runIterativeLoop(page, request);
            }
        }
    }

    /**
     * Core loop: scrape current page → ask LLM for ONE next step (or DONE) → execute it → repeat.
     * This handles multi-page flows correctly because each LLM call sees the page
     * AFTER previous actions (including navigations) have taken effect.
     */
    private static final int MAX_RESOLUTION_LOOPS = 5;

    /**
     * Two-phase flow generation:
     *
     * Phase 1 — PLAN: Scrape initial page, send ALL user steps + elements to LLM in one shot.
     *           LLM returns a complete plan. Some steps may have locator=null for elements
     *           that don't exist on the initial page (they appear after navigation/clicks).
     *
     * Phase 2 — RESOLVE: Agent scans for null locators. When found, it finds the nearest
     *           preceding CLICK step (clicks trigger DOM/page changes), executes the browser
     *           up to and including that click, scrapes the resulting page, and asks LLM to
     *           fill in only the unresolved steps with the new elements. Repeats until all
     *           locators are resolved or max loops hit.
     */
    private UIAutomationResult runIterativeLoop(Page page, UIAutomationRequest request) throws Exception {

        // ── Phase 1: Initial scrape + full plan from LLM ────────────────────
        List<UIAutomationResult.ExtractedElement> initialElements = scrapeCurrentPage(page);
        log.info("[UIAutomation] Phase 1 — scraped {} elements from {}", initialElements.size(), page.url());

        List<AutomationStep> plan = buildFullPlan(request.getSteps(), initialElements, page.url());
        log.info("[UIAutomation] Phase 1 — LLM returned {} steps, checking for nulls", plan.size());

        // ── Phase 2: Resolve null locators via click-triggered scraping ──────
        plan = resolveNullLocators(page, plan, request, 0);

        int nullCount = (int) plan.stream()
                .filter(s -> s.locator == null &&
                        !isAssertOrNavigate(s.action))
                .count();
        if (nullCount > 0) {
            log.warn("[UIAutomation] {} step(s) still have null locators after resolution — user can fix in UI",
                    nullCount);
        }

        // ── Save as flow ─────────────────────────────────────────────────────
        if (plan.isEmpty()) {
            throw new IllegalStateException(
                    "No steps were generated. Try being more specific about element names.");
        }

        String script = generatePlaywrightScript(request.getUrl(), plan, request.getFlowName());

        FlowRequest flowReq = new FlowRequest();
        flowReq.setName(request.getFlowName());
        flowReq.setModule(request.getModuleName());
        flowReq.setFlowType("UI");
        flowReq.setPlaywrightScript(script);
        flowReq.setDescription("UI_AUTOMATION:" + request.getUrl());
        FlowDTO flow = flowService.create(flowReq);

        for (int i = 0; i < plan.size(); i++) {
            AutomationStep s = plan.get(i);
            FlowStepRequest stepReq = new FlowStepRequest();
            stepReq.setName(s.description != null ? s.description : s.action + " step " + (i + 1));
            stepReq.setMethod("UI");
            stepReq.setUrl(request.getUrl());
            stepReq.setDescription(s.description);
            stepReq.setBodyJson(objectMapper.writeValueAsString(s));
            flowStepService.create(flow.getId(), stepReq);
        }

        long pages = plan.stream().filter(s -> "navigate".equalsIgnoreCase(s.action)).count() + 1;
        long resolved = plan.stream().filter(s -> s.locator != null || isAssertOrNavigate(s.action)).count();

        UIAutomationResult result = new UIAutomationResult();
        result.setFlowId(flow.getId());
        result.setFlowName(flow.getName());
        result.setModuleName(request.getModuleName());
        result.setStepCount(plan.size());
        result.setPlaywrightScript(script);
        result.setExtractedElements(initialElements);
        result.setSummary(String.format(
                "Generated %d steps across %d page(s). %d/%d locators resolved.%s",
                plan.size(), pages, resolved, plan.size(),
                nullCount > 0 ? " " + nullCount + " step(s) need manual locator fix." : ""));
        return result;
    }

    /**
     * Recursively resolves null locators.
     * Finds the first null-locator step, locates the nearest preceding click,
     * executes up to that click in the browser, scrapes the new page,
     * and asks the LLM to fill only the unresolved steps.
     */
    private List<AutomationStep> resolveNullLocators(Page page, List<AutomationStep> plan,
                                                     UIAutomationRequest request,
                                                     int depth) throws Exception {
        if (depth >= MAX_RESOLUTION_LOOPS) {
            log.warn("[UIAutomation] Max resolution loops ({}) reached", MAX_RESOLUTION_LOOPS);
            return plan;
        }

        // Find first step with null locator that actually needs one
        int firstNull = -1;
        for (int i = 0; i < plan.size(); i++) {
            AutomationStep s = plan.get(i);
            if (s.locator == null && !isAssertOrNavigate(s.action)) {
                firstNull = i;
                break;
            }
        }

        if (firstNull == -1) {
            log.info("[UIAutomation] All locators resolved.");
            return plan; // nothing to fix
        }

        log.info("[UIAutomation] Null locator at step {} '{}' — looking for preceding click",
                firstNull + 1, plan.get(firstNull).description);

        // Find the nearest click BEFORE the null-locator step
        int clickIndex = -1;
        for (int i = firstNull - 1; i >= 0; i--) {
            if ("click".equalsIgnoreCase(plan.get(i).action)) {
                clickIndex = i;
                break;
            }
        }

        if (clickIndex == -1) {
            log.warn("[UIAutomation] No preceding click found before null step {} — cannot resolve", firstNull + 1);
            return plan; // nothing we can do
        }

        log.info("[UIAutomation] Executing steps 1-{} in browser to reach page with new elements",
                clickIndex + 1);

        // Execute steps 0..clickIndex in the browser
        String baseUrl = plan.get(0).url != null ? plan.get(0).url : request.getUrl();
        page.navigate(baseUrl);
        try { page.waitForLoadState(LoadState.NETWORKIDLE,
                new Page.WaitForLoadStateOptions().setTimeout(5000)); }
        catch (Exception ignored) {}

        for (int i = 0; i <= clickIndex; i++) {
            AutomationStep s = plan.get(i);
            if ("navigate".equalsIgnoreCase(s.action)) continue; // already navigated
            try {
                playwrightExecutorService.dispatch(page, s);
                try { page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(3000)); }
                catch (Exception ignored) {}
                if (s.waitAfterMs != null && s.waitAfterMs > 0) page.waitForTimeout(s.waitAfterMs);
            } catch (Exception e) {
                log.warn("[UIAutomation] Step {} failed during resolution: {}", i + 1, e.getMessage());
            }
        }

        // Scrape the resulting page
        List<UIAutomationResult.ExtractedElement> newElements = scrapeCurrentPage(page);
        log.info("[UIAutomation] Resolution loop {} — scraped {} elements from {}",
                depth + 1, newElements.size(), page.url());

        if (newElements.isEmpty()) {
            log.warn("[UIAutomation] No elements found after executing to click — giving up resolution");
            return plan;
        }

        // Extract just the unresolved steps (from firstNull onwards)
        List<AutomationStep> unresolvedSteps = plan.subList(firstNull, plan.size());
        StringBuilder unresolvedNL = new StringBuilder();
        unresolvedSteps.forEach(s -> unresolvedNL.append("- ").append(s.description).append("\n"));

        // Ask LLM to fill in locators for the unresolved steps using the new elements
        List<AutomationStep> resolvedTail = fillNullLocators(
                unresolvedNL.toString(), newElements, page.url(), unresolvedSteps);

        // Merge: keep already-resolved head, replace tail with newly resolved
        List<AutomationStep> merged = new ArrayList<>(plan.subList(0, firstNull));
        merged.addAll(resolvedTail);

        // Recurse in case there are more nulls further down
        return resolveNullLocators(page, merged, request, depth + 1);
    }

    /**
     * Phase 1 LLM call — generates the full plan in one shot from the initial page elements.
     * Some locators may be null if elements don't exist on the initial page.
     */
    private List<AutomationStep> buildFullPlan(String nlSteps,
                                               List<UIAutomationResult.ExtractedElement> elements,
                                               String currentUrl) throws Exception {
        StringBuilder elementList = new StringBuilder();
        elements.stream().limit(80).forEach(e ->
                elementList.append("  - ").append(e.getDescription())
                        .append(" → ").append(e.getBestLocator()).append("\n"));

        String prompt = """
                You are a Playwright test automation expert. Given interactive elements on a web page
                and natural language steps to automate, return a COMPLETE step-by-step plan as a JSON array.

                PAGE URL: %s

                INTERACTIVE ELEMENTS ON THIS PAGE:
                %s

                USER'S STEPS TO AUTOMATE:
                %s

                Return ONLY a JSON array (no markdown, no explanation). Each item:
                {
                  "action": navigate|click|fill|select|check|uncheck|hover|waitForSelector|assertVisible|assertText|assertURL,
                  "locator": exact bestLocator from elements above, or NULL if element not on this page yet,
                  "value": text to type/select, or expected text for assertions, or null,
                  "url": target URL for navigate only, or null,
                  "description": short human-readable description,
                  "waitAfterMs": ms to wait after this step, 0 if not needed
                }

                CRITICAL RULES:
                - Include ALL steps, even for elements not yet visible. Set locator=null for those.
                - For elements on THIS page, always use a locator from the list above — never invent one.
                - Clicks on buttons/links likely cause page changes — subsequent steps may need locator=null.
                - For assertVisible/assertText after navigation, set locator=null and value=expected text.
                - For assertURL, set locator=null and value=expected URL fragment.
                - Prefer getByRole over getByText for buttons/links.
                """.formatted(currentUrl, elementList, nlSteps);

        String responseJson = callGroq(prompt);
        return objectMapper.readValue(responseJson, new TypeReference<>() {});
    }

    /**
     * Phase 2 LLM call — fills in null locators for a subset of steps using newly scraped elements.
     * Only asked to fix steps that still have null locators.
     */
    private List<AutomationStep> fillNullLocators(String remainingNL,
                                                  List<UIAutomationResult.ExtractedElement> elements,
                                                  String currentUrl,
                                                  List<AutomationStep> stepsToFill) throws Exception {
        StringBuilder elementList = new StringBuilder();
        elements.stream().limit(80).forEach(e ->
                elementList.append("  - ").append(e.getDescription())
                        .append(" → ").append(e.getBestLocator()).append("\n"));

        StringBuilder existingSteps = new StringBuilder();
        stepsToFill.forEach(s -> existingSteps.append(String.format(
                "  {action: %s, locator: %s, value: %s, description: %s}\n",
                s.action, s.locator == null ? "NULL — NEEDS FILLING" : s.locator,
                s.value, s.description)));

        String prompt = """
                You are a Playwright test automation expert. The browser has navigated to a new page
                after a click. Fill in the NULL locators in the existing steps using the new page's elements.

                CURRENT PAGE URL: %s

                INTERACTIVE ELEMENTS ON THIS PAGE:
                %s

                STEPS TO COMPLETE (fill in NULL locators only, keep non-null ones unchanged):
                %s

                Return ONLY a JSON array with the same number of steps, same order.
                For steps with locator=NULL: assign the correct locator from the elements list above.
                For steps with existing locators: keep them exactly as-is.
                For assertVisible/assertText with locator=NULL: keep null, ensure value is set.
                """.formatted(currentUrl, elementList, existingSteps);

        String responseJson = callGroq(prompt);
        return objectMapper.readValue(responseJson, new TypeReference<>() {});
    }

    private boolean isAssertOrNavigate(String action) {
        if (action == null) return false;
        return switch (action.toLowerCase()) {
            case "assertvisible", "asserttext", "asserturl", "navigate",
                 "waitforurl", "screenshot" -> true;
            default -> false;
        };
    }

    private void applyAuth(BrowserContext ctx, UIAutomationRequest request) {
        if (request.getCookiesJson() != null && !request.getCookiesJson().isBlank()) {
            try {
                List<Map<String, Object>> cookieList = objectMapper.readValue(
                        request.getCookiesJson(), new TypeReference<>() {});
                List<Cookie> playwrightCookies = new ArrayList<>();
                for (Map<String, Object> c : cookieList) {
                    Cookie cookie = new Cookie((String) c.get("name"), (String) c.get("value"));
                    if (c.containsKey("domain")) cookie.domain = (String) c.get("domain");
                    if (c.containsKey("path"))   cookie.path   = (String) c.get("path");
                    playwrightCookies.add(cookie);
                }
                ctx.addCookies(playwrightCookies);
                log.info("[UIAutomation] Added {} auth cookies", playwrightCookies.size());
            } catch (Exception e) {
                log.warn("[UIAutomation] Failed to parse cookies: {}", e.getMessage());
            }
        }
    }

    // ── Groq API call ─────────────────────────────────────────────────────────
    String callGroq(String prompt) throws Exception {
        if (groqApiKey == null || groqApiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY not set — cannot generate automation steps.");
        }

        var requestBody = objectMapper.writeValueAsString(Map.of(
                "model", groqModel,
                "temperature", 0.1,
                "max_tokens", 2000,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        var http = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqApiKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Groq API error " + resp.statusCode() + ": " + resp.body());
        }

        var respNode = objectMapper.readTree(resp.body());
        String content = respNode.at("/choices/0/message/content").asText();

        // Strip markdown fences if model wrapped in them anyway
        content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        log.info("[UIAutomation] LLM response: {}", content.substring(0, Math.min(200, content.length())));
        return content;
    }

    // ── Playwright script generation ──────────────────────────────────────────

    String generatePlaywrightScript(String url, List<AutomationStep> steps, String flowName) {
        String className = flowName.replaceAll("[^a-zA-Z0-9]", "") + "Test";
        StringBuilder sb = new StringBuilder();
        sb.append("import com.microsoft.playwright.*;\n");
        sb.append("import com.microsoft.playwright.options.*;\n");
        sb.append("import org.junit.jupiter.api.*;\n\n");
        sb.append("public class ").append(className).append(" {\n\n");
        sb.append("    @Test\n");
        sb.append("    public void run() {\n");
        sb.append("        try (Playwright playwright = Playwright.create()) {\n");
        sb.append("            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));\n");
        sb.append("            Page page = browser.newContext().newPage();\n\n");
        sb.append("            // Navigate to page\n");
        sb.append("            page.navigate(\"").append(url).append("\");\n\n");

        for (AutomationStep step : steps) {
            sb.append("            // ").append(step.description).append("\n");
            switch (step.action.toLowerCase()) {
                case "navigate" ->
                        sb.append("            page.navigate(\"").append(step.url).append("\");\n");
                case "click" ->
                        sb.append("            page.").append(step.locator).append(".click();\n");
                case "fill" ->
                        sb.append("            page.").append(step.locator)
                                .append(".fill(\"").append(esc(step.value)).append("\");\n");
                case "select" ->
                        sb.append("            page.").append(step.locator)
                                .append(".selectOption(\"").append(esc(step.value)).append("\");\n");
                case "check" ->
                        sb.append("            page.").append(step.locator).append(".check();\n");
                case "uncheck" ->
                        sb.append("            page.").append(step.locator).append(".uncheck();\n");
                case "hover" ->
                        sb.append("            page.").append(step.locator).append(".hover();\n");
                case "waitforselector" ->
                        sb.append("            page.waitForSelector(\"").append(esc(step.locator)).append("\");\n");
                case "waitforurl" ->
                        sb.append("            page.waitForURL(\"").append(esc(step.url != null ? step.url : step.value)).append("\");\n");
                case "screenshot" ->
                        sb.append("            page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get(\"screenshot.png\")));\n");
                case "assertvisible" ->
                        sb.append("            Assertions.assertTrue(page.").append(step.locator).append(".isVisible());\n");
                case "asserttext" ->
                        sb.append("            Assertions.assertEquals(\"").append(esc(step.value))
                                .append("\", page.").append(step.locator).append(".innerText());\n");
                case "asserturl" ->
                        sb.append("            Assertions.assertTrue(page.url().contains(\"").append(esc(step.value)).append("\"));\n");
                default ->
                        sb.append("            // Unknown action: ").append(step.action).append("\n");
            }
            if (step.waitAfterMs != null && step.waitAfterMs > 0) {
                sb.append("            page.waitForTimeout(").append(step.waitAfterMs).append(");\n");
            }
            sb.append("\n");
        }

        sb.append("            browser.close();\n");
        sb.append("        }\n");
        sb.append("    }\n}\n");
        return sb.toString();
    }

    private String buildStepDescription(AutomationStep step) {
        return String.format("action=%s locator=%s value=%s",
                step.action,
                step.locator != null ? step.locator : "-",
                step.value != null ? step.value : "-");
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Internal DTO for LLM-parsed step ─────────────────────────────────────

    public static class AutomationStep {
        public String action;
        public String locator;
        public String value;
        public String url;
        public String description;
        public Integer waitAfterMs;
    }
}