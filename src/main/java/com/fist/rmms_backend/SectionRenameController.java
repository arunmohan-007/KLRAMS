package com.fist.rmms_backend;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renaming a road section label across every layer that references it.
 *
 * <p>The work — and the reasoning behind the rules it enforces — is in
 * {@link SectionRenameService}. This class exists only to bind the request, take the user
 * from the session, and make sure the in-memory caches are cleared AFTER the transaction
 * commits rather than inside it (see {@link SectionRenameService#clearCaches()}), which is
 * why the cache clear cannot live in the service's {@code @Transactional} method.
 *
 * <p>Labels are sent in a JSON body, never in the path: a section label looks like
 * {@code KPWD/MDR/501010103/17} and Tomcat rejects an encoded slash in a path segment by
 * default — the same reason {@code /api/roads/one/geojson} takes {@code ?section=}.
 *
 * <p>SUPER_ADMIN only, enforced in {@link SecurityConfig}. It is not a data edit in the
 * ordinary sense: it rewrites the join key every survey record hangs off, and renaming
 * back is only an undo while nothing else has been imported in between.
 */
@RestController
@RequestMapping("/api/roads")
public class SectionRenameController {

    private final SectionRenameService renames;

    public SectionRenameController(SectionRenameService renames) {
        this.renames = renames;
    }

    /**
     * {@code POST /api/roads/section/rename} with {@code {"from": "...", "to": "..."}}.
     *
     * <p>Always 200 — the outcome is in {@code status}: {@code ok}, {@code not_found}
     * (neither label is usable), {@code exists} (both are live road sections, so this would
     * be a merge), {@code conflict} (the new label already carries survey data) or
     * {@code error}. Only {@code ok} wrote anything.
     *
     * <p>An {@code ok} also carries {@code mode}: {@code rename} when the old label was
     * still on the network, or {@code recover} when it had already been replaced by a road
     * re-upload and only the dependent layers were left behind. A {@code recover} carries a
     * {@code follow_up} list too — re-pointing the rows does not by itself re-place the
     * geometry they were originally cut against. The caller does not choose the mode; see
     * {@link SectionRenameService}.
     */
    @PostMapping("/section/rename")
    public Map<String, Object> rename(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Map<String, Object> r = renames.rename(
                    str(body.get("from")), str(body.get("to")),
                    auth != null ? auth.getName() : null);
            if ("ok".equals(r.get("status"))) renames.clearCaches();
            return r;
        } catch (Exception e) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("status", "error");
            r.put("message", ApiErrors.safe("road section rename", e));
            return r;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
