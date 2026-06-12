package com.example.flowengine.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;

@Entity
@Table(
        name = "flows",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "module_id"}) // unique per module, not globally
)
@Data
public class FlowDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(length = 10)
    private String flowType = "API";  // "API" or "UI"

    @Column(columnDefinition = "TEXT")
    private String playwrightScript;  // stored for UI automation flows

    @ManyToOne
    @JoinColumn(name = "module_id")
    @JsonBackReference
    private ModuleEntity module;

    @OneToMany(mappedBy = "flow", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    @JsonManagedReference
    private List<FlowStep> steps;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_environment_id")
    private Environment defaultEnvironment; // nullable — no env if null

}