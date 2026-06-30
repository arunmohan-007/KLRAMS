package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/condition")
public class ConditionController {

    private final ConditionService service;

    public ConditionController(ConditionService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        try {
            int n = service.loadCsv(file.getInputStream());
            result.put("status", "ok");
            result.put("inserted", n);
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", String.valueOf(e.getMessage()));
        }
        return result;
    }

    @GetMapping("/count")
    public Map<String, Object> count() {
        Map<String, Object> result = new HashMap<>();
        result.put("count", service.count());
        return result;
    }
}
