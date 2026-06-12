package com.example.flowengine.service;

import com.example.flowengine.DTO.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
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

    private final FlowService flowService;
    private final FlowStepService flowStepService;
    private final ObjectMapper objectMapper;

    @Value("${ui.automation.headless:false}")
    private boolean headless;

    @Value("${llm.provider:ollama}")
    private String llmProvider;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:qwen2.5-coder:1.5b-base}")
    private String ollamaModel;

    @Value("${groq.api.key:}")
    private String groqApiKey;
    @Value("${groq.model:llama-3.3-70b-versatile}")
    private String groqModel;

    // ── Public entry point ────────────────────────────────────────────────────

    public UIAutomationResult generateAutomation(UIAutomationRequest request) throws Exception {
        log.info("[UIAutomation] Starting for url={} module={}", request.getUrl(), request.getModuleName());

        // 1. Scrape the page
        List<UIAutomationResult.ExtractedElement> elements = scrapeElements(request);
        log.info("[UIAutomation] Extracted {} interactive elements", elements.size());

        // Scrape-only mode — skip LLM and flow creation
        if ("__SCRAPE_ONLY__".equals(request.getSteps())) {
            UIAutomationResult result = new UIAutomationResult();
            result.setFlowName(request.getFlowName());
            result.setModuleName(request.getModuleName());
            result.setStepCount(0);
            result.setExtractedElements(elements);
            result.setSummary(String.format("Found %d interactive elements on %s",
                    elements.size(), request.getUrl()));
            return result;
        }

        // 2. Ask LLM to map NL steps to locators
        List<AutomationStep> steps = mapStepsViaLLM(request.getSteps(), elements, request.getUrl());
        log.info("[UIAutomation] LLM mapped {} steps", steps.size());

        // 3. Generate Playwright script
        String script = generatePlaywrightScript(request.getUrl(), steps, request.getFlowName());

        // 4. Save as flow — type=UI, script persisted
        FlowRequest flowReq = new FlowRequest();
        flowReq.setName(request.getFlowName());
        flowReq.setModule(request.getModuleName());
        flowReq.setFlowType("UI");
        flowReq.setPlaywrightScript(script);
        flowReq.setDescription("UI_AUTOMATION:" + request.getUrl());
        flowReq.setFlowType("UI");
        flowReq.setPlaywrightScript(script);
        FlowDTO flow = flowService.create(flowReq);

        // Each step becomes a UI flow step.
        // Structured step data is stored as JSON in bodyJson so the executor can read it back.
        for (int i = 0; i < steps.size(); i++) {
            AutomationStep s = steps.get(i);
            FlowStepRequest stepReq = new FlowStepRequest();
            stepReq.setName(s.description != null ? s.description : s.action + " step " + (i + 1));
            stepReq.setMethod("UI");
            stepReq.setUrl(request.getUrl());
            stepReq.setDescription(s.description);
            // Store the full step as JSON in bodyJson for the Playwright executor to read back
            stepReq.setBodyJson(objectMapper.writeValueAsString(s));
            flowStepService.create(flow.getId(), stepReq);
        }

          UIAutomationResult result = new UIAutomationResult();
        result.setFlowId(flow.getId());
        result.setFlowName(flow.getName());
        result.setModuleName(request.getModuleName());
        result.setStepCount(steps.size());
        result.setPlaywrightScript(script);
        result.setExtractedElements(elements);
        result.setSummary(String.format(
                "Generated %d automation steps for '%s'. Playwright script ready to run.",
                steps.size(), request.getUrl()));

        return result;
    }

    // ── Playwright scraping ───────────────────────────────────────────────────

    private List<UIAutomationResult.ExtractedElement> scrapeElements(UIAutomationRequest request) {
        List<UIAutomationResult.ExtractedElement> elements = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            BrowserType.LaunchOptions opts = new BrowserType.LaunchOptions().setHeadless(headless);
            try (Browser browser = playwright.chromium().launch(opts)) {
                BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                        .setIgnoreHTTPSErrors(true));
                // Handle auth cookies if provided
                if (request.getCookiesJson() != null && !request.getCookiesJson().isBlank()) {
                    try {
                        List<Map<String, Object>> cookieList = objectMapper.readValue(
                                request.getCookiesJson(), new TypeReference<>() {});
                        List<Cookie> playwrightCookies = new ArrayList<>();
                        for (Map<String, Object> c : cookieList) {
                            Cookie cookie = new Cookie(
                                    (String) c.get("name"),
                                    (String) c.get("value"));
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

                Page page = ctx.newPage();

                // Set auth header if provided
                if (request.getAuthHeader() != null && !request.getAuthHeader().isBlank()) {
                    page.setExtraHTTPHeaders(Map.of("Authorization", request.getAuthHeader()));
                }

                log.info("[UIAutomation] Navigating to {}", request.getUrl());
                page.navigate(request.getUrl());
                page.waitForLoadState(); // waits for network idle

                // Give SPAs a bit more time to render
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

                // Extract all interactive elements via injected JS
                String json = (String) page.evaluate("""
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
                            if (!el.offsetParent && el.tagName !== 'BODY') return; // skip hidden
                            const rect = el.getBoundingClientRect();
                            if (rect.width === 0 || rect.height === 0) return; // skip zero-size
                          
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
                          
                            // Build best locator in priority order
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
                              // Prefer getByRole for buttons/links to avoid strict mode violations
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
                          
                            if (!bestLocator) return; // skip elements with no usable locator
                            if (seen.has(bestLocator)) return;
                            seen.add(bestLocator);
                          
                            // Human-readable description
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
                        """);

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
            } catch (JsonMappingException e) {
                throw new RuntimeException(e);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return elements;
    }

    // ── LLM step mapping ──────────────────────────────────────────────────────

    private List<AutomationStep> mapStepsViaLLM(
            String naturalLanguageSteps,
            List<UIAutomationResult.ExtractedElement> elements,
            String url) throws Exception {

        // Build element summary for the prompt (max 80 elements to stay within context)
        StringBuilder elementList = new StringBuilder();
        elements.stream().limit(80).forEach(e ->
                elementList.append("  - ").append(e.getDescription())
                        .append(" → ").append(e.getBestLocator()).append("\n"));

        String prompt = """
                You are a Playwright test automation expert. Given the interactive elements found on a web page
                and a natural language description of steps to automate, produce a JSON array of automation steps.
                
                PAGE URL: %s
                
                AVAILABLE ELEMENTS ON THE PAGE:
                %s
                
                USER'S REQUESTED STEPS:
                %s
                
                Return ONLY a JSON array. No explanation, no markdown, no code fences. Each item must have:
                {
                  "action": one of: navigate|click|fill|select|check|uncheck|hover|waitForSelector|waitForURL|screenshot|assertVisible|assertText|assertURL,
                  "locator": the exact bestLocator string from the elements list above, or null for navigate/waitForURL/screenshot,
                  "value": the value to type/select (for fill/select), or the expected text (for assertText), or null,
                  "url": target URL (for navigate action only), or null,
                  "description": short human-readable description of this step,
                  "waitAfterMs": optional ms to wait after this step (0 if not needed)
                }
                
                Rules:
                - Only use locators from the elements list above. Do not invent locators.
                - IMPORTANT: For buttons and links, ALWAYS prefer getByRole locators over getByText.
                  getByText can match headings and other text on the page causing strict mode violations.
                  Only use getByText as a last resort when no getByRole or getByLabel locator is available.
                - If a step references an element not in the list, use waitForSelector with a reasonable CSS selector.
                - For login flows: fill username → fill password → click submit button using getByRole.
                - Add assertURL or assertVisible steps at the end to verify the automation succeeded.
                - Keep steps atomic — one action per step.
                - For post-login flows, add a navigate step first if needed, then proceed with actions.
                - For assertVisible/assertText steps checking content that appears AFTER an action
                  (e.g. a success message after clicking submit), the target element won't be in the
                  scraped elements list (it appears on a new page). In this case set "locator" to null
                  and ALWAYS set "value" to the exact expected text — the executor will locate it by text.
                - For assertURL, always set "locator" to null and "value" to the expected URL fragment.
                """.formatted(url, elementList, naturalLanguageSteps);

        String responseJson = "groq".equalsIgnoreCase(llmProvider)
                ? callGroq(prompt)
                : callOllama(prompt);

        // Parse and return
        return objectMapper.readValue(responseJson, new TypeReference<>() {});
    }

    private String callGroq(String prompt) throws Exception {
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

    private String callOllama(String prompt) throws Exception {
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", ollamaModel,
                "prompt", prompt,
                "stream", false
        ));

        var http = java.net.http.HttpClient.newHttpClient();
        var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(ollamaBaseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        var resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Ollama API error " + resp.statusCode() + ": " + resp.body());
        }

        String content = objectMapper.readTree(resp.body()).path("response").asText();
        content = content.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
        log.info("[UIAutomation] LLM response: {}", content.substring(0, Math.min(200, content.length())));
        return content;
    }

    // ── Playwright script generation ──────────────────────────────────────────

    private String generatePlaywrightScript(String url, List<AutomationStep> steps, String flowName) {
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