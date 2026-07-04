package com.fist.rmms_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * User Management + account self-service.
 *
 *  /api/users/**        — SUPER_ADMIN only (enforced in SecurityConfig). List,
 *                         create, update role/enabled, reset password, delete.
 *  GET  /api/me         — any authenticated user; the frontend uses it to learn
 *                         its role (to gate the UI) and whether a first-login
 *                         password change is required.
 *  POST /api/account/password — any authenticated user changes their own password.
 *
 *  Business rules (last super admin, uniqueness, min length) live in
 *  {@link UserService}; validation failures come back as 400 with a message.
 */
@RestController
@RequestMapping("/api")
public class UserController {

    private final UserService users;

    public UserController(UserService users){ this.users = users; }

    /* ---------------- user management (SUPER_ADMIN) ---------------- */

    @GetMapping("/users")
    public List<Map<String,Object>> list(){
        return users.list();
    }

    @PostMapping("/users")
    public ResponseEntity<?> create(@RequestBody Map<String,String> body){
        try {
            Map<String,Object> u = users.create(
                    body.get("username"), body.get("fullName"),
                    body.get("role"), body.get("password"));
            u.remove("password_hash");
            return ResponseEntity.ok(Map.of("ok", true, "user", u));
        } catch(IllegalArgumentException e){ return bad(e); }
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String,Object> body){
        try {
            String role = body.get("role") == null ? null : String.valueOf(body.get("role"));
            String fullName = body.get("fullName") == null ? null : String.valueOf(body.get("fullName"));
            Boolean enabled = body.get("enabled") == null ? null : Boolean.valueOf(String.valueOf(body.get("enabled")));
            users.updateProfile(id, role, fullName, enabled);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch(IllegalArgumentException e){ return bad(e); }
    }

    @PostMapping("/users/{id}/password")
    public ResponseEntity<?> resetPassword(@PathVariable long id, @RequestBody Map<String,String> body){
        try {
            users.setPassword(id, body.get("password"));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch(IllegalArgumentException e){ return bad(e); }
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> delete(@PathVariable long id){
        try {
            users.delete(id);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch(IllegalArgumentException e){ return bad(e); }
    }

    /* ---------------- current user (any authenticated) ---------------- */

    @GetMapping("/me")
    public Map<String,Object> me(Authentication auth){
        Map<String,Object> r = new HashMap<>();
        if(auth == null){ r.put("authenticated", false); return r; }
        Map<String,Object> u = users.findByUsername(auth.getName());
        r.put("authenticated", true);
        r.put("username", auth.getName());
        r.put("role", u == null ? null : u.get("role"));
        r.put("fullName", u == null ? null : u.get("full_name"));
        r.put("mustChangePassword", u != null && Boolean.TRUE.equals(u.get("must_change_password")));
        return r;
    }

    @PostMapping("/account/password")
    public ResponseEntity<?> changeOwnPassword(Authentication auth, @RequestBody Map<String,String> body){
        if(auth == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("ok", false));
        try {
            users.changeOwnPassword(auth.getName(), body.get("current"), body.get("new"));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch(IllegalArgumentException e){ return bad(e); }
    }

    private ResponseEntity<?> bad(Exception e){
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", e.getMessage()));
    }
}
