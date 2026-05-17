/*
 * File: AuthManager.java
 * Description: Lightweight authentication and session manager for moderator/player access.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */
package com.chemejeopardy.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps authentication concerns separate from route handling and game rules.
 *
 * <p>The class acts as a small security proxy: AppServer asks it whether a request
 * may proceed before delegating protected work to GameEngine.</p>
 */
public final class AuthManager {
    /** Cookie name used to store the moderator session token. */
    private static final String MODERATOR_COOKIE = "cheme_mod_session";

    /** Moderator session lifetime in seconds. */
    private static final long SESSION_SECONDS = 12 * 60 * 60;

    /** Strong randomness used for unguessable session tokens. */
    private final SecureRandom random = new SecureRandom();

    /** In-memory session table storing token expiration times. */
    private final Map<String, Long> moderatorSessions = new ConcurrentHashMap<>();

    /** Cookie name for this auth scope. Multi-game hosting gives each game its own cookie. */
    private final String moderatorCookie;

    /** Server-administrator password loaded from configuration. */
    private final String moderatorPassword;

    /** Player join password controlled by the moderator during the event. */
    private volatile String playerJoinPassword;

    /**
     * Creates a manager with the administrator password and optional player password.
     */
    public AuthManager(String moderatorPassword, String initialPlayerJoinPassword) {
        this(moderatorPassword, initialPlayerJoinPassword, MODERATOR_COOKIE);
    }

    /**
     * Creates a manager with an explicit moderator cookie name for a specific game or master view.
     */
    public AuthManager(String moderatorPassword, String initialPlayerJoinPassword, String moderatorCookie) {
        this.moderatorPassword = moderatorPassword;
        this.moderatorCookie = moderatorCookie == null || moderatorCookie.isBlank()
                ? MODERATOR_COOKIE
                : moderatorCookie;
        setInitialPlayerJoinPassword(initialPlayerJoinPassword);
    }

    /**
     * @return true when the supplied password matches the configured moderator password
     */
    public boolean authenticateModerator(String password) {
        return secureEquals(moderatorPassword, password);
    }

    /**
     * Creates a new moderator session token.
     */
    public String createModeratorSession() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        moderatorSessions.put(token, System.currentTimeMillis() + SESSION_SECONDS * 1000);
        return token;
    }

    /**
     * Checks whether an HTTP request carries a live moderator session cookie.
     */
    public boolean isModeratorAuthenticated(HttpExchange exchange) {
        String token = cookieValue(exchange.getRequestHeaders(), moderatorCookie);
        if (token == null) {
            return false;
        }
        Long expiresAt = moderatorSessions.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt < System.currentTimeMillis()) {
            moderatorSessions.remove(token);
            return false;
        }
        return true;
    }

    /**
     * Revokes the moderator session attached to the current request, if any.
     */
    public void revokeModeratorSession(HttpExchange exchange) {
        String token = cookieValue(exchange.getRequestHeaders(), moderatorCookie);
        if (token != null) {
            moderatorSessions.remove(token);
        }
    }

    /**
     * Builds a Set-Cookie header value for a live moderator session.
     */
    public String moderatorSessionCookie(String token) {
        return moderatorCookie + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + SESSION_SECONDS;
    }

    /**
     * Builds a Set-Cookie header value that clears the moderator session.
     */
    public String expiredModeratorSessionCookie() {
        return moderatorCookie + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    /**
     * @return true when players can join using a password
     */
    public boolean isPlayerJoinPasswordSet() {
        String current = playerJoinPassword;
        return current != null && !current.isBlank();
    }

    /**
     * @return true when a player provided the current join password
     */
    public boolean authenticatePlayerJoin(String password) {
        String current = playerJoinPassword;
        return current != null && secureEquals(current, password);
    }

    /**
     * Updates the player join password for future join attempts.
     */
    public void setPlayerJoinPassword(String password) {
        playerJoinPassword = clean(password);
    }

    /**
     * @return public security metadata safe for player and display clients
     */
    public Map<String, Object> publicSecurityState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("playerJoinPasswordSet", isPlayerJoinPasswordSet());
        return state;
    }

    /**
     * @return moderator-only security metadata
     */
    public Map<String, Object> moderatorSecurityState() {
        Map<String, Object> state = publicSecurityState();
        state.put("authenticated", true);
        return state;
    }

    /**
     * Initializes player access without forcing a password when none was configured.
     */
    private void setInitialPlayerJoinPassword(String password) {
        String cleaned = clean(password);
        playerJoinPassword = cleaned.isBlank() ? null : cleaned;
    }

    /**
     * Normalizes passwords before comparison.
     */
    private static String clean(String value) {
        return value == null ? "" : value.replace("\r", "").trim();
    }

    /**
     * Constant-time byte comparison avoids obvious timing leaks.
     */
    private static boolean secureEquals(String expected, String actual) {
        byte[] expectedBytes = clean(expected).getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = clean(actual).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    /**
     * Extracts a single cookie value from request headers.
     */
    private static String cookieValue(Headers headers, String name) {
        List<String> cookieHeaders = headers.get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            String[] cookies = header.split(";");
            for (String cookie : cookies) {
                String[] pieces = cookie.trim().split("=", 2);
                if (pieces.length == 2 && pieces[0].equals(name)) {
                    return pieces[1];
                }
            }
        }
        return null;
    }
}
