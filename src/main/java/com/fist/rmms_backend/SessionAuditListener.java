package com.fist.rmms_backend;

import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Closes the open {@code login_events} row the instant the servlet container
 * destroys a session — not just on an explicit {@code /logout}.
 *
 * <p>{@link SecurityConfig}'s logout handler already calls
 * {@link LoginAuditService#recordLogout} when a user clicks "Sign out", but
 * that is the only path that ever did. A closed browser tab, a crashed
 * machine or a lost network never runs it, so the session's row stayed
 * {@code logout_at IS NULL} — "Active" — forever, even though the site's own
 * inactivity rule ({@code server.servlet.session.timeout}) had already
 * expired that session server-side. That is what made the Login Activity
 * report and the Monitoring dashboard's Active Sessions table show devices
 * that had been closed for days.
 *
 * <p>Tomcat's background session reaper invalidates timed-out sessions on
 * its own schedule and fires {@link HttpSessionListener#sessionDestroyed}
 * for every one of them — including idle-timeout, not just explicit
 * {@code session.invalidate()}. Hooking that event means "Active" in both
 * reports now means "has a live session right now", matching the auto
 * sign-out rule instead of "has not yet clicked Sign out". Calling
 * {@code recordLogout} twice for the same session (idle-timeout after an
 * explicit logout already stamped it) is harmless — the method only ever
 * updates the one still-open row, and there is none left to touch.
 */
@Configuration
public class SessionAuditListener {

    @Bean
    public ServletListenerRegistrationBean<HttpSessionListener> sessionAuditListener(LoginAuditService audit) {
        HttpSessionListener listener = new HttpSessionListener() {
            @Override
            public void sessionDestroyed(HttpSessionEvent se) {
                audit.recordLogout(se.getSession().getId());
            }
        };
        return new ServletListenerRegistrationBean<>(listener);
    }
}
