package com.gatekeeper.controller;

import com.gatekeeper.service.JwtService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public String getTestToken(@RequestParam String subject) {
        return jwtService.mintTestToken(subject);
    }
}