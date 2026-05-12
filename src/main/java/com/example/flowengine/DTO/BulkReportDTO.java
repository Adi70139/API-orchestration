package com.example.flowengine.DTO;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class BulkReportDTO {
    private Long bulkJobId;
    private String type;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Integer totalItems;
    private Integer passedItems;
    private Integer failedItems;
    private List<BulkItemReportDTO> items;

    @Data
    public static class BulkItemReportDTO {
        private Long targetId;
        private String targetName;
        private String status;
        private Long executionId;
        private Long environmentId;
        private Long durationMs;
    }
}

