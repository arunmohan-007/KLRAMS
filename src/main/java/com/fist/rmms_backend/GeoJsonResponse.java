package com.fist.rmms_backend;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared conditional-GET (ETag) handling for the large cached GeoJSON layers
 * (roads, condition segments, FWD segments, full road network, assets).
 *
 * These payloads are multi-MB and unchanged between data uploads, yet were
 * re-downloaded in full on every map open (only Cache-Control: no-cache was
 * set, with nothing to revalidate against). We now attach a content-derived
 * ETag: the browser revalidates with If-None-Match and we answer 304 (empty
 * body) whenever the content is unchanged, so a repeat open transfers almost
 * nothing. Cache-Control stays no-cache, so a fresh upload still surfaces on a
 * normal reload the moment the in-memory cache is rebuilt.
 */
final class GeoJsonResponse {
    private GeoJsonResponse() {}

    /** A quoted ETag derived from the body content, so identical data yields the
     *  same tag across rebuilds and restarts (the browser keeps getting 304s),
     *  while any change flips it — a stale 304 is therefore impossible. */
    static String contentTag(String body) {
        if (body == null) return "\"0\"";
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(26).append('"');
            for (int i = 0; i < 12; i++) {           // 96 bits -> 24 hex chars, ample
                sb.append(Character.forDigit((h[i] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(h[i] & 0xf, 16));
            }
            return sb.append('"').toString();
        } catch (Exception e) {
            return "\"" + Integer.toHexString(body.hashCode()) + "\"";
        }
    }

    /** True if the client's If-None-Match satisfies our tag — tolerant of the
     *  {@code W/} weak-validator prefix a gzip proxy (nginx/Cloudflare) may add,
     *  of surrounding whitespace, of a comma-separated list, and of {@code *}. */
    static boolean matches(String ifNoneMatch, String tag) {
        if (ifNoneMatch == null || tag == null) return false;
        String want = strip(tag);
        for (String part : ifNoneMatch.split(",")) {
            String p = part.trim();
            if ("*".equals(p) || strip(p).equals(want)) return true;
        }
        return false;
    }

    private static String strip(String s) {
        s = s.trim();
        if (s.startsWith("W/")) s = s.substring(2).trim();
        return s;
    }

    /** 200 with the body, or an empty 304 when the client's tag still matches.
     *  Always no-cache + application/json. */
    static ResponseEntity<String> conditional(String body, String tag, String ifNoneMatch) {
        if (matches(ifNoneMatch, tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(tag).cacheControl(CacheControl.noCache()).build();
        }
        return ResponseEntity.ok()
                .eTag(tag).cacheControl(CacheControl.noCache())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /** Body plus its ETag, returned together from the per-period segment services
     *  so the controller can answer a conditional request without re-hashing. */
    record Payload(String body, String etag) {}
}
