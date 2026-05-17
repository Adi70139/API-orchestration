package com.example.flowengine.DTO;

import lombok.Data;

@Data
public class ModuleScheduleResponse {


    private String time;

    private String timezone;


    private boolean active;
}
