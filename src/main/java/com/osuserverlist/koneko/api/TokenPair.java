package com.osuserverlist.koneko.api;

import java.time.Instant;

import lombok.Getter;

/**
 * An OAuth2 access/refresh pair as returned by {@code POST /api/v1/oauth/token}.
 *
 * <p>These never leave the server: the browser only ever sees the opaque
 * koneko-web session cookie.
 */
@Getter
public final class TokenPair {

    private final String accessToken;
    private final String refreshToken;
    private final String scope;
    private final Instant accessExpiresAt;
    private final Instant refreshExpiresAt;

    public TokenPair(String accessToken, String refreshToken, String scope,
            long expiresInSeconds, long refreshExpiresInSeconds) {

        Instant now = Instant.now();

        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.scope = scope == null ? "" : scope;
        this.accessExpiresAt = now.plusSeconds(expiresInSeconds);
        this.refreshExpiresAt = now.plusSeconds(refreshExpiresInSeconds);
    }

    /** Rebuilds a pair that was saved earlier, keeping its original expiry times. */
    public TokenPair(String accessToken, String refreshToken, String scope,
            Instant accessExpiresAt, Instant refreshExpiresAt) {

        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.scope = scope == null ? "" : scope;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
    }

    /**
     * True when the access token is gone, or close enough to it that a request
     * started now could arrive after it expired.
     */
    public boolean needsRefresh() {
        return Instant.now().isAfter(accessExpiresAt.minusSeconds(60));
    }

    /** True when even refreshing is pointless and the user has to log in again. */
    public boolean isDead() {
        return Instant.now().isAfter(refreshExpiresAt);
    }
}
