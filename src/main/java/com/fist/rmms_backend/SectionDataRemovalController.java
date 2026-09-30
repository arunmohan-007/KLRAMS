package com.fist.rmms_backend;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Data Console's "Remove Data" panel: permanently deletes every survey/asset row for a
 * chosen set of road sections. The work — and the table discovery behind it — is in
 * {@link SectionDataRemovalService}.
 *
 * <p>Labels are sent in a JSON body, never in the path, for the same reason
 * {@link SectionRenameController} does it: a section label looks like
 * {@code KPWD/MDR/501010103/17} and an encoded slash in a path segment is rejected by Tomcat
 * by default.
 *
 * <p>SUPER_ADMIN only, enforced in {@link SecurityConfig} — it is a permanent, irreversible
 * bulk delete across every survey table in the system, stricter than the general ADMIN write
 * rule.
 */
@RestController
@RequestMapping("/api/roads")
public class SectionDataRemovalController {

    private final SectionDataRemovalService removal;
    private final SegmentService segments;
    private final FwdSegmentService fwdSegments;
    private final IriSegmentService iriSegments;

    public SectionDataRemovalController(SectionDataRemovalService removal, SegmentService segments,
                                        FwdSegmentService fwdSegments, IriSegmentService iriSegments) {
        this.removal = removal;
        this.segments = segments;
        this.fwdSegments = fwdSegments;
        this.iriSegments = iriSegments;
    }

    /**
     * {@code POST /api/roads/section/remove-data} with {@code {"labels": ["...", "..."]}}.
     * Always 200 on a handled outcome; {@code status: "error"} carries a message and nothing
     * was written.
     */
    @PostMapping("/section/remove-data")
    public Map<String, Object> removeData(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) (List<?>) body.get("labels");
            Map<String, Object> r = removal.remove(labels, auth != null ? auth.getName() : null);
            // Cleared AFTER the transaction commits — the derived-segment tables the removal
            // deletes from are cached in memory (see SectionRenameController for the same
            // reasoning), so a concurrent request must not rebuild the cache from
            // uncommitted-or-rolled-back data.
            if ("ok".equals(r.get("status"))) {
                segments.clearCache();
                fwdSegments.clearCache();
                iriSegments.clearCache();
            }
            return r;
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "error");
            r.put("message", ApiErrors.safe("section data removal", e));
            return r;
        }
    }
}
