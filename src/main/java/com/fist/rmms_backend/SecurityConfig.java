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
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
 *  below). Token-based CSRF is disabled because the static frontend (~30 JS
 *  modules of raw fetch()/XHR calls plus a plain form-POST login) has no place
 *  to carry a token. CSRF is instead defended at the cookie layer: the session
 *  cookie is SameSite=Strict (see application.properties), so a cross-site page
 *  cannot make an authenticated browser send state-changing requests.
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

    /**
     * Content-Security-Policy — PHASE 1 (see the CSP note in the security audit memo).
     *
     * WHAT THIS DOES AND DOES NOT STOP. `script-src` deliberately still carries
     * 'unsafe-inline', because the pages rely on ~343 inline event-handler
     * attributes (onclick="..." and friends: ~191 written into the HTML, ~152
     * more string-built by the JS modules at runtime). A nonce cannot rescue
     * those — the moment a nonce appears in script-src the browser IGNORES
     * 'unsafe-inline', and every one of those handlers stops firing at once.
     * Removing them is a separate, page-by-page refactor (Phase 2); until then
     * an injected inline <script> or onerror= would still run.
     *
     * What it DOES stop today, which is most of the payload shapes that matter:
     *   - script-src 'self' + a pinned CDN: an injected <script src="//evil/x.js">
     *     will not load, and eval()/new Function() are blocked (no 'unsafe-eval';
     *     verified nothing in this codebase uses either).
     *   - base-uri 'self': blocks <base href="//evil"> injection, which would
     *     otherwise silently re-point every relative <script src> on the page.
     *   - form-action 'self': an injected <form> cannot post the user's input
     *     (or a password manager's autofill) to an attacker host.
     *   - connect-src / img-src allowlists: even if script does execute, it has
     *     nowhere to exfiltrate to — fetch() and image-beacon to any host not
     *     listed here is refused.
     *   - object-src 'none' / frame-src 'none': no plugin or nested-document
     *     vectors (the app embeds no iframes at all).
     *
     * ORIGIN NOTES — every entry below is load-bearing; dropping one breaks a map:
     *   unpkg.com        MapLibre GL, Leaflet, Turf, shpjs (script + css, and
     *                    Leaflet's css pulls its marker PNGs from there too,
     *                    which is why unpkg is in img-src as well).
     *   fonts.google/gstatic  the webfonts every page's <link> pulls.
     *   demotiles.maplibre.org  glyph server for MapLibre symbol layers — label
     *                    rendering fails outright without it (see the FONTS note
     *                    in js/34-layer-style.js).
     *   tile/basemap hosts    OSM, OpenTopoMap, Carto, ArcGIS World Imagery.
     *                    Listed in BOTH img-src and connect-src: Leaflet
     *                    (map-lite.html, survey-archive.html) loads tiles as
     *                    <img>, MapLibre fetches them.
     *   router.project-osrm.org  routing lookups.
     *   blob:            MapLibre runs its workers from blob URLs (worker-src),
     *                    and the CSV/PDF/KML export paths build blob downloads.
     *   media-src https: the NSV video catalogue may point at an externally
     *                    hosted file (video.html:147 uses entry.file directly
     *                    when it is an absolute URL), so media cannot be pinned
     *                    to 'self'. Broad, but media is not an execution vector.
     */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "base-uri 'self'",
            "object-src 'none'",
            "frame-src 'none'",
            "frame-ancestors 'none'",
            "form-action 'self'",
            /* No 'unsafe-inline': every inline <script> is now an external file
             * and every on*= handler dispatches through js/00-actions.js, so an
             * injected inline script or onerror= no longer executes. This is the
             * directive the whole Phase-2 refactor existed to make possible.
             *
             * style-src below DOES keep 'unsafe-inline' — the pages carry inline
             * style="" attributes throughout, and CSS is not an execution vector
             * in the way script is. Removing it is a much larger job for far less
             * benefit, so it is a deliberate stopping point rather than an oversight. */
            "script-src 'self' https://unpkg.com",
            "style-src 'self' 'unsafe-inline' https://unpkg.com https://fonts.googleapis.com",
            "font-src 'self' data: https://fonts.gstatic.com",
            "img-src 'self' data: blob: https://unpkg.com "
                    + "https://*.tile.openstreetmap.org https://*.tile.opentopomap.org "
                    + "https://*.basemaps.cartocdn.com https://server.arcgisonline.com",
            "connect-src 'self' blob: https://demotiles.maplibre.org https://router.project-osrm.org "
                    + "https://*.tile.openstreetmap.org https://*.tile.opentopomap.org "
                    + "https://*.basemaps.cartocdn.com https://server.arcgisonline.com",
            "media-src 'self' blob: https:",
            "worker-src 'self' blob:");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, LoginAuditService audit,
                                           LoginAttemptService attempts, UserService users) throws Exception {
        http
            .csrf(c -> c.disable())
            // Defence in depth beyond Spring Security's defaults (nosniff,
            // X-Frame-Options: DENY, HSTS on HTTPS requests — the last of
            // those now actually fires now that application.properties trusts
            // the proxy's X-Forwarded-Proto). Referrer-Policy stops the full
            // URL — including any query strings — leaking to third-party
            // resources the map viewer or public pages happen to link to.
            .headers(h -> h
                    .referrerPolicy(r -> r.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .contentSecurityPolicy(c -> c.policyDirectives(CSP)))
            // Reject POST /login from a locked-out IP before the password is checked.
            .addFilterBefore(new LoginAttemptFilter(attempts), UsernamePasswordAuthenticationFilter.class)
            // After authorization: block a "must change password" account from every
            // /api/** call except /api/me and /api/account/** until it changes it.
            .addFilterAfter(new MustChangePasswordFilter(users), AuthorizationFilter.class)
            .authorizeHttpRequests(a -> a
                // public pages + assets (login page needs its JS/CSS to load)
                .requestMatchers("/welcome.html", "/login.html", "/login", "/favicon.ico",
                                 "/manifest.webmanifest",
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
                // saving a personal named map filter is self-service, not a data
                // edit — view-only USER accounts must be able to POST/DELETE here.
                // Ownership (and who may mark one "shared") is enforced inside
                // SavedFilterController against auth.getName().
                .requestMatchers("/api/saved-filters/**").authenticated()
                .requestMatchers("/api/saved-filters").authenticated()
                // The Map Composer's extent endpoint is a READ that has to be a POST — it is
                // handed the section-label list of the active network filter, which is far too
                // long (and too full of slashes) for a query string. Without this rule it would
                // fall to the blanket "POST /api/** is ADMIN" matcher below and a view-only
                // account could open the Composer but never get a map out of it.
                .requestMatchers("/api/composer/**").authenticated()

                // --- view-only (USER) is blocked from every write ---
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
                    attempts.loginSucceeded(LoginAuditService.clientIp(req), auth.getName());
                    audit.recordLogin(auth.getName(), req,
                            req.getSession(false) != null ? req.getSession().getId() : null);
                    res.sendRedirect(req.getContextPath() + "/home.html");
                })
                // Count the failure against BOTH the IP and the account being guessed
                // at (see LoginAttemptService); if either trips its lockout, say so.
                .failureHandler((req, res, ex) -> {
                    String ip = LoginAuditService.clientIp(req);
                    String user = req.getParameter("username");
                    attempts.loginFailed(ip, user);
                    res.sendRedirect(req.getContextPath()
                            + (attempts.isBlocked(ip, user) ? "/login.html?locked" : "/login.html?error"));
                })
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
