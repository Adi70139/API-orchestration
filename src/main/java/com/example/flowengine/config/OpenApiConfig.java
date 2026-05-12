package com.example.flowengine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("API Flow Engine")
                        .version("1.0.0")
                        .description("""
                    API automation engine that allows you to:
                    - Organize APIs into Modules → Flows → Steps
                    - Chain responses between steps using {placeholders}
                    - Run flows with environment-specific variables (DEV/QA/PROD)
                    - Assert response schemas and field values
                    - Schedule nightly runs per module
                    - Generate PDF execution reports
                    """)
                        .contact(new Contact()
                                .name("Flow Engine Team")));
    }
}