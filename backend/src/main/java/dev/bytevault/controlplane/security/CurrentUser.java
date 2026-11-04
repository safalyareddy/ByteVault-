package dev.bytevault.controlplane.security;

import dev.bytevault.controlplane.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Access to the authenticated caller. The owner of objects is the username. */
@Component
public class CurrentUser {

    public AuthenticatedUser require() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return u;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "authentication required");
    }

    public String username() {
        return require().username();
    }

    /** For admin-only operations: returns the actor recorded in the audit log. */
    public String requireAdmin() {
        AuthenticatedUser u = require();
        if (u.role() != Role.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "ADMIN role required");
        }
        return u.username();
    }
}
