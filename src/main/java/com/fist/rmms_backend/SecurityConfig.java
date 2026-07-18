package com.fist.rmms_backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KLRAMS / KHRI security — role-based access control.
 *
 *  Accounts are stored in the {@code app_users} table and loaded by
 *  {@link AppUserDetailsService}; the first SUPER_ADMIN is seeded from
 *  {@code app.admin.username} / {@code app.admin.password} by {@link UserService}.
 *
 *  Roles (a role hierarchy makes each level inherit the ones below it):
 *    SUPER_ADMIN — full power: all data, Site Control, User Management
 *    ADMIN       — view everything + import/edit/delete data; NOT Site Control, NOT User Management
 *    USER        — view-only: every read, no writes anywhere
 *
 *  Public (no login):
 *    - welcome.html, login.html, /login, favicon, /img/**, /js/**, /css/**
 *    - GET /api/go/folders, /api/go/docs, /api/go/file/**, /api/site/content
 *
 *  Everything else needs a login; writes are gated by role (see the matchers
 *  below). CSRF is disabled for the pilot so the existing upload fetch() calls
 *  work unchanged; enable it before public hosting.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** SUPER_ADMIN inherits ADMIN inherits USER, so hasRole("ADMIN") also passes super admins. */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("SUPER_ADMIN").implies("ADMIN")
                .role("ADMIN").implies("USER")
                .build();
    }

    /**
     * Serve public static assets (JS, CSS, images, favicon) completely outside
     * the security filter chain. Two reasons:
     *   1) Spring Security otherwise stamps every response with
     *      "Cache-Control: no-store", forcing the browser to re-download all
     *      ~30 JS modules + CSS on every page load and navigation.
     *   2) These files are cache-busted with ?v=NNN in the HTML, so caching
     *      them for a long time is safe — a change bumps the version.
     * Ignored requests instead get the long-lived cache headers configured by
     * spring.web.resources.cache.* in application.properties.
     */
    @Bean
    public WebSecurityCustomizer staticAssetsIgnore() {
        return (WebSecurity web) -> web.ignoring()
                .requestMatchers("/js/**", "/css/**", "/img/**", "/favicon.ico");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, LoginAuditService audit) throws Exception {
        http
            .csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a
                // public pages + assets (login page needs its JS/CSS to load)
                .requestMatchers("/welcome.html", "/login.html", "/login", "/favicon.ico",
                                 "/img/**", "/js/**", "/css/**").permitAll()
                // public read-only APIs (Government Orders + About/Contact)
                .requestMatchers(HttpMethod.GET, "/api/go/folders", "/api/go/docs", "/api/go/file/**", "/api/site/content").permitAll()

                // --- SUPER_ADMIN only: Site Control + User Management + reports ---
                .requestMatchers("/admin.html").hasRole("SUPER_ADMIN")          // Site Control page
                .requestMatchers("/users.html").hasRole("SUPER_ADMIN")          // User Management page
                .requestMatchers("/login-report.html").hasRole("SUPER_ADMIN")   // Login activity report page
                .requestMatchers("/api/users/**").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/reports/**").hasRole("SUPER_ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/site/**").hasRole("SUPER_ADMIN")  // site settings writes
                // permanently deletes survey points — stricter than the general ADMIN delete rule below
                .requestMatchers(HttpMethod.DELETE, "/api/assets/*/orphans").hasRole("SUPER_ADMIN")

                // --- self-service: change own password ---
                .requestMatchers("/api/account/**").authenticated()

                // --- view-only (USER) is blocked from every write ---
                // climate seed/recalc are triggerable via GET, so pin them explicitly
                .requestMatchers("/api/climate/seed-grid", "/api/climate/recalc").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,   "/api/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT,    "/api/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH,  "/api/**").hasRole("ADMIN")

                // everything else (all reads, the viewer, portals) needs any login
                .anyRequest().authenticated())
            .formLogin(f -> f
                .loginPage("/login.html")
                .loginProcessingUrl("/login")
                // Record the sign-in (IP, user-agent, session) then land on the portal,
                // preserving the previous "always redirect to /home.html" behaviour.
                .successHandler((req, res, auth) -> {
                    audit.recordLogin(auth.getName(), req,
                            req.getSession(false) != null ? req.getSession().getId() : null);
                    res.sendRedirect(req.getContextPath() + "/home.html");
                })
                .failureUrl("/login.html?error")
                .permitAll())
            .logout(l -> l
                .logoutUrl("/logout")
                // Stamp the session's logout time before the session is invalidated.
                .addLogoutHandler((req, res, auth) -> {
                    if(req.getSession(false) != null) audit.recordLogout(req.getSession(false).getId());
                })
                .logoutSuccessUrl("/welcome.html")
                .permitAll());
        return http.build();
    }
}
