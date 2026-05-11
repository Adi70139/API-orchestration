package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class ModuleResponse {

    private Long id;

    private String name;

    private String description;

    private Integer flowCount;
}