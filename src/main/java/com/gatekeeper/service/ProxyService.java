package com.gatekeeper.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;

@Service
public class ProxyService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${gatekeeper.backend-url}")
    private String backendUrl;

    public ResponseEntity<byte[]> forward(HttpServletRequest request) throws IOException {
        String targetUrl = backendUrl + request.getRequestURI();
        if (request.getQueryString() != null) {
            targetUrl += "?" + request.getQueryString();
        }

        HttpHeaders headers = new HttpHeaders();
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.add(name, request.getHeader(name));
        }

        byte[] body = request.getInputStream().readAllBytes();
        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());

        try {
            return restTemplate.exchange(targetUrl, method, entity, byte[].class);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsByteArray());
        }
    }
}