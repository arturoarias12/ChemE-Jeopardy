/*
 * File: Main.java
 * Description: Application entry point that wires configuration, authentication, and the HTTP server.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */
package com.chemejeopardy;

import com.chemejeopardy.config.AppConfig;
import com.chemejeopardy.server.AppServer;

/**
 * Bootstraps the ChemE Jeopardy web application.
 *
 * <p>This class intentionally acts as a small composition root: it creates the
 * configuration object, the game engine, the security helper, and the server.</p>
 */
public final class Main {
    /** Utility class constructor kept private to prevent accidental instantiation. */
    private Main() {
    }

    /**
     * Starts the server. Optional args: {@code [port] [game-definition-file]}.
     *
     * @param args command-line overrides used mainly by local scripts
     * @throws Exception when required configuration is missing or the server cannot start
     */
    public static void main(String[] args) throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        AppConfig config = AppConfig.load(root, args);

        AppServer server = new AppServer(
                root,
                config.definitionPath(),
                config.publicDir(),
                config.port(),
                config.moderatorPassword());
        server.start();

        System.out.println("ChemE Jeopardy is running.");
        System.out.println("Admin home: http://localhost:" + config.port() + "/");
        System.out.println("Default question file for new games: " + config.definitionPath());
        System.out.println("Admin password: configured.");
        System.out.println("No game rooms are created until the admin creates one.");
        System.out.println("Press Ctrl+C to stop.");
    }
}
