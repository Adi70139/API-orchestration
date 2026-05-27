package com.example.flowengine.entity;

import com.example.flowengine.constants.BuiltinMethodType;
import com.example.flowengine.constants.MethodType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "custom_methods")
@Data
public class CustomMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MethodType type; // BUILTIN or USER_DEFINED

    // For BUILTIN — which builtin to invoke
    @Enumerated(EnumType.STRING)
    private BuiltinMethodType builtinType;

    // Parameter definitions — JSON array of { name, type, description, required }
    // e.g. [{"name":"min","type":"number","description":"lower bound","required":true}]
    @Column(columnDefinition = "TEXT")
    private String parameterDefinitionsJson;

    // For USER_DEFINED — Groovy script source
    // Script receives: Map<String, Object> params (resolved from bindings)
    // Script must return: Map<String, Object> or a single value (wrapped as {result: value})
    @Column(columnDefinition = "TEXT")
    private String groovyScript;

    // LLM-generated description used to produce the script — useful for regeneration/editing
    @Column(columnDefinition = "TEXT")
    private String llmPromptDescription;

    // false = draft/testing, true = saved and available across all flows
    @Column(nullable = false)
    private boolean global = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}