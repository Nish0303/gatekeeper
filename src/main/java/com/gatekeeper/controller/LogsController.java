package com.gatekeeper.controller;

import com.gatekeeper.model.RequestLog;
import com.gatekeeper.repository.RequestLogRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogsController {

    private final RequestLogRepository repo;

    public LogsController(RequestLogRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<RequestLog> getLogs(@RequestParam Long apiKeyId) {
        return repo.findByApiKeyIdOrderByTimestampDesc(apiKeyId);
    }
}