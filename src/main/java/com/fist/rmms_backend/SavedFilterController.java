package com.fist.rmms_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Named, reusable filters for the map viewer — currently the Road Network
 * attribute filter (kind="network"), whose state is just {mode, rows[]} from
 * 05-road-network.js. The payload is stored as opaque JSON so the same table
 * can hold other panels' filters later without a schema change.
 *
 * Visibility: a filter belongs to the user who saved it. ADMIN / SUPER_ADMIN
 * may additionally mark one "shared", which makes it visible (read-only) to
 * every logged-in user — that is how department-wide standard filters are
 * published. Only the owner can overwrite or delete their own row.
 *
 *   GET    /api/saved-filters?kind=network  — own filters + everyone's shared ones
 *   POST   /api/saved-filters               — save/overwrite by (owner, kind, name)
 *   DELETE /api/saved-filters/{id}          — delete own
 *
 * Writes are open to any authenticated user (see the /api/saved-filters/**
 * carve-out in SecurityConfig): saving a personal filter is self-service, not
 * a data edit, so view-only USER accounts must be able to do it.
 */
@RestController
@RequestMapping("/api/saved-filters")
public class SavedFilterController {

    /** Guard against a runaway payload being pushed into the table. */
    private static final int MAX_PAYLOAD_CHARS = 64 * 1024;
    private static final int MAX_NAME_CHARS = 80;

    private final JdbcTemplate jdbc;
    private final ObjectMapper om = new ObjectMapper();

    public SavedFilterController(JdbcTemplate jdbc){ this.jdbc = jdbc; }

    @PostConstruct
    public void init(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS saved_filters (" +
                "id SERIAL PRIMARY KEY, " +
                "owner TEXT NOT NULL, " +
                "kind TEXT NOT NULL DEFAULT 'network', " +
                "name TEXT NOT NULL, " +
                "shared BOOLEAN NOT NULL DEFAULT false, " +
                "payload JSONB NOT NULL, " +
                "created_at TIMESTAMPTZ DEFAULT now(), " +
                "updated_at TIMESTAMPTZ DEFAULT now())");
        /* One name per user per panel. Case-insensitive on both owner and name
           so "SH roads" cannot quietly become a second row next to "SH Roads";
           the expression list here must match the ON CONFLICT target below. */
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS saved_filters_owner_kind_name " +
                "ON saved_filters (lower(owner), kind, lower(name))");
    }

    /* ---------------- helpers ---------------- */

    private static boolean canShare(Authentication auth){
        if(auth == null) return false;
        for(GrantedAuthority a : auth.getAuthorities()){
            String r = a.getAuthority();
            if("ROLE_ADMIN".equals(r) || "ROLE_SUPER_ADMIN".equals(r)) return true;
        }
        return false;
    }

    private static Map<String,Object> bad(String msg){
        Map<String,Object> m = new HashMap<>();
        m.put("ok", false); m.put("error", msg);
        return m;
    }

    /* ---------------- read ---------------- */

    /**
     * Own filters first, then filters other users have shared. `mine` tells the
     * frontend which rows offer a Delete button; the rest are load-only.
     */
    @GetMapping
    public List<Map<String,Object>> list(Authentication auth,
                                         @RequestParam(defaultValue = "network") String kind){
        String me = auth == null ? "" : auth.getName();
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT id, owner, name, shared, payload::text AS payload, updated_at " +
                "FROM saved_filters WHERE kind=? AND (lower(owner)=lower(?) OR shared=true) " +
                "ORDER BY (lower(owner)=lower(?)) DESC, lower(name)", kind, me, me);
        List<Map<String,Object>> out = new ArrayList<>();
        for(Map<String,Object> r : rows){
            Map<String,Object> m = new HashMap<>();
            m.put("id", r.get("id"));
            m.put("name", r.get("name"));
            m.put("owner", r.get("owner"));
            m.put("shared", r.get("shared"));
            m.put("mine", String.valueOf(r.get("owner")).equalsIgnoreCase(me));
            m.put("updated_at", r.get("updated_at"));
            /* payload comes back as a JSON string from ::text — re-parse so the
               client receives a real object, not a string it has to JSON.parse.
               Read to Object (Map/List), NOT to JsonNode: a JsonNode nested
               inside this Map gets re-serialized by its bean getters, so the
               client would receive {"array":false,"object":true,...} instead
               of the filter. */
            try { m.put("payload", om.readValue(String.valueOf(r.get("payload")), Object.class)); }
            catch(Exception e){ m.put("payload", null); }
            out.add(m);
        }
        return out;
    }

    /* ---------------- write ---------------- */

    @PostMapping
    public ResponseEntity<?> save(Authentication auth, @RequestBody Map<String,Object> body){
        if(auth == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(bad("not signed in"));
        String owner = auth.getName();

        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if(name.isEmpty()) return ResponseEntity.badRequest().body(bad("Give the filter a name."));
        if(name.length() > MAX_NAME_CHARS)
            return ResponseEntity.badRequest().body(bad("Name is too long (max " + MAX_NAME_CHARS + " characters)."));

        String kind = String.valueOf(body.getOrDefault("kind", "network")).trim();
        if(kind.isEmpty()) kind = "network";

        Object payload = body.get("payload");
        if(payload == null) return ResponseEntity.badRequest().body(bad("payload required"));
        String json;
        try { json = om.writeValueAsString(payload); }
        catch(Exception e){ return ResponseEntity.badRequest().body(bad("payload is not valid JSON")); }
        if(json.length() > MAX_PAYLOAD_CHARS)
            return ResponseEntity.badRequest().body(bad("Filter is too large to save."));

        /* Sharing is an ADMIN act — a USER posting shared:true just gets a
           private filter rather than a 403, since the save itself is legitimate. */
        boolean shared = Boolean.TRUE.equals(body.get("shared")) && canShare(auth);

        jdbc.update("INSERT INTO saved_filters(owner,kind,name,shared,payload,updated_at) " +
                    "VALUES (?,?,?,?,?::jsonb,now()) " +
                    "ON CONFLICT (lower(owner), kind, lower(name)) DO UPDATE SET " +
                    "name=EXCLUDED.name, shared=EXCLUDED.shared, payload=EXCLUDED.payload, updated_at=now()",
                owner, kind, name, shared, json);

        Map<String,Object> r = new HashMap<>();
        r.put("ok", true); r.put("name", name); r.put("shared", shared);
        return ResponseEntity.ok(r);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable long id){
        if(auth == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(bad("not signed in"));
        /* Scoped to the owner: a shared filter is visible to everyone but only
           the user who saved it can remove it. */
        int n = jdbc.update("DELETE FROM saved_filters WHERE id=? AND lower(owner)=lower(?)",
                id, auth.getName());
        if(n == 0) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(bad("That filter is not yours to delete."));
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
