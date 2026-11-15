package com.gatekeeper.controller;

import com.gatekeeper.model.ApiKey;
import com.gatekeeper.repository.ApiKeyRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/keys")
public class ApiKeysController {

    private final ApiKeyRepository repo;

    public ApiKeysController(ApiKeyRepository repo) {
        this.repo = repo;
    }

    // Only expose id + owner — never the hash
    @GetMapping
    public List<Map<String, Object>> listKeys() {
        return repo.findAll().stream()
                .map(k -> Map.<String, Object>of("id", k.getId(), "owner", k.getOwner()))
                .collect(Collectors.toList());
    }
}