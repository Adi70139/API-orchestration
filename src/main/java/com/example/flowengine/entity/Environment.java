package com.example.flowengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(
        name = "environments",
        uniqueConstraints = @UniqueConstraint(columnNames = {"name", "module_id"})
)
@Data
public class Environment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name; // DEV, QA, PROD etc.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private ModuleEntity module;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String variablesJson; // encrypted JSON: {"baseUrl": "enc...", "username": "enc..."}
}