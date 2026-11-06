package dev.bytevault.controlplane.web;

import org.springframework.stereotype.Component;

/**
 * Resolves the owner of the current request. Until authentication lands (Phase 6) every request
 * acts as the single local owner below; the API is only bound to 127.0.0.1 in the meantime
 * (threat model T5). Callers already scope every query by owner, so adding auth changes only this.
 */
@Component
public class CurrentOwner {

    public static final String LOCAL_OWNER = "local-dev";

    public String id() {
        return LOCAL_OWNER;
    }
}
