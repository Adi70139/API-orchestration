package com.example.flowengine.service;

import com.example.flowengine.entity.ModuleSchedule;
import com.example.flowengine.repository.ModuleScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
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

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    // ApplicationReadyEvent fires after the entire context is fully started —
    // all @PostConstruct methods done, all beans initialized.
    // @PostConstruct was unreliable here because ExecutorService.init() (which sets up
    // asyncFlowExecutor) is also a @PostConstruct — order between them is not guaranteed.
    @EventListener(ApplicationReadyEvent.class)
    public void loadSchedulesOnStartup() {
        List<ModuleSchedule> schedules = moduleScheduleRepository.findAllByActiveTrue();
        log.info("Loading {} active module schedule(s) on startup", schedules.size());
        schedules.forEach(schedule -> {
            try {
                registerSchedule(schedule);
            } catch (Exception e) {
                log.error("Failed to register schedule for module {}: {}",
                        schedule.getModule().getId(), e.getMessage());
            }
        });
    }

    public void registerSchedule(ModuleSchedule schedule) {
        Long moduleId = schedule.getModule().getId();
        String cron = timeToCron(schedule.getTime());
        TimeZone tz = TimeZone.getTimeZone(schedule.getTimezone());

        log.info("Registering schedule for module {} — cron='{}' tz='{}'", moduleId, cron, tz.getID());

        scheduledTasks.compute(moduleId, (id, existing) -> {
            if (existing != null) {
                existing.cancel(false);
                log.info("Cancelled existing schedule for module {}", moduleId);
            }
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> runScheduledModule(moduleId),
                    new CronTrigger(cron, tz)
            );
            log.info("Scheduled module {} at '{}' ({})", moduleId, schedule.getTime(), schedule.getTimezone());
            return future;
        });
    }

    public void cancelSchedule(Long moduleId) {
        scheduledTasks.computeIfPresent(moduleId, (id, existing) -> {
            existing.cancel(false);
            log.info("Cancelled schedule for module {}", moduleId);
            return null;
        });
    }

    private void runScheduledModule(Long moduleId) {
        log.info("Running scheduled execution for module {}", moduleId);
        try {
            executorService.runModule(moduleId, null);
            log.info("Scheduled execution completed for module {}", moduleId);
        } catch (Exception e) {
            log.error("Scheduled execution failed for module {}: {}", moduleId, e.getMessage(), e);
        }
    }

    // Converts "HH:mm" to a 6-part Spring cron expression: "0 mm HH * * *"
    private String timeToCron(String time) {
        String[] parts = time.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid time format '" + time + "' — expected HH:mm");
        }
        return "0 " + parts[1].trim() + " " + parts[0].trim() + " * * *";
    }
}