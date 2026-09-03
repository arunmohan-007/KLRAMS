package com.fist.rmms_backend;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Server-side extent (bounding box) arithmetic for the Map Composer.
 *
 * <p>The Composer has to answer "where should the page be centred, and how far in?" before it
 * draws anything. The honest client-side answer would be to download every geometry it is about
 * to show and run turf.bbox over it — which is exactly what the viewer spent build 163 onwards
 * getting AWAY from. The road network alone is multi-MB of coordinates, and an extent is four
 * numbers; asking PostGIS for {@code ST_Extent} costs one index-assisted scan and returns those
 * four numbers.
 *
 * <p>One POST, not a GET per layer kind: a composed map routinely spans several datasets (the
 * filtered network plus bridges plus a user layer), and the useful answer is the UNION of their
 * extents, computed once. Sending the section-label list of a filtered network also rules a GET
 * out — a district filter can match several hundred labels, each of them containing slashes.
 *
 * <p>It is a read despite being a POST, so {@code SecurityConfig} carries an explicit
 * {@code /api/composer/**} authenticated() rule ahead of the blanket "POST /api/** is ADMIN"
 * matcher. Without it a view-only account could open the Composer and never get a map.
 */
@RestController
@RequestMapping("/api/composer")
public class MapComposerController {

    private final MapComposerService composer;

    public MapComposerController(MapComposerService composer) {
        this.composer = composer;
    }

    /**
     * Union extent of everything the request names.
     *
     * <p>Request body, every field optional:
     * <pre>
     * {
     *   "roads":      true,                 // the whole road network
     *   "sections":   ["KPWD/MDR/…", …],    // …or only these Section_La labels
     *   "assets":     ["bridge","culvert"],
     *   "userLayers": [3, 7],
     *   "boundaries": ["district"],
     *   "fullNetwork": false
     * }
     * </pre>
     *
     * <p>Answers {@code {ok:true, bbox:[minX,minY,maxX,maxY]}}, or {@code bbox:null} when nothing
     * named has geometry — an empty filter result is a normal answer here, not an error, and the
     * Composer says so in words rather than drawing a map of the Atlantic.
     */
    @PostMapping(value = "/extent", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> extent(@RequestBody(required = false) Map<String, Object> req) {
        return composer.extent(req == null ? Map.of() : req);
    }

    /**
     * The datasets the Composer may ask for an extent of, with a per-dataset feature count.
     *
     * <p>Used to grey out "Selected layers" entries that would contribute nothing, so a user does
     * not pick three layers and get an empty page.
     */
    @GetMapping(value = "/extent/sources", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> sources() {
        return composer.sources();
    }
}
