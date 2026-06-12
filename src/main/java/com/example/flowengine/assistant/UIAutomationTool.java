package com.example.flowengine.assistant;

import com.example.flowengine.DTO.UIAutomationRequest;
import com.example.flowengine.DTO.UIAutomationResult;
import com.example.flowengine.service.UIAutomationService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UIAutomationTool {

    private final UIAutomationService uiAutomationService;

    @Tool("""
            Generate a UI automation flow from a URL and natural language steps.
            Launches a headless browser, scrapes interactive elements from the page,
            uses AI to map the natural language steps to real locators, generates a
            Playwright script, and saves everything as a new flow in the specified module.
            Use this when the user wants to automate a web page or create a UI test.
            """)
    public String generateUIAutomation(
            @P("The full URL of the web page to automate (e.g. https://myapp.com/login)") String url,
            @P("Natural language description of the steps to automate. E.g: 'enter admin in the username field, enter password123 in the password field, click the Login button, verify the dashboard page loads'") String steps,
            @P("Name of the module to save the flow into. Call listModules first if unsure.") String moduleName,
            @P("Name for the generated flow (e.g. 'Login Flow Automation')") String flowName,
            @P("Optional: Bearer token for authenticated pages, e.g. 'Bearer eyJ...' Leave blank if not needed.") String authHeader,
            @P("Optional: cookies as JSON array [{name, value, domain}]. Leave blank if not needed.") String cookiesJson
    ) {
        log.info("[UIAutomationTool] url={} module={} flow={}", url, moduleName, flowName);
        try {
            UIAutomationRequest request = new UIAutomationRequest();
            request.setUrl(url);
            request.setSteps(steps);
            request.setModuleName(moduleName);
            request.setFlowName(flowName);
            if (authHeader != null && !authHeader.isBlank()) request.setAuthHeader(authHeader);
            if (cookiesJson != null && !cookiesJson.isBlank()) request.setCookiesJson(cookiesJson);

            UIAutomationResult result = uiAutomationService.generateAutomation(request);

            // Build a concise summary for the chat response
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("UI automation generated successfully!\n\n"));
            sb.append(String.format("Flow: '%s' [id=%d] in module '%s'\n",
                    result.getFlowName(), result.getFlowId(), result.getModuleName()));
            sb.append(String.format("Steps generated: %d\n\n", result.getStepCount()));

            sb.append("Elements found on page:\n");
            result.getExtractedElements().stream().limit(10).forEach(e ->
                    sb.append(String.format("  - %s → %s\n", e.getDescription(), e.getBestLocator())));
            if (result.getExtractedElements().size() > 10) {
                sb.append(String.format("  ... and %d more elements\n",
                        result.getExtractedElements().size() - 10));
            }

            sb.append("\nPlaywright script preview (first 500 chars):\n```java\n");
            String script = result.getPlaywrightScript();
            sb.append(script, 0, Math.min(500, script.length()));
            if (script.length() > 500) sb.append("\n... (truncated)");
            sb.append("\n```\n");
            sb.append("\nThe full Playwright script is saved in the flow description. ");
            sb.append("You can download it via GET /ui-automation/script/").append(result.getFlowId());

            return sb.toString();

        } catch (Exception e) {
            log.error("[UIAutomationTool] Failed", e);
            return "UI automation failed: " + e.getMessage() +
                    "\n\nCommon causes:\n" +
                    "- Page not reachable from this server\n" +
                    "- Page requires authentication (provide authHeader or cookiesJson)\n" +
                    "- Playwright browser not installed (run: mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args='install chromium')";
        }
    }
}