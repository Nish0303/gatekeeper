package com.gatekeeper.filter;

import com.gatekeeper.model.ApiKey;
import com.gatekeeper.model.RequestLog;
import com.gatekeeper.repository.ApiKeyRepository;
import com.gatekeeper.repository.RequestLogRepository;
import com.gatekeeper.service.JwtService;
import com.gatekeeper.service.ProxyService;
import com.gatekeeper.service.RateLimiterService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Optional;

@Component
public class GatewayFilter implements Filter {

    private final JwtService jwtService;
    private final RateLimiterService rateLimiterService;
    private final ProxyService proxyService;
    private final ApiKeyRepository apiKeyRepository;
    private final RequestLogRepository requestLogRepository;

    public GatewayFilter(JwtService jwtService, RateLimiterService rateLimiterService,
                          ProxyService proxyService, ApiKeyRepository apiKeyRepository,
                          RequestLogRepository requestLogRepository) {
        this.jwtService = jwtService;
        this.rateLimiterService = rateLimiterService;
        this.proxyService = proxyService;
        this.apiKeyRepository = apiKeyRepository;
        this.requestLogRepository = requestLogRepository;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Skip the filter for our own test/utility endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/auth")
        || path.startsWith("/api/logs")
        || path.startsWith("/api/keys")
        || path.startsWith("/dashboard")
        || path.equals("/")
        || path.startsWith("/favicon")) {
            chain.doFilter(req, res);
            return;
        }

        long start = System.currentTimeMillis();

        // 1. JWT check
        String authHeader = request.getHeader("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;

        if (token == null || !jwtService.isValid(token)) {
            respond(response, 401, "Invalid or missing JWT");
            return;
        }

        // 2. API key lookup
        String apiKeyRaw = request.getHeader("X-API-Key");
        if (apiKeyRaw == null) {
            respond(response, 401, "Missing X-API-Key header");
            return;
        }

        String hash = sha256(apiKeyRaw);
        Optional<ApiKey> apiKeyOpt = apiKeyRepository.findByKeyHash(hash);
        if (apiKeyOpt.isEmpty()) {
            respond(response, 401, "Unknown API key");
            return;
        }
        ApiKey apiKey = apiKeyOpt.get();

        // 3. Rate limit
        boolean allowed = rateLimiterService.allow(apiKey.getId().toString(), apiKey.getRateLimitPerMin());
        if (!allowed) {
            respond(response, 429, "Rate limit exceeded");
            logRequest(apiKey.getId(), path, 429, start);
            return;
        }

        // 4. Proxy
        ResponseEntity<byte[]> proxied = proxyService.forward(request);
        response.setStatus(proxied.getStatusCode().value());
        if (proxied.getBody() != null) {
            response.getOutputStream().write(proxied.getBody());
        }

        // 5. Log
        logRequest(apiKey.getId(), path, proxied.getStatusCode().value(), start);
    }

    private void logRequest(Long apiKeyId, String path, int status, long start) {
        long latency = System.currentTimeMillis() - start;
        requestLogRepository.save(new RequestLog(apiKeyId, path, status, latency));
    }

    private void respond(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}