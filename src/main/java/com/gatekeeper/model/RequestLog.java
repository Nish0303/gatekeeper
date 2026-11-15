package com.gatekeeper.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "request_logs")
public class RequestLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long apiKeyId;
    private String path;
    private int statusCode;
    private long latencyMs;
    private Instant timestamp = Instant.now();

    public RequestLog() {}

    public RequestLog(Long apiKeyId, String path, int statusCode, long latencyMs) {
        this.apiKeyId = apiKeyId;
        this.path = path;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
    }

    // getters/setters
    public Long getId() { return id; }
    public Long getApiKeyId() { return apiKeyId; }
    public String getPath() { return path; }
    public int getStatusCode() { return statusCode; }
    public long getLatencyMs() { return latencyMs; }
    public Instant getTimestamp() { return timestamp; }
}