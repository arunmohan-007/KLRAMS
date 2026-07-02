package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * KLRAMS / KHRI security.
 *
 *  Public (no login):
 *    - welcome.html  (public KHRI portal: Home / GOs / About / Contact)
 *    - login.html, /login, favicon, /img/** (logos, survey photos)
 *    - /js/**, /css/** (front-end assets the public pages need: 3D scene, styles)
 *    - GET /api/go/folders, /api/go/docs, /api/go/file/**   (read GOs)
 *    - GET /api/site/content                                (About / Contact text)
 *
 *  Authenticated (staff): everything else — the GIS viewer (/map.html), the
 *  internal portal (/home.html), the Data Console (/), and all uploads / edits
 *  (POST & DELETE on /api/go/** and /api/site/**).
 *
 *  One admin account for the pilot; credentials come from application.properties
 *  (app.admin.username / app.admin.password). CSRF is disabled for the pilot so
 *  the existing upload fetch() calls work unchanged; enable it before public hosting.
 */
@Configuration
public class SecurityConfig {

    @Value("${app.admin.username:admin}")
    private String adminUser;

    @Value("${app.admin.password:Klrams@2026}")
    private String adminPass;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService users(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.withUsername(adminUser)
                .password(encoder.encode(adminPass))
                .roles("ADMIN")
                .build());
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a
                // public pages + assets (login page needs its JS/CSS to load)
                .requestMatchers("/welcome.html", "/login.html", "/login", "/favicon.ico",
                                 "/img/**", "/js/**", "/css/**").permitAll()
                // public read-only APIs (Government Orders + About/Contact)
                .requestMatchers(HttpMethod.GET, "/api/go/folders", "/api/go/docs", "/api/go/file/**", "/api/site/content").permitAll()
                // everything else (viewer, console, portal, all uploads/edits) needs login
                .anyRequest().authenticated())
            .formLogin(f -> f
                .loginPage("/login.html")
                .loginProcessingUrl("/login")
                .defaultSuccessUrl("/home.html", true)
                .failureUrl("/login.html?error")
                .permitAll())
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessUrl("/welcome.html")
                .permitAll());
        return http.build();
    }
}
