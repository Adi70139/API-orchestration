package com.example.flowengine.DTO;

import com.example.flowengine.constants.BulkJobStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class BulkJobResult {
    private Long bulkJobId;
    private String type;
    private BulkJobStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private List<BulkJobItemResult> items;
}