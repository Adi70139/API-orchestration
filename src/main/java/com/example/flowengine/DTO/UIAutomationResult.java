package com.example.flowengine.DTO;

import lombok.Data;
import java.util.List;

@Data
public class UIAutomationResult {

    /** The created flow */
    private Long flowId;
    private String flowName;
    private String moduleName;

    /** How many steps were generated */
    private int stepCount;

    /** The generated Playwright script (for display/download) */
    private String playwrightScript;

    /** Summary of what was generated */
    private String summary;

    /** Locators extracted from the page — useful for the frontend to display */
    private List<ExtractedElement> extractedElements;

    @Data
    public static class ExtractedElement {
        private String tag;          // button, input, a, select, etc.
        private String role;
        private String label;
        private String placeholder;
        private String testId;
        private String cssSelector;
        private String bestLocator;  // recommended locator in priority order
        private String description;  // human-readable e.g. "Login button"
    }
}