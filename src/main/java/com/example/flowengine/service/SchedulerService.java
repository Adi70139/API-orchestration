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

    @PostConstruct
    public void loadSchedulesOnStartup() {
        List<ModuleSchedule> schedules = moduleScheduleRepository.findAllByActiveTrue();
        log.info("Loading {} module schedules on startup", schedules.size());
        schedules.forEach(this::registerSchedule);
    }

    public void registerSchedule(ModuleSchedule schedule) {
        Long moduleId = schedule.getModule().getId();
        String cron = timeToCron(schedule.getTime());
        TimeZone tz = TimeZone.getTimeZone(schedule.getTimezone());

        // Atomically replace: compute new future only if we can cancel the old one first.
        // ConcurrentHashMap.compute() is atomic per key — no two threads can race on the same moduleId.
        scheduledTasks.compute(moduleId, (id, existing) -> {
            if (existing != null) {
                existing.cancel(false);
                log.info("Cancelled existing schedule for module {}", moduleId);
            }
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> runScheduledModule(moduleId),
                    new CronTrigger(cron, tz)
            );
            log.info("Scheduled module {} at {} {}", moduleId, schedule.getTime(), schedule.getTimezone());
            return future;
        });
    }

    public void cancelSchedule(Long moduleId) {
        scheduledTasks.computeIfPresent(moduleId, (id, existing) -> {
            existing.cancel(false);
            log.info("Cancelled schedule for module {}", moduleId);
            return null; // returning null removes the key
        });
    }

    private void runScheduledModule(Long moduleId) {
        log.info("Running scheduled execution for module {}", moduleId);
        try {
            executorService.runModule(moduleId, null);
            log.info("Scheduled execution completed for module {}", moduleId);
        } catch (Exception e) {
            log.error("Scheduled execution failed for module {}: {}", moduleId, e.getMessage());
        }
    }

    private String timeToCron(String time) {
        String[] parts = time.split(":");
        return "0 " + parts[1] + " " + parts[0] + " * * *";
    }
}