package com.fist.rmms_backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The road-network attribute metadata a categorical colour-by needs, without downloading the
 * network. Additive: {@code buildAttrMeta()} still scans the full GeoJSON today, and continues to
 * until the client is switched over.
 */
@RestController
public class RoadAttrController {

    private final RoadAttrService attrs;

    public RoadAttrController(RoadAttrService attrs) {
        this.attrs = attrs;
    }

    /** Every colourable attribute name, and whether it is numeric or categorical — populates the
     *  colour-by dropdown without needing the network in hand first. */
    @GetMapping("/api/roads/attrs")
    public List<Map<String, Object>> attrs() {
        return attrs.attrs();
    }

    /** Stats for one attribute: min/max if numeric, distinct values (by frequency and
     *  alphabetically) if categorical. A malformed/unknown attribute is a 400 through
     *  {@link GlobalExceptionHandler}, carrying the developer-authored message — safe to show,
     *  and useful if the client ever asks for a name that has since left the roads schema. */
    @GetMapping("/api/roads/attr-meta")
    public Map<String, Object> attrMeta(@RequestParam("attr") String attr) {
        return attrs.meta(attr);
    }
}
