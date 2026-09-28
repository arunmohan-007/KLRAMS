package com.fist.rmms_backend;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dry runs for splitting and merging road sections.
 *
 * <p><b>There is no commit endpoint here yet, and that is the point.</b> Splitting or merging
 * rewrites both the join key and the chainage frame of every survey record on the sections
 * involved (see {@link SectionLineage}), so the impact report ships and is used on its own
 * before any code exists that could act on it. Adding a commit route is Phase 2.
 *
 * <p>Labels travel in a JSON body, never in the path: a section label looks like
 * {@code KPWD/MDR/501010103/17} and Tomcat rejects an encoded slash in a path segment by
 * default — the same reason {@link SectionRenameController} takes its labels in a body.
 *
 * <p>Both routes are ADMIN — see {@link SecurityConfig}. They read and report only. When the
 * commit path arrives it is gated at SUPER_ADMIN separately, so an admin can investigate a
 * change freely without being able to make one.
 */
@RestController
@RequestMapping("/api/roads/section")
public class SectionLineageController {

    private final SectionLineageService lineage;

    public SectionLineageController(SectionLineageService lineage) {
        this.lineage = lineage;
    }

    /**
     * {@code POST /api/roads/section/split/preview} with
     * {@code {"section": "...", "at_chainage": 2600, "first": "...", "second": "..."}}.
     *
     * <p>Always 200; the outcome is in {@code status}. {@code refused} means the split cannot
     * be made as asked and says why; {@code ok} carries the two children's chainage columns,
     * the per-table row counts, the straddling-row counts and the warnings. Nothing is written
     * in either case.
     */
    @PostMapping("/split/preview")
    public Map<String, Object> previewSplit(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Double at = num(body.get("at_chainage"));
            if (at == null)
                return error("A split chainage is required (\"at_chainage\", in metres of road chainage).");
            return lineage.previewSplit(str(body.get("section")), at,
                    str(body.get("first")), str(body.get("second")), name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("road section split preview", e));
        }
    }

    /**
     * {@code POST /api/roads/section/merge/preview} with
     * {@code {"sections": ["...", "..."], "result": "..."}}.
     *
     * <p>Same contract as the split preview. The sections may be given in any order — they are
     * sorted into chainage order before the offsets are computed, because the offset each one's
     * data receives is the total length of everything before it.
     */
    @PostMapping("/merge/preview")
    public Map<String, Object> previewMerge(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Object raw = body.get("sections");
            if (!(raw instanceof List<?> list) || list.size() < 2)
                return error("At least two section labels are required in \"sections\".");
            List<String> labels = list.stream().map(SectionLineageController::str).toList();
            return lineage.previewMerge(labels, str(body.get("result")), name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("road section merge preview", e));
        }
    }

    /**
     * {@code POST /api/roads/section/chainage/preview} with
     * {@code {"section":"...","road_start_chainage":0,"road_end_chainage":3200,"measured_length":3200}}.
     *
     * <p>Reports what correcting one section's chainage would do — in particular whether the
     * reference length changes, because that is what decides whether any stored row moves.
     * Writes nothing. ADMIN.
     */
    @PostMapping("/chainage/preview")
    public Map<String, Object> previewChainage(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return lineage.previewChainageCorrection(str(body.get("section")),
                    num(body.get("road_start_chainage")), num(body.get("road_end_chainage")),
                    num(body.get("measured_length")), name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("section chainage preview", e));
        }
    }

    /**
     * {@code POST /api/roads/section/chainage/apply} — the same body plus
     * {@code "confirm": true}.
     *
     * <p><b>This writes.</b> It is the only endpoint in the module that does. SUPER_ADMIN only,
     * enforced in {@link SecurityConfig} by a matcher placed above the blanket
     * {@code POST /api/**} ADMIN rule — without that explicit entry it would inherit ADMIN.
     *
     * <p>Refuses unless {@code confirm} is true, and refuses when rows would fall past the new
     * section length unless {@code accept_overflow} is also true: shortening a section leaves
     * those rows with no computable position, and they stack on its final point. Both refusals
     * return the full preview, so the reason is in the same response as the numbers behind it.
     */
    @PostMapping("/chainage/apply")
    public Map<String, Object> applyChainage(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return lineage.applyChainageCorrection(str(body.get("section")),
                    num(body.get("road_start_chainage")), num(body.get("road_end_chainage")),
                    num(body.get("measured_length")),
                    bool(body.get("confirm")), bool(body.get("accept_overflow")), name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("section chainage correction", e));
        }
    }

    /**
     * {@code POST /api/roads/section/split/apply} — the preview body plus {@code "confirm": true}
     * and, when the preview raised a high-severity warning, {@code "accept_warnings": true}.
     *
     * <p><b>This writes.</b> SUPER_ADMIN only. One transaction covers the road network row, the
     * cut geometry and every dependent row's chainage; re-placement and cache clearing follow
     * the commit. The response carries a {@code change_id} — the lineage log entry this can be
     * undone from.
     */
    @PostMapping("/split/apply")
    public Map<String, Object> applySplit(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Double at = num(body.get("at_chainage"));
            if (at == null) return error("A split chainage is required (\"at_chainage\", in metres).");
            return lineage.applySplit(str(body.get("section")), at,
                    str(body.get("first")), str(body.get("second")),
                    bool(body.get("confirm")), bool(body.get("accept_warnings")),
                    attrs(body.get("first_attributes")), attrs(body.get("second_attributes")),
                    name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("road section split", e));
        }
    }

    /** {@code POST /api/roads/section/merge/apply}. Writes; SUPER_ADMIN only. */
    @PostMapping("/merge/apply")
    public Map<String, Object> applyMerge(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Object raw = body.get("sections");
            if (!(raw instanceof List<?> list) || list.size() < 2)
                return error("At least two section labels are required in \"sections\".");
            List<String> labels = list.stream().map(SectionLineageController::str).toList();
            return lineage.applyMerge(labels, str(body.get("result")),
                    bool(body.get("confirm")), bool(body.get("accept_warnings")),
                    attrs(body.get("result_attributes")), name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("road section merge", e));
        }
    }

    /**
     * {@code GET /api/roads/section/label-check?label=X&exclude=A&exclude=B} — is this label free?
     *
     * <p>For the editor to answer while the operator is still typing. A read, so it needs no new
     * security rule; it reports only whether a label is taken and by which tables.
     *
     * <p>{@code exclude} names the sections the pending operation is consuming — their labels are
     * free to reuse, and without this the obvious choice (keep the parent's label on one half)
     * would be reported as a clash with itself.
     */
    /**
     * {@code GET /api/roads/section/info?label=X} — one section's chainage frame and the
     * attributes an operator may set on the sections a split or merge produces.
     *
     * <p>Read by the editor as soon as a section is named, so the split point can be entered as
     * road chainage or as a distance into the section with each shown against the other. A cut
     * "at 5800" means very different things on a section that starts at 0 and one that starts at
     * 4 240, and the field alone cannot say which was meant.
     */
    @GetMapping("/info")
    public Map<String, Object> info(@RequestParam String label) {
        try {
            return lineage.sectionInfo(label);
        } catch (Exception e) {
            return error(ApiErrors.safe("section info", e));
        }
    }

    @GetMapping("/label-check")
    public Map<String, Object> labelCheck(@RequestParam String label,
                                          @RequestParam(required = false) List<String> exclude) {
        try {
            return lineage.checkLabel(label, exclude == null ? List.of() : exclude);
        } catch (Exception e) {
            return error(ApiErrors.safe("section label check", e));
        }
    }

    /**
     * {@code GET /api/roads/section/history} — the lineage log, newest first.
     *
     * <p>Each entry says whether it can still be undone, and when it cannot, why. ADMIN: reading
     * what happened to a section is not a privileged act, and it is the first thing anyone
     * investigating a wrong figure needs.
     */
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(defaultValue = "100") int limit) {
        return lineage.history(limit);
    }

    /**
     * {@code POST /api/roads/section/undo} with {@code {"change_id": 12, "confirm": true}}.
     *
     * <p>Writes; SUPER_ADMIN only. Restores the road network rows the change overwrote and
     * reverses the exact chainage shifts it applied. Refused once new data has been imported
     * against the labels the change produced — at that point the recorded state is no longer one
     * the database can be returned to.
     */
    @PostMapping("/undo")
    public Map<String, Object> undo(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Double id = num(body.get("change_id"));
            if (id == null) return error("\"change_id\" is required — see GET /api/roads/section/history.");
            return lineage.undo(id.longValue(), bool(body.get("confirm")), name(auth));
        } catch (Exception e) {
            return error(ApiErrors.safe("road section undo", e));
        }
    }

    /** Attribute overrides arrive as a {column: value} object, or not at all. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(o));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "error");
        r.put("dry_run", true);
        r.put("message", message);
        return r;
    }

    private static String name(Authentication auth) {
        return auth == null ? null : auth.getName();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return null;
        try {
            return Double.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
