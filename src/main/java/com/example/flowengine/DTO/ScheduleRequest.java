package com.example.flowengine.DTO;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ScheduleRequest {

    @NotBlank
    @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
            message = "Time must be in HH:mm format e.g. 02:30")
    private String time;

    @NotBlank
    private String timezone; // e.g. "Asia/Kolkata"
}