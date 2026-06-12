package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class UIAutomationRequest {
    /** Target page URL to automate */
    private String url;

    /** Natural language description of the steps to perform */
    private String steps;

    /** Module name (or ID as string) to save the generated flow into */
    private String moduleName;

    /** Name for the generated flow */
    private String flowName;

    /** Optional: cookie string or Bearer token for authenticated pages */
    private String authHeader;

    /** Optional: extra cookies as JSON array [{name, value, domain}] */
    private String cookiesJson;
}