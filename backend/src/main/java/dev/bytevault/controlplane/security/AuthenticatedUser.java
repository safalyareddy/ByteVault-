package dev.bytevault.controlplane.security;

import java.util.UUID;

/** The principal placed in the SecurityContext by {@link BearerTokenFilter}. */
public record AuthenticatedUser(UUID id, String username, Role role) {}
