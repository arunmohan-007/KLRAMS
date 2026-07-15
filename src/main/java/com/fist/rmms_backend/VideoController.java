package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private final VideoService service;
    private final SurveyPeriodService periods;

    public VideoController(VideoService service, SurveyPeriodService periods) {
        this.service = service;
        this.periods = periods;
    }

    /** Upload a .zip of video files; they are extracted and kept on the server. */
    @PostMapping("/upload-zip")
    public Map<String, Object> uploadZip(@RequestParam("file") MultipartFile file) {
        Map<String, Object> r = new HashMap<>();
        try {
            int n = service.storeZip(file);
            r.put("status", "ok");
            r.put("stored", n);
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", String.valueOf(e.getMessage()));
        }
        return r;
    }

    /** How many bytes the server already holds for this file, so the client can resume. */
    @GetMapping("/upload-status")
    public Map<String, Object> uploadStatus(@RequestParam("name") String name) {
        Map<String, Object> r = new HashMap<>();
        try {
            r.put("status", "ok");
            r.put("uploaded", service.uploadedBytes(name));
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", String.valueOf(e.getMessage()));
            r.put("uploaded", 0);
        }
        return r;
    }

    /**
     * Append one chunk of a single video. The client sends files one at a time
     * in small chunks; a failure only affects the current chunk, never the
     * whole batch. Returns {status: complete|partial|resync, uploaded: bytes}.
     */
    @PostMapping("/upload-chunk")
    public Map<String, Object> uploadChunk(@RequestParam("name") String name,
                                           @RequestParam("offset") long offset,
                                           @RequestParam("total") long total,
                                           @RequestParam("chunk") MultipartFile chunk) {
        try {
            return service.putChunk(name, offset, total, chunk);
        } catch (Exception e) {
            Map<String, Object> r = new HashMap<>();
            r.put("status", "error");
            r.put("message", String.valueOf(e.getMessage()));
            return r;
        }
    }

    /** Upload the catalog CSV (section_label, video_file, direction) into one survey period. */
    @PostMapping("/upload-catalog")
    public Map<String, Object> uploadCatalog(@RequestParam("file") MultipartFile file,
                                             @RequestParam(value = "periodId", required = false) Integer periodId) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (periodId == null || !periods.exists(periodId)) {
                r.put("status", "error");
                r.put("message", "Select the survey period this video catalogue belongs to before importing.");
                return r;
            }
            int n = service.loadCatalog(file.getInputStream(), periodId);
            r.put("status", "ok");
            r.put("entries", n);
        } catch (Exception e) {
            r.put("status", "error");
            r.put("message", String.valueOf(e.getMessage()));
        }
        return r;
    }

    /** The map reads this to know which video + direction belongs to each road.
     *  Defaults to the active survey period; ?period_id= selects another. */
    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog(@RequestParam(value = "period_id", required = false) Integer periodId) {
        return service.catalog(periodId);
    }
}
