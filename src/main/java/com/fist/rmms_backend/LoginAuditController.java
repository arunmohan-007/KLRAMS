package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * Login activity report — SUPER_ADMIN only (enforced in {@link SecurityConfig}
 * via {@code /api/reports/**}). Backs the /login-report.html page: who signed
 * in, when, from which IP, on what browser, and how long the session lasted.
 */
@RestController
@RequestMapping("/api/reports")
public class LoginAuditController {

    private final LoginAuditService audit;

    public LoginAuditController(LoginAuditService audit){ this.audit = audit; }

    @GetMapping("/logins")
    public List<Map<String,Object>> logins(
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to){
        return audit.list(limit, from, to);
    }
}
