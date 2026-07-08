package site.kpeclub.filemanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles one-time login tokens (generated via /filemgr), an optional
 * password step in between, and the longer-lived browser sessions they
 * eventually get exchanged for.
 */
public class TokenManager {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long PENDING_EXPIRY_MILLIS = 5 * 60_000L;

    private volatile long tokenExpiryMillis;
    private volatile long sessionExpiryMillis;

    private final ConcurrentHashMap<String, LoginToken> loginTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingSession> pendingSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    // Keyed by client IP. These only count failed attempts, so normal usage
    // (including console polling every ~1.5s with a valid session) never
    // trips them — only repeated invalid/guessed tokens, passwords, or
    // session ids do.
    private final RateLimiter tokenRedeemLimiter = new RateLimiter(10, 5 * 60_000L);
    private final RateLimiter authFailureLimiter = new RateLimiter(30, 5 * 60_000L);
    private final RateLimiter passwordAttemptLimiter = new RateLimiter(10, 5 * 60_000L);

    public TokenManager(long tokenExpiryMinutes, long sessionExpiryHours) {
        this.tokenExpiryMillis = tokenExpiryMinutes * 60_000L;
        this.sessionExpiryMillis = sessionExpiryHours * 3_600_000L;
    }

    /**
     * Updates token/session lifetimes live (used by /filemgr reload). Only
     * affects tokens/sessions issued from now on — existing ones keep the
     * expiry they were created with, so a reload never logs anyone out.
     */
    public void updateExpiries(long tokenExpiryMinutes, long sessionExpiryHours) {
        this.tokenExpiryMillis = tokenExpiryMinutes * 60_000L;
        this.sessionExpiryMillis = sessionExpiryHours * 3_600_000L;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Generates a fresh one-time login token for the given player. */
    public String generateLoginToken(UUID playerId, String playerName) {
        cleanup();
        String token = randomToken();
        loginTokens.put(token, new LoginToken(playerId, playerName, System.currentTimeMillis() + tokenExpiryMillis));
        return token;
    }

    /** True if this IP is still allowed to attempt a token redemption. */
    public boolean allowTokenRedeemAttempt(String ip) {
        return tokenRedeemLimiter.allow(ip);
    }

    /** True if this IP is still allowed another failed-auth attempt (session check). */
    public boolean allowAuthFailure(String ip) {
        return authFailureLimiter.allow(ip);
    }

    /** True if this IP is still allowed another password attempt. */
    public boolean allowPasswordAttempt(String ip) {
        return passwordAttemptLimiter.allow(ip);
    }

    /**
     * Redeems a one-time login token, invalidating it, and returns a new
     * session id directly if the token was valid and unexpired. Used when no
     * extra login-password is configured. Returns null otherwise.
     */
    public String redeemToken(String token) {
        LoginToken lt = loginTokens.remove(token);
        if (lt == null) return null;
        if (System.currentTimeMillis() > lt.expiresAt) return null;
        return createSession(lt.playerId, lt.playerName);
    }

    /**
     * Redeems a one-time login token into a short-lived "pending" state
     * instead of a full session, so a password can be required in between.
     * Returns a pending id to hand back on the password form, or null if the
     * token was invalid/expired.
     */
    public String redeemTokenToPending(String token) {
        LoginToken lt = loginTokens.remove(token);
        if (lt == null) return null;
        if (System.currentTimeMillis() > lt.expiresAt) return null;

        String pendingId = randomToken();
        pendingSessions.put(pendingId, new PendingSession(lt.playerId, lt.playerName, System.currentTimeMillis() + PENDING_EXPIRY_MILLIS));
        return pendingId;
    }

    /** True if this pending id still exists and hasn't expired. */
    public boolean pendingSessionValid(String pendingId) {
        if (pendingId == null) return false;
        PendingSession p = pendingSessions.get(pendingId);
        if (p == null) return false;
        if (System.currentTimeMillis() > p.expiresAt) {
            pendingSessions.remove(pendingId);
            return false;
        }
        return true;
    }

    /**
     * Checks the entered password against the configured one for a still-valid
     * pending session. Only call after pendingSessionValid() returned true —
     * a null return here then unambiguously means "wrong password" (the
     * pending session itself is left alone so the player can retry).
     * On success, consumes the pending session and returns a new session id.
     */
    public String confirmPendingSession(String pendingId, String enteredPassword, String configuredPassword) {
        PendingSession p = pendingSessions.get(pendingId);
        if (p == null) return null;
        if (!constantTimeEquals(enteredPassword, configuredPassword)) return null;
        pendingSessions.remove(pendingId);
        return createSession(p.playerId, p.playerName);
    }

    private String createSession(UUID playerId, String playerName) {
        String sessionId = randomToken();
        sessions.put(sessionId, new Session(playerId, playerName, System.currentTimeMillis() + sessionExpiryMillis));
        return sessionId;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** Returns the player name behind a session id, or null if invalid/expired. */
    public String validateSession(String sessionId) {
        if (sessionId == null) return null;
        Session s = sessions.get(sessionId);
        if (s == null) return null;
        if (System.currentTimeMillis() > s.expiresAt) {
            sessions.remove(sessionId);
            return null;
        }
        return s.playerName;
    }

    /** Immediately revokes a session (used by the logout button). */
    public void invalidateSession(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        loginTokens.values().removeIf(t -> now > t.expiresAt);
        pendingSessions.values().removeIf(p -> now > p.expiresAt);
        sessions.values().removeIf(s -> now > s.expiresAt);
        tokenRedeemLimiter.cleanup();
        authFailureLimiter.cleanup();
        passwordAttemptLimiter.cleanup();
    }

    private static class LoginToken {
        final UUID playerId;
        final String playerName;
        final long expiresAt;

        LoginToken(UUID playerId, String playerName, long expiresAt) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.expiresAt = expiresAt;
        }
    }

    private static class PendingSession {
        final UUID playerId;
        final String playerName;
        final long expiresAt;

        PendingSession(UUID playerId, String playerName, long expiresAt) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.expiresAt = expiresAt;
        }
    }

    private static class Session {
        final UUID playerId;
        final String playerName;
        final long expiresAt;

        Session(UUID playerId, String playerName, long expiresAt) {
            this.playerId = playerId;
            this.playerName = playerName;
            this.expiresAt = expiresAt;
        }
    }
}
