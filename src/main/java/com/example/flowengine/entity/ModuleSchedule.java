package com.example.flowengine.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "module_schedules")
@Data
public class ModuleSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "module_id", nullable = false, unique = true)
    private ModuleEntity module;

    @Column(nullable = false)
    private String time; // "HH:mm" e.g. "02:30"

    @Column(nullable = false)
    private String timezone; // e.g. "Asia/Kolkata"

    @Column(nullable = false)
    private boolean active = true;
}