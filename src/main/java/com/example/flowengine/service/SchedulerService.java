package com.example.flowengine.service;

import com.example.flowengine.entity.ModuleSchedule;
import com.example.flowengine.repository.ModuleScheduleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final TaskScheduler taskScheduler;
    private final ModuleScheduleRepository moduleScheduleRepository;
    private final ExecutorService executorService;

    // Keeps track of active scheduled futures so we can cancel them
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * On startup, reload all active schedules from DB.
     */
    @PostConstruct
    public void loadSchedulesOnStartup() {
        List<ModuleSchedule> schedules = moduleScheduleRepository.findAllByActiveTrue();
        log.info("Loading {} module schedules on startup", schedules.size());
        schedules.forEach(this::registerSchedule);
    }

    public void registerSchedule(ModuleSchedule schedule) {
        Long moduleId = schedule.getModule().getId();

        // Cancel existing if any
        cancelSchedule(moduleId);

        String cron = timeToCron(schedule.getTime());
        TimeZone tz = TimeZone.getTimeZone(schedule.getTimezone());

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> runScheduledModule(moduleId),
                new CronTrigger(cron, tz)
        );

        scheduledTasks.put(moduleId, future);
        log.info("Scheduled module {} to run at {} {}", moduleId, schedule.getTime(), schedule.getTimezone());
    }

    public void cancelSchedule(Long moduleId) {
        ScheduledFuture<?> existing = scheduledTasks.remove(moduleId);
        if (existing != null) {
            existing.cancel(false);
            log.info("Cancelled schedule for module {}", moduleId);
        }
    }

    private void runScheduledModule(Long moduleId) {
        log.info("Running scheduled execution for module {}", moduleId);
        try {
            executorService.runModule(moduleId);
            log.info("Scheduled execution completed for module {}", moduleId);
            // TODO: notify — add notification dispatch here when implementing alerts
        } catch (Exception e) {
            log.error("Scheduled execution failed for module {}: {}", moduleId, e.getMessage());
            // TODO: notify — send failure notification here
        }
    }

    /**
     * Convert "HH:mm" to a Spring cron expression "0 mm HH * * *"
     */
    private String timeToCron(String time) {
        String[] parts = time.split(":");
        return "0 " + parts[1] + " " + parts[0] + " * * *";
    }
}