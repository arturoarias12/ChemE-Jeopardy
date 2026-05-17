/*
 * File: AppConfig.java
 * Description: Centralized configuration loader for local and cloud deployments.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */
package com.chemejeopardy.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads application settings from command-line arguments, environment variables,
 * and an optional root .env file.
 *
 * <p>This class encapsulates deployment configuration so the entry point can stay
 * small and the rest of the application can receive validated values.</p>
 */
public final class AppConfig {
    /** HTTP port where the embedded server listens. */
    private final int port;

    /** Path to static browser assets. */
    private final Path publicDir;

    /** Source file containing questions, answers, point values, teams, and timers. */
    private final Path definitionPath;

    /** Moderator password loaded from a secret source rather than hard-coded. */
    private final String moderatorPassword;

    private AppConfig(
            int port,
            Path publicDir,
            Path definitionPath,
            String moderatorPassword) {
        this.port = port;
        this.publicDir = publicDir;
        this.definitionPath = definitionPath;
        this.moderatorPassword = moderatorPassword;
    }

    /**
     * Factory method that builds a complete configuration object from the runtime context.
     *
     * @param root repository or container working directory
     * @param args optional arguments; args[0] overrides PORT, args[1] overrides CHEME_GAME_FILE
     * @return validated application configuration
     * @throws IOException if .env cannot be read
     */
    public static AppConfig load(Path root, String[] args) throws IOException {
        Map<String, String> dotEnv = readDotEnv(root.resolve(".env"));
        int port = parsePort(args.length > 0 ? args[0] : value("PORT", dotEnv), 8080);
        Path publicDir = resolvePath(root, value("CHEME_PUBLIC_DIR", dotEnv), "public");
        Path definitionPath = resolvePath(
                root,
                args.length > 1 ? args[1] : value("CHEME_GAME_FILE", dotEnv),
                "data/game-definition.json");
        String moderatorPassword = value("CHEME_MODERATOR_PASSWORD", dotEnv);
        if (moderatorPassword == null || moderatorPassword.isBlank()) {
            throw new IllegalStateException("Set CHEME_MODERATOR_PASSWORD in the environment or in a root .env file.");
        }
        return new AppConfig(
                port,
                publicDir,
                definitionPath,
                moderatorPassword);
    }

    /**
     * @return port used by local browsers and cloud load balancers
     */
    public int port() {
        return port;
    }

    /**
     * @return directory containing HTML, CSS, and JavaScript files
     */
    public Path publicDir() {
        return publicDir;
    }

    /**
     * @return game definition source file
     */
    public Path definitionPath() {
        return definitionPath;
    }

    /**
     * @return moderator password secret
     */
    public String moderatorPassword() {
        return moderatorPassword;
    }

    /**
     * Looks up a setting with real environment variables taking priority over .env.
     */
    private static String value(String key, Map<String, String> dotEnv) {
        String envValue = System.getenv(key);
        if (envValue != null) {
            return envValue;
        }
        return dotEnv.get(key);
    }

    /**
     * Resolves a configured path relative to the app root when it is not absolute.
     */
    private static Path resolvePath(Path root, String configured, String fallback) {
        Path path = Path.of(configured == null || configured.isBlank() ? fallback : configured.trim());
        return path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
    }

    /**
     * Parses the platform-provided port while keeping local runs forgiving.
     */
    private static int parsePort(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed < 65536 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * Parses a small .env file without introducing a deployment dependency.
     */
    private static Map<String, String> readDotEnv(Path path) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return values;
        }
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}
