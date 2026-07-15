package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/condition")
public class ConditionController {

    private final ConditionService service;
    private final SurveyPeriodService periods;

    public ConditionController(ConditionService service, SurveyPeriodService periods) {
        this.service = service;
        this.periods = periods;
    }

    /**
     * Duplicate rows (same Section_Label + XSP + chainage) inflate every lane-km
     * total, so the upload runs a pre-check first: when duplicates are present and
     * force=false, nothing is imported — the response carries the duplicate report
     * and the console asks the user whether to proceed. Re-posting with force=true
     * imports the file as-is.
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "force", defaultValue = "false") boolean force,
                                      @RequestParam(value = "periodId", required = false) Integer periodId) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (periodId == null || !periods.exists(periodId)) {
                result.put("status", "error");
                result.put("message", "Select the survey period this data belongs to before importing.");
                return result;
            }
            byte[] data = file.getBytes();
            if (!force) {
                Map<String, Object> rep = service.analyzeDuplicates(new ByteArrayInputStream(data));
                if (((Number) rep.get("duplicates")).intValue() > 0) {
                    rep.put("status", "duplicates");
                    return rep;
                }
            }
            int n = service.loadCsv(new ByteArrayInputStream(data), periodId);
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
