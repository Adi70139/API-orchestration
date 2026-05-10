package com.example.flowengine.controller;

import com.example.flowengine.DTO.ScheduleRequest;
import com.example.flowengine.entity.ModuleEntity;
import com.example.flowengine.entity.ModuleSchedule;
import com.example.flowengine.repository.ModuleRepository;
import com.example.flowengine.repository.ModuleScheduleRepository;
import com.example.flowengine.service.SchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/schedule/modules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ModuleScheduleRepository moduleScheduleRepository;
    private final ModuleRepository moduleRepository;
    private final SchedulerService schedulerService;

    @PostMapping("/{moduleId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleSchedule setSchedule(@PathVariable Long moduleId,
                                      @Valid @RequestBody ScheduleRequest request) {
        ModuleEntity module = moduleRepository.findById(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Module not found: " + moduleId));

        ModuleSchedule schedule = moduleScheduleRepository.findByModuleId(moduleId)
                .orElse(new ModuleSchedule());

        schedule.setModule(module);
        schedule.setTime(request.getTime());
        schedule.setTimezone(request.getTimezone());
        schedule.setActive(true);
        schedule = moduleScheduleRepository.save(schedule);

        schedulerService.registerSchedule(schedule);
        return schedule;
    }

    @GetMapping("/{moduleId}")
    public ModuleSchedule getSchedule(@PathVariable Long moduleId) {
        return moduleScheduleRepository.findByModuleId(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("No schedule found for module: " + moduleId));
    }

    @DeleteMapping("/{moduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(@PathVariable Long moduleId) {
        ModuleSchedule schedule = moduleScheduleRepository.findByModuleId(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("No schedule found for module: " + moduleId));
        schedule.setActive(false);
        moduleScheduleRepository.save(schedule);
        schedulerService.cancelSchedule(moduleId);
    }
}