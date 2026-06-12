package com.example.flowengine.controller;

import com.example.flowengine.DTO.UIAutomationRequest;
import com.example.flowengine.DTO.UIAutomationResult;
import com.example.flowengine.service.UIAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/ui-automation")
@RequiredArgsConstructor
@Tag(name = "UI Automation", description = "Generate Playwright UI automation flows from natural language")
public class UIAutomationController {

    private final UIAutomationService uiAutomationService;

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Generate a UI automation flow",
            description = """
                    Launches a headless browser, scrapes interactive elements from the target page,
                    maps your natural language steps to real locators via LLM, generates a
                    Playwright Java test script, and saves everything as a new flow.
                    
                    Example steps: "enter admin in username, enter secret in password, click Login, verify dashboard loads"
                    """
    )
    public UIAutomationResult generate(@RequestBody UIAutomationRequest request) throws Exception {
        return uiAutomationService.generateAutomation(request);
    }

    @GetMapping("/script/{flowId}")
    @Operation(
            summary = "Download the Playwright script for a UI automation flow",
            description = "Returns the generated Playwright Java test as a downloadable .java file"
    )
    public ResponseEntity<String> downloadScript(@PathVariable Long flowId) throws Exception {
        // Re-generate script isn't ideal long-term — ideally store it.
        // For now this endpoint is a placeholder the tool references.
        // The script is included in the /generate response body.
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body("Script download: retrieve from the playwrightScript field in the /generate response. " +
                        "Persistent script storage will be added in a future migration.");
    }

    @PostMapping("/scrape")
    @Operation(
            summary = "Scrape interactive elements from a page without generating a flow",
            description = "Useful for previewing what elements are available before generating automation"
    )
    public UIAutomationResult scrapeOnly(@RequestBody UIAutomationRequest request) throws Exception {
        // Just scrape — set empty steps so LLM mapping is skipped
        // We reuse the service but short-circuit by checking steps field
        request.setSteps("__SCRAPE_ONLY__");
        return uiAutomationService.generateAutomation(request);
    }
}