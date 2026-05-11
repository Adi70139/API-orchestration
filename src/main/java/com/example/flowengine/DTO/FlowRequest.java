package com.example.flowengine.DTO;


import lombok.Data;

@Data
public class FlowRequest {

    private String name;

    private String description;

    private String module;

    private Long environmentId; // optional — overrides flow's default env

}