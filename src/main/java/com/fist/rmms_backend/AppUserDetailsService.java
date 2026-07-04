package com.fist.rmms_backend;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.Map;

/**
 * Loads KLRAMS accounts from the {@code app_users} table for Spring Security's
 * form login. Replaces the old single in-memory admin. Boot auto-wires this
 * bean together with the {@code PasswordEncoder} declared in {@link SecurityConfig}.
 *
 * The account's {@code role} becomes the authority {@code ROLE_<role>}; a
 * disabled account cannot authenticate.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserService users;

    public AppUserDetailsService(UserService users){ this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Map<String,Object> u = users.findByUsername(username);
        if(u == null) throw new UsernameNotFoundException("No such user: " + username);
        boolean enabled = Boolean.TRUE.equals(u.get("enabled"));
        return User.withUsername((String) u.get("username"))
                .password((String) u.get("password_hash"))
                .roles((String) u.get("role"))   // becomes ROLE_<role>
                .disabled(!enabled)
                .build();
    }
}
