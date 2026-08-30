package com.fist.rmms_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calculation Rules API — the corrections and constants behind the published
 * figures, and what each of them changed.
 *
 * Writes inherit the ADMIN requirement {@link SecurityConfig} already applies to
 * every mutating {@code /api/**} call, so there is no role annotation here to
 * drift out of step with it. Reads are open to any signed-in user: seeing why a
 * total is what it is should not need admin rights.
 */
@RestController
@RequestMapping("/api/calc-rules")
public class CalcRuleController {

    private final CalcRuleService rules;
    private final SegmentService segments;

    public CalcRuleController(CalcRuleService rules, SegmentService segments) {
        this.rules = rules;
        this.segments = segments;
    }

    /** Everything the module page needs in one call. */
    @GetMapping
    public ResponseEntity<?> all() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("carriageway", Map.of(
                "groups", rules.carriagewayGroups(),
                "candidates", rules.carriagewayCandidates(),
                "used_by", CalcRuleService.usedBy("carriageway"),
                "effect", rules.carriagewayEffect()));
            out.put("stations", Map.of(
                "groups", rules.stationGroups(),
                "candidates", rules.stationCandidates(),
                "used_by", CalcRuleService.usedBy("traffic_station"),
                "effect", rules.stationEffect()));
            out.put("width", withUsage(rules.widthBands(), "pavement_width", rules.widthEffect()));
            out.put("pci", withUsage(rules.pciSettings(), "pci", null));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return fail("calculation rules", e);
        }
    }

    /** Before/after for every rule — what the dashboards show under "corrections". */
    @GetMapping("/effects")
    public ResponseEntity<?> effects() {
        try {
            return ResponseEntity.ok(Map.of("effects", rules.effects()));
        } catch (Exception e) {
            return fail("correction figures", e);
        }
    }

    /**
     * The rule values the browser needs, so the map viewer and the PCI report
     * compute with the same numbers the server stored: the PCI weights and
     * thresholds, the pavement width bands, and the two grouping maps (section
     * label → group, station name → group) the network scope card corrects with.
     * Read by js/00-calc-rules.js on page load.
     */
    @GetMapping("/client")
    public ResponseEntity<?> client() {
        try {
            Map<String, Object> pci = new LinkedHashMap<>();
            for (Map<String, Object> p : listOf(rules.pciSettings().get("params"))) {
                pci.put(String.valueOf(p.get("key")), List.of(
                    p.get("weight"), p.get("fair"), p.get("poor")));
            }
            Map<String, Object> w = rules.widthBands();
            Map<String, Object> bands = new LinkedHashMap<>();
            for (Map<String, Object> b : listOf(w.get("bands"))) {
                bands.put(String.valueOf(b.get("code")), b.get("width_m"));
            }
            return ResponseEntity.ok(Map.of(
                "pci", pci,
                "width", Map.of("bands", bands,
                                "default_m", w.get("default_m"),
                                "dual_factor", w.get("dual_factor")),
                "carriageway_groups", rules.carriagewayGroupOf(),
                "station_groups", rules.stationKeys(),
                "station_group_names", rules.stationGroupNames()));
        } catch (Exception e) {
            return fail("calculation rules", e);
        }
    }

    /* ------------------------------------------------------------------
       Carriageway groups
       ------------------------------------------------------------------ */

    @PostMapping("/carriageway")
    public ResponseEntity<?> createCw(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            int id = rules.createCarriagewayGroup(str(body.get("name")), strList(body.get("sections")),
                    str(body.get("note")), user(auth));
            return ResponseEntity.ok(Map.of("ok", true, "id", id, "effect", rules.carriagewayEffect()));
        } catch (Exception e) {
            return fail("create carriageway group", e);
        }
    }

    @PostMapping("/carriageway/{id}/members")
    public ResponseEntity<?> addCw(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            rules.addCarriagewayMembers(id, strList(body.get("sections")));
            return okWith(rules.carriagewayEffect());
        } catch (Exception e) {
            return fail("add sections to the group", e);
        }
    }

    @DeleteMapping("/carriageway/{id}/members")
    public ResponseEntity<?> removeCw(@PathVariable int id, @RequestParam String section) {
        try {
            rules.removeCarriagewayMember(id, section);
            return okWith(rules.carriagewayEffect());
        } catch (Exception e) {
            return fail("remove the section from the group", e);
        }
    }

    @PutMapping("/carriageway/{id}")
    public ResponseEntity<?> renameCw(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            rules.renameCarriagewayGroup(id, str(body.get("name")), str(body.get("note")));
            return ok();
        } catch (Exception e) {
            return fail("rename the group", e);
        }
    }

    @DeleteMapping("/carriageway/{id}")
    public ResponseEntity<?> deleteCw(@PathVariable int id) {
        try {
            rules.deleteCarriagewayGroup(id);
            return okWith(rules.carriagewayEffect());
        } catch (Exception e) {
            return fail("delete the group", e);
        }
    }

    /* ------------------------------------------------------------------
       Traffic station groups
       ------------------------------------------------------------------ */

    @PostMapping("/stations")
    public ResponseEntity<?> createStn(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            int id = rules.createStationGroup(str(body.get("name")), strList(body.get("stations")),
                    str(body.get("note")), user(auth));
            return ResponseEntity.ok(Map.of("ok", true, "id", id, "effect", rules.stationEffect()));
        } catch (Exception e) {
            return fail("create station group", e);
        }
    }

    @PostMapping("/stations/{id}/members")
    public ResponseEntity<?> addStn(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            rules.addStationMembers(id, strList(body.get("stations")));
            return okWith(rules.stationEffect());
        } catch (Exception e) {
            return fail("add stations to the group", e);
        }
    }

    @DeleteMapping("/stations/{id}/members")
    public ResponseEntity<?> removeStn(@PathVariable int id, @RequestParam String station) {
        try {
            rules.removeStationMember(id, station);
            return okWith(rules.stationEffect());
        } catch (Exception e) {
            return fail("remove the station from the group", e);
        }
    }

    @PutMapping("/stations/{id}")
    public ResponseEntity<?> renameStn(@PathVariable int id, @RequestBody Map<String, Object> body) {
        try {
            rules.renameStationGroup(id, str(body.get("name")), str(body.get("note")));
            return ok();
        } catch (Exception e) {
            return fail("rename the group", e);
        }
    }

    @DeleteMapping("/stations/{id}")
    public ResponseEntity<?> deleteStn(@PathVariable int id) {
        try {
            rules.deleteStationGroup(id);
            return okWith(rules.stationEffect());
        } catch (Exception e) {
            return fail("delete the group", e);
        }
    }

    /** Fold any still-ungrouped A/B station pairs into groups. */
    @PostMapping("/stations/rescan")
    public ResponseEntity<?> rescanStn(Authentication auth) {
        try {
            int made = rules.rescanStationPairs(user(auth));
            return ResponseEntity.ok(Map.of("ok", true, "created", made,
                    "effect", rules.stationEffect()));
        } catch (Exception e) {
            return fail("scan for A/B station pairs", e);
        }
    }

    /* ------------------------------------------------------------------
       Width bands, value maps, PCI
       ------------------------------------------------------------------ */

    @PostMapping("/width/band")
    public ResponseEntity<?> putBand(@RequestBody Map<String, Object> body) {
        try {
            rules.putWidthBand(str(body.get("code")), dbl(body.get("width_m")), str(body.get("note")));
            return okWith(rules.widthEffect());
        } catch (Exception e) {
            return fail("save the width band", e);
        }
    }

    @DeleteMapping("/width/band")
    public ResponseEntity<?> deleteBand(@RequestParam String code) {
        try {
            rules.deleteWidthBand(code);
            return okWith(rules.widthEffect());
        } catch (Exception e) {
            return fail("delete the width band", e);
        }
    }

    @PostMapping("/width/scalars")
    public ResponseEntity<?> putScalars(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            rules.putWidthScalars(dblOrNull(body.get("default_m")), dblOrNull(body.get("dual_factor")), user(auth));
            return okWith(rules.widthEffect());
        } catch (Exception e) {
            return fail("save the width settings", e);
        }
    }

    /**
     * Save the PCI weights and thresholds.
     *
     * The PCI stored on every condition segment was computed with the OLD
     * numbers, so it is stale the moment this returns. {@code rebuild=true}
     * rebuilds the segments straight away; otherwise the response says plainly
     * that a rebuild is outstanding, and the module shows it.
     */
    @PostMapping("/pci")
    public ResponseEntity<?> putPci(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            rules.putPciSettings(mapList(body.get("params")), user(auth));
            return ResponseEntity.ok(pciResult(Boolean.TRUE.equals(body.get("rebuild"))));
        } catch (Exception e) {
            return fail("save the PCI settings", e);
        }
    }

    @PostMapping("/pci/reset")
    public ResponseEntity<?> resetPci(@RequestBody(required = false) Map<String, Object> body,
                                      Authentication auth) {
        try {
            rules.resetPciSettings(user(auth));
            return ResponseEntity.ok(pciResult(body != null && Boolean.TRUE.equals(body.get("rebuild"))));
        } catch (Exception e) {
            return fail("reset the PCI settings", e);
        }
    }

    private Map<String, Object> pciResult(boolean rebuild) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("pci", rules.pciSettings());
        if (rebuild) {
            int n = segments.buildSegments();
            res.put("rebuilt_segments", n);
            res.put("rebuild_required", false);
            res.put("message", "Saved, and " + n + " condition segment(s) were recomputed with the new numbers.");
        } else {
            res.put("rebuild_required", true);
            res.put("message", "Saved. The PCI stored on every condition segment was computed with the "
                             + "previous numbers and is now out of date — rebuild the condition segments "
                             + "to bring it in line.");
        }
        return res;
    }

    /* ------------------------------------------------------------------ */

    private static Map<String, Object> withUsage(Map<String, Object> base, String ruleKey, Object effect) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.put("used_by", CalcRuleService.usedBy(ruleKey));
        if (effect != null) m.put("effect", effect);
        return m;
    }

    private static ResponseEntity<?> ok() {
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<?> okWith(Object effect) {
        return ResponseEntity.ok(Map.of("ok", true, "effect", effect));
    }

    private static String user(Authentication auth) {
        return auth == null ? "system" : auth.getName();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Double dblOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        return Double.parseDouble(String.valueOf(o).trim());
    }

    private static double dbl(Object o) {
        Double d = dblOrNull(o);
        if (d == null) throw new IllegalArgumentException("A number is required.");
        return d;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null) out.add(String.valueOf(x));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map) out.add((Map<String, Object>) x);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    private ResponseEntity<?> fail(String context, Exception e) {
        return ResponseEntity.badRequest()
                .body(Map.of("ok", false, "error", ApiErrors.safe(context, e)));
    }
}
