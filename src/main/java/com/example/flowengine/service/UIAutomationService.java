package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import okhttp3.*;
import okhttp3.OkHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
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

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    final FlowService flowService;
    final FlowStepService flowStepService;
    final ObjectMapper objectMapper;
    final PlaywrightExecutorService playwrightExecutorService;
    final OkHttpClient okHttpClient;

    @Value("${ui.automation.headless:false}")
    boolean headless;

    @Value("${groq.api.key:}")
    String groqApiKey;
    @Value("${groq.model:llama-3.3-70b-versatile}")
    String groqModel;

    @Value("${groq.max-tokens:4000}")
    int groqMaxTokens;

    @Value("${llm.provider:ollama}")
    String llmProvider;

    @Value("${ollama.base-url:http://localhost:11434}")
    String ollamaBaseUrl;

    @Value("${ollama.model:llama3.1}")
    String ollamaModel;

    @Value("${ollama.num-predict:4096}")
    int ollamaNumPredict;

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
        log.info("[UIAutomation] scrapeCurrentPage → {} elements with a usable locator", elements.size());
        return elements;
    }

    public UIAutomationResult generateAutomation(UIAutomationRequest request) throws Exception {
        log.info("[UIAutomation] Starting for url={} module={}", request.getUrl(), request.getModuleName());
        log.info("[UIAutomation] LLM provider={} model={}", llmProvider,
                "ollama".equalsIgnoreCase(llmProvider) ? ollamaModel : groqModel);

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

        if (initialElements.isEmpty()) {
            log.warn("[UIAutomation] Phase 1 — SCRAPE_JS found 0 interactive elements on {}. " +
                    "Any locator the LLM returns for this page will be hallucinated.", page.url());
        }

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
        return resolveNullLocators(page, plan, request, depth, -1);
    }

    /**
     * @param lastExecutedClickIndex index of the last "click" step that has already been
     *                                executed in the CURRENT browser state, across recursive
     *                                calls. -1 means nothing executed yet (fresh browser/page).
     *                                Steps up to and including this index are NOT replayed —
     *                                the browser is already past them. Re-navigating to baseUrl
     *                                and replaying from step 0 on every recursion is what broke
     *                                multi-page flows: once authenticated, re-navigating to a
     *                                login URL can auto-redirect past the login form entirely,
     *                                so the "replayed" fill/click steps fail (field doesn't
     *                                exist) while the page still ends up in the right place by
     *                                accident — masking the bug until the app's redirect
     *                                behavior changes.
     */
    private List<AutomationStep> resolveNullLocators(Page page, List<AutomationStep> plan,
                                                     UIAutomationRequest request,
                                                     int depth, int lastExecutedClickIndex) throws Exception {
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

        if (clickIndex <= lastExecutedClickIndex) {
            // Browser is already past this point from a previous resolution loop — don't
            // re-navigate or replay. Re-navigating here is what caused the multi-page bug:
            // it can silently redirect past an already-completed login/step sequence.
            log.info("[UIAutomation] clickIndex {} already executed in this browser session — " +
                    "reusing current state, no replay", clickIndex + 1);
        } else {
            int replayFrom = lastExecutedClickIndex + 1;
            log.info("[UIAutomation] Executing steps {}-{} in browser to reach page with new elements",
                    replayFrom + 1, clickIndex + 1);

            if (lastExecutedClickIndex == -1) {
                // First time touching the browser for this flow — start from the real beginning.
                String baseUrl = plan.get(0).url != null ? plan.get(0).url : request.getUrl();
                page.navigate(baseUrl);
                try { page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(5000)); }
                catch (Exception ignored) {}
            }

            for (int i = replayFrom; i <= clickIndex; i++) {
                AutomationStep s = plan.get(i);
                if ("navigate".equalsIgnoreCase(s.action)) continue; // already navigated
                try {
                    playwrightExecutorService.dispatch(page, s);
                    try { page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(3000)); }
                    catch (Exception ignored) {}
                    if (s.waitAfterMs != null && s.waitAfterMs > 0) page.waitForTimeout(s.waitAfterMs);
                } catch (Exception e) {
                    log.warn("[UIAutomation] Step {} ('{}') failed during resolution — aborting replay, " +
                                    "remaining steps in this batch depend on it: {}",
                            i + 1, s.description, e.getMessage());
                    // Stop here instead of continuing to fill/click against a page that never
                    // reached the expected state — chaining onward just produces more failures
                    // and a misleading "it eventually landed on the right page" result.
                    break;
                }
            }
            lastExecutedClickIndex = clickIndex;
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
        return resolveNullLocators(page, merged, request, depth + 1, lastExecutedClickIndex);
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

        List<AutomationStep> steps = callAndParseAutomationSteps(prompt, "buildFullPlan");
        return enforceLocatorWhitelist(steps, elements, "buildFullPlan");
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

        String existingSteps = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stepsToFill);

        String prompt = """
                You are a Playwright test automation expert. The browser has navigated to a new page
                after a click. Fill in the NULL locators in the existing steps using the new page's elements.

                CURRENT PAGE URL: %s

                INTERACTIVE ELEMENTS ON THIS PAGE:
                %s

                UNRESOLVED USER INTENT (for context):
                %s

                STEPS TO COMPLETE (fill in NULL locators only, keep non-null ones unchanged):
                %s

                Return ONLY a JSON array with the same number of steps, same order.
                For steps with locator=NULL: assign the correct locator from the elements list above.
                For steps with existing locators: keep them exactly as-is.
                For assertVisible/assertText with locator=NULL: keep null, ensure value is set.
                Ensure every string value is valid JSON (double-quoted), including locator values like getByLabel(...).
                """.formatted(currentUrl, elementList, remainingNL, existingSteps);

        List<AutomationStep> steps = callAndParseAutomationSteps(prompt, "fillNullLocators");
        return enforceLocatorWhitelist(steps, elements, "fillNullLocators");
    }

    private boolean isAssertOrNavigate(String action) {
        if (action == null) return false;
        return switch (action.toLowerCase()) {
            case "assertvisible", "asserttext", "asserturl", "navigate",
                 "waitforurl", "screenshot" -> true;
            default -> false;
        };
    }

    /**
     * Guards against LLM hallucination. The prompt tells the model to only use locators
     * from the scraped element list, but that's not enforced by anything — small/local
     * models in particular will happily invent a plausible-looking locator
     * (e.g. getByRole("input", { name: "Input field" })) when nothing in the list is a
     * clean match. "input" isn't even a valid ARIA role, which is a giveaway it was
     * fabricated rather than copied.
     *
     * Any non-null locator that doesn't exactly match a bestLocator we actually scraped
     * is discarded (set to null) so it falls through to resolveNullLocators instead of
     * being baked into the generated script and failing at runtime with a 30s timeout.
     */
    private List<AutomationStep> enforceLocatorWhitelist(List<AutomationStep> steps,
                                                         List<UIAutomationResult.ExtractedElement> elements,
                                                         String phase) {
        Set<String> validLocators = new HashSet<>();
        for (UIAutomationResult.ExtractedElement e : elements) {
            if (e.getBestLocator() != null && !e.getBestLocator().isBlank()) {
                validLocators.add(e.getBestLocator());
            }
        }

        int invented = 0;
        for (AutomationStep s : steps) {
            if (s.locator == null || s.locator.isBlank()) continue;
            if (isAssertOrNavigate(s.action)) continue; // these legitimately don't need a scraped locator

            if (!validLocators.contains(s.locator)) {
                log.warn("[UIAutomation][{}] Discarding hallucinated locator — not present in scraped elements. " +
                                "step='{}' action={} invented_locator={}",
                        phase, s.description, s.action, s.locator);
                s.locator = null;
                invented++;
            }
        }

        if (invented > 0) {
            log.warn("[UIAutomation][{}] {}/{} step(s) had locators not found in the scraped element list — " +
                            "reset to null so resolveNullLocators can re-resolve them against real page state",
                    phase, invented, steps.size());
        } else {
            log.info("[UIAutomation][{}] All {} non-null locators validated against scraped elements", phase, steps.size());
        }

        return steps;
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

    // ── LLM dispatch ──────────────────────────────────────────────────────────
    String callLLM(String prompt) throws Exception {
        if ("ollama".equalsIgnoreCase(llmProvider)) {
            return callOllama(prompt);
        }
        return callGroq(prompt);
    }

    String callOllama(String prompt) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of(
                        "model", ollamaModel,
                        "prompt", prompt,
                        "stream", false,
                        "options", Map.of("num_predict", ollamaNumPredict)
                ));
        Request req = new Request.Builder()
                .url(ollamaBaseUrl + "/api/generate")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Ollama error: HTTP " + response.code());
            String content = objectMapper.readTree(response.body().string()).path("response").asText();

            // Normalize common LLM wrappers and keep only the JSON payload.
            content = extractJsonPayload(content);
            log.info("[UIAutomation] Ollama response: {}", content.substring(0, Math.min(200, content.length())));
            return content;
        }
    }

    // ── Groq API call ─────────────────────────────────────────────────────────
    String callGroq(String prompt) throws Exception {
        if (groqApiKey == null || groqApiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY not set — cannot generate automation steps.");
        }

        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", groqModel,
                "temperature", 0.1,
                "max_tokens", groqMaxTokens,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        ));

        Request req = new Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + groqApiKey)
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = okHttpClient.newCall(req).execute()) {
            if (!response.isSuccessful()) throw new RuntimeException("Groq error: HTTP " + response.code() + ": " + response.body().string());

            var respNode = objectMapper.readTree(response.body().string());
            String content = respNode.at("/choices/0/message/content").asText();

            // Normalize common LLM wrappers and keep only the JSON payload.
            content = extractJsonPayload(content);

            log.info("[UIAutomation] LLM response: {}", content.substring(0, Math.min(200, content.length())));
            return content;
        }
    }

    private String extractJsonPayload(String raw) {
        if (raw == null) return "";

        String content = raw
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        int objectStart = content.indexOf('{');
        int arrayStart = content.indexOf('[');
        int start = firstNonNegativeMin(objectStart, arrayStart);
        if (start < 0) {
            return content;
        }

        char open = content.charAt(start);
        char close = (open == '{') ? '}' : ']';
        int end = findMatchingJsonEnd(content, start, open, close);
        if (end < 0) {
            return content.substring(start).trim();
        }
        return content.substring(start, end + 1).trim();
    }

    private List<AutomationStep> parseAutomationSteps(String responseJson, String phase) throws Exception {
        try {
            return objectMapper.readValue(responseJson, new TypeReference<>() {});
        } catch (Exception first) {
            String repaired = repairJsonLikeSteps(responseJson);
            if (!repaired.equals(responseJson)) {
                log.warn("[UIAutomation] {} returned non-strict JSON, applying repair", phase);
                return objectMapper.readValue(repaired, new TypeReference<>() {});
            }
            throw first;
        }
    }

    private List<AutomationStep> callAndParseAutomationSteps(String prompt, String phase) throws Exception {
        Exception lastError = null;
        String responseJson = "";

        for (int attempt = 1; attempt <= 2; attempt++) {
            String effectivePrompt = attempt == 1
                    ? prompt
                    : prompt + """

                    IMPORTANT: Your previous response was truncated.
                    Return the FULL JSON array from start to end in a single response.
                    Do not omit any step. Do not add explanation.
                    """;

            responseJson = callLLM(effectivePrompt);
            try {
                return parseAutomationSteps(responseJson, phase + "#attempt" + attempt);
            } catch (Exception e) {
                lastError = e;
                if (attempt == 2 || !isLikelyTruncatedJson(e, responseJson)) {
                    throw e;
                }
                log.warn("[UIAutomation] {} response looks truncated, retrying once", phase);
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Failed to parse automation steps for " + phase);
    }

    private boolean isLikelyTruncatedJson(Exception error, String payload) {
        String msg = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        String text = payload == null ? "" : payload.trim();

        boolean parserHintsTruncation = msg.contains("unexpected end-of-input")
                || msg.contains("unexpected end")
                || msg.contains("end-of-input")
                || msg.contains("was expecting") && msg.contains("end");

        boolean endsAbruptly = (text.startsWith("[") && !text.endsWith("]"))
                || (text.startsWith("{") && !text.endsWith("}"));

        return parserHintsTruncation || endsAbruptly;
    }

    private String repairJsonLikeSteps(String jsonish) {
        if (jsonish == null || jsonish.isBlank()) return jsonish;
        String repaired = jsonish;
        repaired = quoteUnquotedStringFieldValues(repaired, "action");
        repaired = quoteUnquotedStringFieldValues(repaired, "locator");
        repaired = quoteUnquotedStringFieldValues(repaired, "value");
        repaired = quoteUnquotedStringFieldValues(repaired, "url");
        repaired = quoteUnquotedStringFieldValues(repaired, "description");
        return repaired;
    }

    private String quoteUnquotedStringFieldValues(String input, String fieldName) {
        String keyToken = "\"" + fieldName + "\"";
        StringBuilder sb = new StringBuilder(input);
        int from = 0;

        while (from < sb.length()) {
            int keyStart = sb.indexOf(keyToken, from);
            if (keyStart < 0) break;

            int colon = indexOfColon(sb, keyStart + keyToken.length());
            if (colon < 0) break;

            int valueStart = skipWhitespace(sb, colon + 1);
            if (valueStart >= sb.length()) break;

            char ch = sb.charAt(valueStart);
            if (ch == '"' || ch == '{' || ch == '[' || ch == '-' || Character.isDigit(ch)) {
                from = valueStart + 1;
                continue;
            }
            if (startsWithKeyword(sb, valueStart, "true") ||
                    startsWithKeyword(sb, valueStart, "false") ||
                    startsWithKeyword(sb, valueStart, "null")) {
                from = valueStart + 1;
                continue;
            }

            int valueEnd = scanBareValueEnd(sb, valueStart);
            String raw = sb.substring(valueStart, valueEnd).trim();
            if (raw.isEmpty()) {
                from = valueEnd + 1;
                continue;
            }

            String replacement;
            if ("null".equalsIgnoreCase(raw)) {
                replacement = "null";
            } else {
                try {
                    replacement = objectMapper.writeValueAsString(raw);
                } catch (Exception e) {
                    replacement = "\"" + esc(raw) + "\"";
                }
            }

            sb.replace(valueStart, valueEnd, replacement);
            from = valueStart + replacement.length();
        }
        return sb.toString();
    }

    private int indexOfColon(StringBuilder sb, int from) {
        for (int i = from; i < sb.length(); i++) {
            char ch = sb.charAt(i);
            if (ch == ':') return i;
            if (!Character.isWhitespace(ch)) return -1;
        }
        return -1;
    }

    private int skipWhitespace(StringBuilder sb, int from) {
        int i = from;
        while (i < sb.length() && Character.isWhitespace(sb.charAt(i))) i++;
        return i;
    }

    private boolean startsWithKeyword(StringBuilder sb, int from, String keyword) {
        if (from + keyword.length() > sb.length()) return false;
        for (int i = 0; i < keyword.length(); i++) {
            if (Character.toLowerCase(sb.charAt(from + i)) != keyword.charAt(i)) return false;
        }
        int end = from + keyword.length();
        return end >= sb.length() || !Character.isLetterOrDigit(sb.charAt(end));
    }

    private int scanBareValueEnd(StringBuilder sb, int from) {
        int parenDepth = 0;
        int braceDepth = 0;
        int bracketDepth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = from; i < sb.length(); i++) {
            char ch = sb.charAt(i);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }

            if (ch == '(') parenDepth++;
            else if (ch == ')') parenDepth = Math.max(0, parenDepth - 1);
            else if (ch == '{') braceDepth++;
            else if (ch == '}') {
                if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) return i;
                braceDepth = Math.max(0, braceDepth - 1);
            } else if (ch == '[') bracketDepth++;
            else if (ch == ']') {
                if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) return i;
                bracketDepth = Math.max(0, bracketDepth - 1);
            } else if (ch == ',' && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                return i;
            }
        }
        return sb.length();
    }

    private int firstNonNegativeMin(int a, int b) {
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private int findMatchingJsonEnd(String content, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }

            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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