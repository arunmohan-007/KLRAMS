package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * KLRAMS — re-place stored linear-referenced geometry against the current road network.
 *
 * Every layer positioned by {@code Section_Label + chainage} (road assets and traffic
 * stations) stores the resolved point/stretch at import. That stored position is a
 * snapshot: re-import the roads shapefile with redrawn centrelines or corrected
 * chainages and those layers are quietly left where the OLD geometry put them. Nothing
 * in the import path fixes it, because both importers only fill rows whose geom is
 * still NULL.
 *
 * <pre>
 *   GET  /api/placement/status              rows / placed / unplaced per layer
 *   POST /api/placement/replace             re-place every layer
 *   POST /api/placement/replace?layer=fwd   re-place one (asset type, or traffic_stations)
 * </pre>
 *
 * Run this after any road-network upload. It is idempotent — re-running changes nothing
 * once positions agree with the network — so it is safe to repeat, and safe to run when
 * you are merely unsure whether it is needed.
 *
 * <b>It never deletes a row.</b> Anything whose section label is no longer on the network
 * comes back with a NULL geom and is named in the response, so the label can be corrected
 * and the row re-placed. Writes fall under the blanket {@code POST /api/**} ADMIN matcher
 * in SecurityConfig, so no new security rule is needed.
 */
@RestController
@RequestMapping("/api/placement")
public class PlacementController {

    private final PlacementService placement;
    private final AssetController assets;

    public PlacementController(PlacementService placement, AssetController assets) {
        this.placement = placement;
        this.assets = assets;
    }

    /** Per-layer stored/unplaced counts. Cheap; meant to be read before and after a re-place. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("layers", placement.status());
    }

    /** Re-place one layer, or every layer when {@code layer} is omitted. */
    @PostMapping("/replace")
    public Map<String, Object> replace(@RequestParam(required = false) String layer) {
        List<Map<String, Object>> results = new ArrayList<>();

        if (layer == null || layer.isBlank()) {
            for (String t : sortedAssetTypes()) results.add(replaceOne(t));
            results.add(placement.replaceTrafficGeom());
        } else {
            String t = layer.trim();
            if (!"traffic_stations".equals(t)
                    && !AssetController.LINE_TYPES.contains(t) && !AssetController.POINT_TYPES.contains(t)) {
                return Map.of("status", "error",
                        "message", "Unknown layer \"" + t + "\". Expected traffic_stations or one of: "
                                 + String.join(", ", sortedAssetTypes()));
            }
            results.add(replaceOne(t));
        }

        // Each layer is re-placed in its own transaction (they are independent, and one
        // bad layer should not roll back the rest), so totals are reported per layer too.
        int replaced = 0, orphaned = 0, unplaced = 0;
        for (Map<String, Object> r : results) {
            replaced += (int) r.get("replaced");
            orphaned += (int) r.get("orphaned");
            unplaced += (int) r.get("unplaced");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("replaced", replaced);
        out.put("orphaned", orphaned);
        out.put("unplaced", unplaced);
        out.put("layers", results);
        return out;
    }

    private Map<String, Object> replaceOne(String type) {
        if ("traffic_stations".equals(type)) return placement.replaceTrafficGeom();
        // A legacy FWD row can still have From..To only in attrs. Restore end_chainage
        // first, or the re-place reads it as NULL and orphans a row that is placeable.
        if ("fwd".equals(type)) assets.relocateFwdLineGeoms();
        return placement.replaceAssetGeom(type, AssetController.LINE_TYPES.contains(type));
    }

    private List<String> sortedAssetTypes() {
        List<String> all = new ArrayList<>(AssetController.LINE_TYPES);
        all.addAll(AssetController.POINT_TYPES);
        Collections.sort(all);
        return all;
    }
}
