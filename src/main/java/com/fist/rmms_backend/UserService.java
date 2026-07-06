package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Application accounts + role assignment for KLRAMS.
 *
 *  Roles (highest to lowest):
 *    SUPER_ADMIN — full power: all data, Site Control, User Management
 *    ADMIN       — view everything + import/edit/delete data; no Site Control, no User Management
 *    USER        — view-only: every read, no writes anywhere
 *
 *  Accounts live in the {@code app_users} table (created on startup, JdbcTemplate
 *  house style — no JPA). The first SUPER_ADMIN is seeded from the pilot
 *  credentials {@code app.admin.username} / {@code app.admin.password} so the
 *  existing login keeps working and becomes the super admin.
 *
 *  The {@code district} column is reserved for future USER scoping (per district
 *  / PWD section) and is not used yet.
 */
@Service
public class UserService {

    /** Allowed role values. */
    public static final Set<String> ROLES = Set.of("SUPER_ADMIN", "ADMIN", "USER");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Value("${app.admin.username:admin}")
    private String seedUser;

    // No default: the bootstrap password must be supplied via configuration
    // (app.admin.password in application.properties) — never hardcoded here.
    @Value("${app.admin.password:}")
    private String seedPass;

    public UserService(JdbcTemplate jdbc, PasswordEncoder encoder){
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @PostConstruct
    public void init(){
        ensureSchema();
        seedSuperAdmin();
        ensureBootstrapSuperAdmin();
    }

    void ensureSchema(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS app_users (" +
                "id SERIAL PRIMARY KEY, " +
                "username TEXT UNIQUE NOT NULL, " +
                "password_hash TEXT NOT NULL, " +
                "role TEXT NOT NULL, " +
                "full_name TEXT, " +
                "enabled BOOLEAN NOT NULL DEFAULT true, " +
                "must_change_password BOOLEAN NOT NULL DEFAULT true, " +
                "district TEXT, " +               // reserved for future USER scoping
                "created_at TIMESTAMPTZ DEFAULT now(), " +
                "updated_at TIMESTAMPTZ DEFAULT now())");
    }

    /** Insert the pilot super-admin once, if the table has no accounts yet. */
    void seedSuperAdmin(){
        Integer c = jdbc.queryForObject("SELECT count(*) FROM app_users", Integer.class);
        if(c != null && c > 0) return;
        if(seedPass == null || seedPass.isBlank()){
            System.out.println("[UserService] app_users is empty but no app.admin.password is configured — " +
                    "skipping super-admin seed. Set app.admin.username/app.admin.password (or create a user in the DB) to bootstrap.");
            return;
        }
        jdbc.update("INSERT INTO app_users(username,password_hash,role,full_name,enabled,must_change_password) " +
                        "VALUES (?,?,?,?,true,false)",
                seedUser, encoder.encode(seedPass), "SUPER_ADMIN", "System Administrator");
    }

    /**
     * Break-glass guarantee: the configured bootstrap account
     * ({@code app.admin.username}) is ALWAYS a full-access super admin on
     * startup. If it exists with a lower role or is disabled, it is promoted.
     * This means a stuck/downgraded admin is fixed by a restart — no SQL needed.
     */
    void ensureBootstrapSuperAdmin(){
        int updated = jdbc.update(
                "UPDATE app_users SET role='SUPER_ADMIN', enabled=true, updated_at=now() " +
                "WHERE lower(username)=lower(?) AND (role<>'SUPER_ADMIN' OR enabled=false)",
                seedUser);
        if(updated > 0)
            System.out.println("[UserService] Promoted bootstrap account '" + seedUser + "' to SUPER_ADMIN.");
    }

    /* ---------------- reads ---------------- */

    public List<Map<String,Object>> list(){
        return jdbc.queryForList(
                "SELECT id, username, role, full_name, enabled, must_change_password, district, " +
                "created_at, updated_at FROM app_users ORDER BY " +
                "CASE role WHEN 'SUPER_ADMIN' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, username");
    }

    public Map<String,Object> findByUsername(String username){
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT * FROM app_users WHERE lower(username)=lower(?)", username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String,Object> findById(long id){
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM app_users WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /* ---------------- writes ---------------- */

    /** Create an account. Throws IllegalArgumentException on bad input / duplicate username. */
    public Map<String,Object> create(String username, String fullName, String role, String password){
        String u = username == null ? "" : username.trim();
        if(u.isEmpty()) throw new IllegalArgumentException("Username is required");
        if(password == null || password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        role = normalizeRole(role);
        if(findByUsername(u) != null) throw new IllegalArgumentException("Username already exists");
        jdbc.update("INSERT INTO app_users(username,password_hash,role,full_name,enabled,must_change_password) " +
                        "VALUES (?,?,?,?,true,true)",
                u, encoder.encode(password), role, fullName);
        return findByUsername(u);
    }

    /** Update role / full name / enabled. Protects the last enabled super admin. */
    public void updateProfile(long id, String role, String fullName, Boolean enabled){
        Map<String,Object> cur = findById(id);
        if(cur == null) throw new IllegalArgumentException("User not found");
        String newRole = role == null ? (String) cur.get("role") : normalizeRole(role);
        boolean newEnabled = enabled == null ? (Boolean) cur.get("enabled") : enabled;

        // Super admins are never disabled — to remove access, downgrade the role first.
        if("SUPER_ADMIN".equals(newRole) && !newEnabled)
            throw new IllegalArgumentException("Super admins cannot be disabled");

        boolean wasSuper = "SUPER_ADMIN".equals(cur.get("role")) && Boolean.TRUE.equals(cur.get("enabled"));
        boolean staysSuper = "SUPER_ADMIN".equals(newRole) && newEnabled;
        if(wasSuper && !staysSuper && countActiveSuperAdmins() <= 1)
            throw new IllegalArgumentException("Cannot remove or disable the last active super admin");

        jdbc.update("UPDATE app_users SET role=?, full_name=?, enabled=?, updated_at=now() WHERE id=?",
                newRole, fullName, newEnabled, id);
    }

    /** Reset a user's password (admin action) and force them to change it at next login. */
    public void setPassword(long id, String password){
        if(password == null || password.length() < 6) throw new IllegalArgumentException("Password must be at least 6 characters");
        if(findById(id) == null) throw new IllegalArgumentException("User not found");
        jdbc.update("UPDATE app_users SET password_hash=?, must_change_password=true, updated_at=now() WHERE id=?",
                encoder.encode(password), id);
    }

    /** Change one's own password (verifies the current one) and clear the force-change flag. */
    public void changeOwnPassword(String username, String current, String next){
        Map<String,Object> u = findByUsername(username);
        if(u == null) throw new IllegalArgumentException("User not found");
        if(current == null || !encoder.matches(current, (String) u.get("password_hash")))
            throw new IllegalArgumentException("Current password is incorrect");
        if(next == null || next.length() < 6) throw new IllegalArgumentException("New password must be at least 6 characters");
        jdbc.update("UPDATE app_users SET password_hash=?, must_change_password=false, updated_at=now() WHERE id=?",
                encoder.encode(next), ((Number) u.get("id")).longValue());
    }

    public void delete(long id){
        Map<String,Object> cur = findById(id);
        if(cur == null) return;
        if("SUPER_ADMIN".equals(cur.get("role")) && Boolean.TRUE.equals(cur.get("enabled")) && countActiveSuperAdmins() <= 1)
            throw new IllegalArgumentException("Cannot delete the last active super admin");
        jdbc.update("DELETE FROM app_users WHERE id=?", id);
    }

    /* ---------------- helpers ---------------- */

    private int countActiveSuperAdmins(){
        Integer c = jdbc.queryForObject(
                "SELECT count(*) FROM app_users WHERE role='SUPER_ADMIN' AND enabled=true", Integer.class);
        return c == null ? 0 : c;
    }

    private String normalizeRole(String role){
        String r = role == null ? "" : role.trim().toUpperCase();
        if(!ROLES.contains(r)) throw new IllegalArgumentException("Invalid role: " + role);
        return r;
    }
}
