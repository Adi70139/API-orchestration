package com.example.flowengine.controller;

import com.example.flowengine.service.ExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/health")
public class HealthController {

    private final ExecutorService executorService;

    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @RequestMapping(method = RequestMethod.HEAD)
    public ResponseEntity<Void> healthHead() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/executor-stats")
    public Map<String, Object> executorStats() {
        return executorService.getExecutorStats();
    }
}