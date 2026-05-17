/*
 * File: AppServer.java
 * Description: Embedded HTTP server, route registry, static asset server, and SSE broadcaster.
 * Author: Arturo Arias
 * Last updated: 2026-05-15
 */
package com.chemejeopardy.server;

import com.chemejeopardy.game.GameEngine;
import com.chemejeopardy.util.Json;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Adapts HTTP requests into calls on one of several GameEngine instances.
 *
 * <p>Each hosted game has its own rules engine, authentication manager, and SSE hubs.
 * The first URL segment selects the game, so /game-1/player and /game-2/player can
 * run in parallel without sharing runtime state.</p>
 */
public final class AppServer {
    /** Game IDs become URL path segments, so keep them simple and predictable. */
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]{0,38}");

    /** Upper bound for browser-uploaded JSON question sets. */
    private static final int MAX_UPLOAD_BYTES = 1_000_000;

    /** Upper bound for browser-uploaded image question packages. */
    private static final int MAX_PACKAGE_BYTES = 25_000_000;

    /** Upper bound for files extracted from one image package. */
    private static final int MAX_EXTRACTED_PACKAGE_BYTES = 50_000_000;

    /** Repository or container working directory. */
    private final Path rootDir;

    /** Static asset directory served to browsers. */
    private final Path publicDir;

    /** Startup question source used as the template for newly created games. */
    private final Path defaultDefinitionPath;

    /** Runtime-only extracted package assets, safe to discard between deployments. */
    private final Path runtimeAssetsDir;

    /** Master auth protects game-instance creation. */
    private final AuthManager masterAuthManager;

    /** Active game sessions keyed by URL slug. */
    private final Map<String, GameSession> games = new ConcurrentHashMap<>();

    /** JDK embedded HTTP server. */
    private final HttpServer server;

    /**
     * Creates and configures the embedded server.
     */
    public AppServer(
            Path rootDir,
            Path defaultDefinitionPath,
            Path publicDir,
            int port,
            String moderatorPassword) throws IOException {
        this.rootDir = rootDir.toAbsolutePath().normalize();
        this.publicDir = publicDir.toAbsolutePath().normalize();
        this.defaultDefinitionPath = defaultDefinitionPath.toAbsolutePath().normalize();
        String envRuntimeDir = System.getenv("CHEME_RUNTIME_ASSETS_DIR");
        this.runtimeAssetsDir = (envRuntimeDir != null && !envRuntimeDir.isBlank())
                ? Path.of(envRuntimeDir.trim()).toAbsolutePath().normalize()
                : Path.of(System.getProperty("java.io.tmpdir")).resolve("cheme-jeopardy-runtime").toAbsolutePath().normalize();
        this.masterAuthManager = new AuthManager(moderatorPassword, "", "cheme_master_session");
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        registerRoutes();
    }

    /**
     * Starts accepting HTTP requests.
     */
    public void start() {
        server.start();
    }

    /**
     * Stops accepting requests after the requested delay.
     */
    public void stop(int delaySeconds) {
        server.stop(delaySeconds);
    }

    /**
     * Registers a single dispatcher so dynamic game IDs can be resolved at request time.
     */
    private void registerRoutes() {
        server.createContext("/", this::handleRequest);
    }

    /**
     * Top-level request dispatcher for master, static, and per-game routes.
     */
    private void handleRequest(HttpExchange exchange) throws IOException {
        String path = cleanPath(exchange.getRequestURI().getPath());
        if (path.equals("/") || path.equals("/games")) {
            servePage(exchange, "games.html");
            return;
        }
        if (path.startsWith("/assets")) {
            serveStatic(exchange);
            return;
        }
        if (path.equals("/player") || path.equals("/moderator") || path.equals("/display")) {
            redirect(exchange, "/games");
            return;
        }
        if (path.equals("/api/games") || path.equals("/api/games/update") || path.equals("/api/games/delete") || path.startsWith("/api/master")) {
            handleMasterRoute(exchange, path);
            return;
        }

        GameRoute route = resolveGameRoute(path);
        if (route.session == null) {
            notFound(exchange);
            return;
        }
        handleGameRoute(exchange, route.session, route.localPath);
    }

    /**
     * Handles the master game-instance API.
     */
    private void handleMasterRoute(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/games")) {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 200, gamesListPayload());
                return;
            }
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCreateGame(exchange);
                return;
            }
            methodNotAllowed(exchange, "GET, POST");
            return;
        }
        if (path.equals("/api/games/update")) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            handleUpdateGame(exchange);
            return;
        }
        if (path.equals("/api/games/delete")) {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            handleDeleteGame(exchange);
            return;
        }
        if (path.equals("/api/master/auth-status")) {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            Map<String, Object> response = ok("Master status loaded.");
            response.put("authenticated", masterAuthManager.isModeratorAuthenticated(exchange));
            writeJson(exchange, 200, response);
            return;
        }
        if (path.equals("/api/master/login")) {
            handleMasterLogin(exchange);
            return;
        }
        if (path.equals("/api/master/logout")) {
            handleMasterLogout(exchange);
            return;
        }
        notFound(exchange);
    }

    /**
     * Handles game-specific pages and APIs after a game session has been selected.
     */
    private void handleGameRoute(HttpExchange exchange, GameSession session, String localPath) throws IOException {
        if (localPath.startsWith("/package-assets/")) {
            servePackageAsset(exchange, session, localPath);
            return;
        }
        switch (localPath) {
            case "/" -> redirect(exchange, "/" + session.id + "/player");
            case "/player" -> servePage(exchange, "player.html");
            case "/moderator" -> servePage(exchange, "moderator.html");
            case "/display" -> servePage(exchange, "display.html");
            case "/api/events" -> handleEvents(exchange, session, false);
            case "/api/mod/events" -> handleEvents(exchange, session, true);
            case "/api/state" -> writeJson(exchange, 200, publicState(session));
            case "/api/mod/auth-status" -> handleModeratorAuthStatus(exchange, session);
            case "/api/mod/login" -> handleModeratorLogin(exchange, session);
            case "/api/mod/logout" -> handleModeratorLogout(exchange, session);
            case "/api/mod/state" -> {
                if (requireModerator(exchange, session)) {
                    writeJson(exchange, 200, moderatorState(session));
                }
            }
            case "/api/time-sync" -> writeJson(exchange, 200, session.engine.moderatorTime());
            case "/api/player/join" -> handleFormPost(exchange, form -> {
                if (!session.authManager.isPlayerJoinPasswordSet()) {
                    return error("The moderator needs to set the player password before players can join.");
                }
                if (!session.authManager.authenticatePlayerJoin(form.getOrDefault("joinPassword", ""))) {
                    return error("That player password is not correct.");
                }
                return session.engine.joinPlayer(
                        form.getOrDefault("teamId", ""),
                        form.getOrDefault("displayName", ""));
            });
            case "/api/player/sync" -> handleFormPost(exchange, form ->
                    session.engine.registerSync(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            parseLong(form.get("clientSentAt")),
                            parseLong(form.get("clientReceivedAt"))));
            case "/api/player/buzz" -> handleFormPost(exchange, form ->
                    session.engine.submitBuzz(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            parseLong(form.get("syncedTimestamp"))));
            case "/api/player/daily-wager" -> handleFormPost(exchange, form ->
                    session.engine.setDailyWager(
                            form.getOrDefault("teamId", ""),
                            parseInt(form.get("wager")),
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            false));
            case "/api/player/final-wager" -> handleFormPost(exchange, form ->
                    session.engine.submitFinalWager(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            parseInt(form.get("wager"))));
            case "/api/player/final-response" -> handleFormPost(exchange, form ->
                    session.engine.submitFinalResponse(
                            form.getOrDefault("playerId", ""),
                            form.getOrDefault("sessionKey", ""),
                            form.getOrDefault("response", "")));
            case "/api/mod/definition" -> handleUpdateDefinition(exchange, session);
            case "/api/mod/load-file" -> handleLoadDefinitionFile(exchange, session);
            case "/api/mod/upload-definition" -> handleUploadDefinition(exchange, session);
            case "/api/mod/player-password" -> handlePlayerPasswordChange(exchange, session);
            case "/api/mod/display-theme" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.setDisplayTheme(
                            form.getOrDefault("color", ""),
                            form.getOrDefault("mode", "")));
            case "/api/mod/reset" -> handleModeratorPostNoBody(exchange, session, session.engine::resetRuntime);
            case "/api/mod/start" -> handleModeratorPostNoBody(exchange, session, session.engine::startGame);
            case "/api/mod/select-clue" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.selectClue(form.getOrDefault("clueId", "")));
            case "/api/mod/finish-reading" -> handleModeratorPostNoBody(exchange, session, session.engine::finishReading);
            case "/api/mod/judge-correct" -> handleModeratorPostNoBody(exchange, session, () -> session.engine.judgeCurrent(true));
            case "/api/mod/judge-incorrect" -> handleModeratorPostNoBody(exchange, session, () -> session.engine.judgeCurrent(false));
            case "/api/mod/continue" -> handleModeratorPostNoBody(exchange, session, session.engine::continueAfterReveal);
            case "/api/mod/daily-wager" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.setDailyWager(
                            form.getOrDefault("teamId", ""),
                            parseInt(form.get("wager")),
                            "",
                            "",
                            true));
            case "/api/mod/start-daily-double" -> handleModeratorPostNoBody(exchange, session, session.engine::startDailyDouble);
            case "/api/mod/adjust-score" -> handleModeratorFormPost(exchange, session, form ->
                    session.engine.adjustScore(form.getOrDefault("teamId", ""), parseInt(form.get("delta"))));
            case "/api/mod/start-final-wager" -> handleModeratorPostNoBody(exchange, session, session.engine::startFinalWager);
            case "/api/mod/reveal-final-clue" -> handleModeratorPostNoBody(exchange, session, session.engine::revealFinalClue);
            case "/api/mod/start-tiebreaker" -> handleModeratorPostNoBody(exchange, session, session.engine::startTieBreaker);
            case "/api/mod/next-tiebreaker" -> handleModeratorPostNoBody(exchange, session, session.engine::nextTieBreakerClue);
            default -> notFound(exchange);
        }
    }

    /**
     * Creates a new runtime game from the master page.
     */
    private void handleCreateGame(HttpExchange exchange) throws IOException {
        if (!requireMaster(exchange)) {
            return;
        }
        Map<String, Object> payload = readRequestMap(exchange);
        String requestedId = slugOrDefault(payloadString(payload, "gameId"));
        if (!isValidGameId(requestedId)) {
            writeJson(exchange, error("Use a game URL slug like game-2 or finals-room."));
            return;
        }
        if (isReservedPath(requestedId)) {
            writeJson(exchange, error("That game URL is reserved by the app."));
            return;
        }
        if (games.containsKey(requestedId)) {
            writeJson(exchange, error("That game already exists."));
            return;
        }
        String moderatorPassword = payloadString(payload, "moderatorPassword");
        if (moderatorPassword.isBlank()) {
            writeJson(exchange, error("Enter a moderator password for the new game."));
            return;
        }

        Path definitionPath = defaultDefinitionPath;
        String sourcePath = payloadString(payload, "sourcePath");
        String uploadedContent = payloadString(payload, "uploadedContent");
        String uploadedContentBase64 = payloadString(payload, "uploadedContentBase64");
        String uploadedFileName = cleanFileName(payloadString(payload, "uploadedFileName"));
        boolean hasUpload = !uploadedContent.isBlank() || !uploadedContentBase64.isBlank();
        try {
            if (!hasUpload && !sourcePath.isBlank()) {
                definitionPath = resolveExistingFile(sourcePath);
            }
            GameSession session = createGameSession(
                    requestedId,
                    payloadString(payload, "displayName").isBlank()
                            ? displayNameFromId(requestedId)
                            : cleanText(payloadString(payload, "displayName")),
                    definitionPath,
                    false,
                    moderatorPassword,
                    payloadString(payload, "playerPassword"));
            if (hasUpload) {
                UploadedQuestionSet uploaded = readUploadedQuestionSet(session, uploadedFileName, uploadedContent, uploadedContentBase64);
                Map<String, Object> loadResult = applyUploadedQuestionSet(session, uploaded);
                if (!Json.asBoolean(loadResult.get("ok"), false)) {
                    writeJson(exchange, loadResult);
                    return;
                }
            }
            games.put(session.id, session);
            Map<String, Object> response = ok("Game created.");
            response.put("game", gameSummary(session));
            writeJson(exchange, 200, response);
        } catch (Exception ex) {
            writeJson(exchange, error("Unable to create game: " + ex.getMessage()));
        }
    }

    /**
     * Updates a game room's admin-facing title and URL slug.
     */
    private void handleUpdateGame(HttpExchange exchange) throws IOException {
        if (!requireMaster(exchange)) {
            return;
        }
        Map<String, Object> payload = readRequestMap(exchange);
        String currentId = cleanText(payloadString(payload, "currentGameId"));
        String requestedId = slugOrDefault(payloadString(payload, "gameId"));
        String displayName = cleanText(payloadString(payload, "displayName"));
        if (currentId.isBlank()) {
            writeJson(exchange, error("Choose a game to update."));
            return;
        }
        if (!isValidGameId(requestedId)) {
            writeJson(exchange, error("Use a game URL slug like game-2 or finals-room."));
            return;
        }
        if (isReservedPath(requestedId)) {
            writeJson(exchange, error("That game URL is reserved by the app."));
            return;
        }
        synchronized (games) {
            GameSession session = games.get(currentId);
            if (session == null) {
                writeJson(exchange, error("That game no longer exists."));
                return;
            }
            if (!requestedId.equals(currentId) && games.containsKey(requestedId)) {
                writeJson(exchange, error("Another game already uses that URL."));
                return;
            }
            if (displayName.isBlank()) {
                displayName = displayNameFromId(requestedId);
            }
            if (!requestedId.equals(currentId)) {
                games.remove(currentId);
                session.id = requestedId;
                games.put(requestedId, session);
            }
            session.displayName = displayName;
            broadcastState(session);
            Map<String, Object> response = ok("Game updated.");
            response.put("game", gameSummary(session));
            writeJson(exchange, 200, response);
        }
    }

    /**
     * Removes a game session and cleans up any uploaded package assets.
     */
    private void handleDeleteGame(HttpExchange exchange) throws IOException {
        if (!requireMaster(exchange)) {
            return;
        }
        Map<String, Object> payload = readRequestMap(exchange);
        String gameId = cleanText(payloadString(payload, "gameId"));
        if (gameId.isBlank()) {
            writeJson(exchange, error("Choose a game to delete."));
            return;
        }
        GameSession session;
        synchronized (games) {
            session = games.remove(gameId);
        }
        if (session == null) {
            writeJson(exchange, error("That game no longer exists."));
            return;
        }
        try {
            deleteDirectory(session.packageAssetDir);
        } catch (IOException ignored) {
            // Best-effort cleanup; game is already removed from active sessions.
        }
        writeJson(exchange, 200, ok("Game \"" + gameId + "\" deleted."));
    }

    /**
     * Authenticates the master game manager.
     */
    private void handleMasterLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        if (!masterAuthManager.authenticateModerator(form.getOrDefault("password", ""))) {
            writeJson(exchange, 401, error("Master password is not correct."));
            return;
        }
        String token = masterAuthManager.createModeratorSession();
        exchange.getResponseHeaders().add("Set-Cookie", masterAuthManager.moderatorSessionCookie(token));
        writeJson(exchange, 200, ok("Game manager unlocked."));
    }

    /**
     * Revokes the master manager session.
     */
    private void handleMasterLogout(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        masterAuthManager.revokeModeratorSession(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", masterAuthManager.expiredModeratorSessionCookie());
        writeJson(exchange, 200, ok("Game manager locked."));
    }

    /**
     * Reports whether the current browser already has a moderator session for this game.
     */
    private void handleModeratorAuthStatus(HttpExchange exchange, GameSession session) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Map<String, Object> response = ok("Moderator status loaded.");
        response.put("authenticated", session.authManager.isModeratorAuthenticated(exchange));
        writeJson(exchange, 200, response);
    }

    /**
     * Authenticates a game moderator and issues a game-scoped session cookie.
     */
    private void handleModeratorLogin(HttpExchange exchange, GameSession session) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        if (!session.authManager.authenticateModerator(form.getOrDefault("password", ""))) {
            writeJson(exchange, 401, error("Moderator password is not correct."));
            return;
        }
        String token = session.authManager.createModeratorSession();
        exchange.getResponseHeaders().add("Set-Cookie", session.authManager.moderatorSessionCookie(token));
        writeJson(exchange, 200, ok("Moderator unlocked."));
    }

    /**
     * Revokes the game moderator session cookie.
     */
    private void handleModeratorLogout(HttpExchange exchange, GameSession session) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        session.authManager.revokeModeratorSession(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", session.authManager.expiredModeratorSessionCookie());
        writeJson(exchange, 200, ok("Moderator locked."));
    }

    /**
     * Updates the password that players must enter before joining a team in one game.
     */
    private void handlePlayerPasswordChange(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        String password = form.getOrDefault("password", "");
        if (password.isBlank()) {
            writeJson(exchange, error("Enter a player password before saving."));
            return;
        }
        session.authManager.setPlayerJoinPassword(password);
        broadcastState(session);
        writeJson(exchange, ok("Player password saved. Share it only with this game's players."));
    }

    /**
     * Saves game settings and team names from the moderator setup screen.
     */
    private void handleUpdateDefinition(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        try {
            String body = readBody(exchange);
            writeJson(exchange, session.engine.updateDefinition(Json.parse(body)));
        } catch (Exception ex) {
            writeJson(exchange, 400, error("Unable to save the definition: " + ex.getMessage()));
        }
    }

    /**
     * Loads a question set from a server-side JSON file path.
     */
    private void handleLoadDefinitionFile(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        try {
            Path source = resolveExistingFile(form.getOrDefault("path", ""));
            Object parsed = Json.parse(Files.readString(source, StandardCharsets.UTF_8));
            String label = displayPath(source);
            Map<String, Object> result = session.engine.loadQuestionSet(parsed, label);
            if (Json.asBoolean(result.get("ok"), false)) {
                session.questionSourceLabel = label;
                session.uploadedFileName = "";
                result.put("questionSource", session.questionSourceMap());
                broadcastState(session);
            }
            writeJson(exchange, result);
        } catch (Exception ex) {
            writeJson(exchange, error("Unable to load question file: " + ex.getMessage()));
        }
    }

    /**
     * Loads a browser-uploaded JSON file or ZIP game package into memory for this game only.
     */
    private void handleUploadDefinition(HttpExchange exchange, GameSession session) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        String body = readBody(exchange);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_PACKAGE_BYTES * 2) {
            writeJson(exchange, error("Uploaded question set is too large."));
            return;
        }
        try {
            Map<String, Object> payload = Json.asObject(Json.parse(body));
            String fileName = cleanFileName(Json.asString(payload.get("fileName"), "uploaded-game.json"));
            String content = Json.asString(payload.get("content"), "");
            String contentBase64 = Json.asString(payload.get("contentBase64"), "");
            if (content.isBlank() && contentBase64.isBlank()) {
                writeJson(exchange, error("Choose a JSON game file or image package before uploading."));
                return;
            }
            boolean replacedUpload = !session.uploadedFileName.isBlank();
            UploadedQuestionSet uploaded = readUploadedQuestionSet(session, fileName, content, contentBase64);
            Map<String, Object> result = applyUploadedQuestionSet(session, uploaded);
            if (Json.asBoolean(result.get("ok"), false)) {
                if (replacedUpload) {
                    result.put("message", result.get("message") + " Previous uploaded file replaced.");
                }
                result.put("replacedUpload", replacedUpload);
                result.put("questionSource", session.questionSourceMap());
                broadcastState(session);
            }
            writeJson(exchange, result);
        } catch (Exception ex) {
            writeJson(exchange, error("Unable to load uploaded question set: " + ex.getMessage()));
        }
    }

    /**
     * Parses a browser-uploaded JSON file or ZIP package. Packages contain game.json plus images/.
     */
    private UploadedQuestionSet readUploadedQuestionSet(
            GameSession session,
            String fileName,
            String content,
            String contentBase64) throws IOException {
        if (isPackageFile(fileName) || !contentBase64.isBlank()) {
            return readUploadedPackage(session, fileName, contentBase64);
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("Uploaded question set is too large.");
        }
        Object definition = Json.parse(stripUtf8Bom(content));
        return new UploadedQuestionSet(definition, fileName, "Uploaded file: " + fileName, null, false);
    }

    /**
     * Extracts a ZIP package into a temporary runtime directory after validating its contents.
     */
    private UploadedQuestionSet readUploadedPackage(GameSession session, String fileName, String contentBase64) throws IOException {
        if (contentBase64.isBlank()) {
            throw new IllegalArgumentException("Image packages must be uploaded as a ZIP or .chemej file.");
        }
        byte[] packageBytes;
        try {
            packageBytes = Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Uploaded package is not valid base64 data.");
        }
        if (packageBytes.length > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Uploaded image package is too large.");
        }

        Files.createDirectories(runtimeAssetsDir);
        Path tempDir = Files.createTempDirectory(runtimeAssetsDir, session.id + "-next-");
        String gameJson = null;
        long[] extractedBytes = {0L};
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = normalizePackagePath(entry.getName());
                if (entryName.isBlank()) {
                    continue;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if ("game.json".equals(entryName)) {
                    gameJson = new String(readZipEntry(zip, MAX_UPLOAD_BYTES), StandardCharsets.UTF_8);
                    continue;
                }
                if (!entryName.startsWith("images/") || !isAllowedImageFile(entryName)) {
                    throw new IllegalArgumentException("Packages may contain only game.json and image files under images/.");
                }
                Path target = tempDir.resolve(entryName.replace('/', java.io.File.separatorChar)).normalize();
                if (!target.startsWith(tempDir)) {
                    throw new IllegalArgumentException("Package contains an unsafe file path.");
                }
                Files.createDirectories(target.getParent());
                copyZipEntry(zip, target, extractedBytes);
            }
            if (gameJson == null || gameJson.isBlank()) {
                throw new IllegalArgumentException("Image packages must include game.json at the package root.");
            }
            Object definition = Json.parse(stripUtf8Bom(gameJson));
            validatePackageImageReferences(definition, tempDir);
            return new UploadedQuestionSet(definition, fileName, "Uploaded package: " + fileName, tempDir, true);
        } catch (Exception ex) {
            deleteDirectory(tempDir);
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw new IllegalArgumentException(ex.getMessage());
        }
    }

    /**
     * Applies a parsed upload after the game engine accepts the definition.
     */
    private Map<String, Object> applyUploadedQuestionSet(GameSession session, UploadedQuestionSet uploaded) throws IOException {
        Map<String, Object> result = session.engine.loadQuestionSet(uploaded.definition, uploaded.label);
        if (!Json.asBoolean(result.get("ok"), false)) {
            if (uploaded.extractedAssets != null) {
                deleteDirectory(uploaded.extractedAssets);
            }
            return result;
        }
        if (uploaded.packageUpload) {
            Path target = runtimeAssetsDir.resolve(session.id).normalize();
            if (!target.startsWith(runtimeAssetsDir)) {
                throw new IllegalStateException("Unsafe runtime package directory.");
            }
            deleteDirectory(target);
            Files.move(uploaded.extractedAssets, target);
            session.packageAssetDir = target;
            session.packageUpload = true;
        } else {
            deleteDirectory(session.packageAssetDir);
            session.packageAssetDir = null;
            session.packageUpload = false;
        }
        session.uploadedFileName = uploaded.fileName;
        session.questionSourceLabel = uploaded.label;
        result.put("questionSource", session.questionSourceMap());
        broadcastState(session);
        return result;
    }

    /**
     * Serves runtime-only image package files for one game.
     */
    private void servePackageAsset(HttpExchange exchange, GameSession session, String localPath) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (session.packageAssetDir == null) {
            notFound(exchange);
            return;
        }
        String relative = localPath.substring("/package-assets/".length());
        String safeRelative;
        try {
            safeRelative = normalizePackagePath(relative);
        } catch (IllegalArgumentException ex) {
            notFound(exchange);
            return;
        }
        Path target = session.packageAssetDir.resolve(safeRelative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(session.packageAssetDir) || !Files.exists(target) || Files.isDirectory(target)) {
            notFound(exchange);
            return;
        }
        byte[] content = Files.readAllBytes(target);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(target));
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Serves a fixed HTML page from the public directory.
     */
    private void servePage(HttpExchange exchange, String fileName) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Path target = publicDir.resolve(fileName).normalize();
        if (!target.startsWith(publicDir) || !Files.exists(target)) {
            notFound(exchange);
            return;
        }
        byte[] content = Files.readAllBytes(target);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Serves static assets while preventing path traversal outside publicDir.
     */
    private void serveStatic(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        Path target = publicDir.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(publicDir) || !Files.exists(target) || Files.isDirectory(target)) {
            notFound(exchange);
            return;
        }
        byte[] content = Files.readAllBytes(target);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(target));
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Opens a server-sent-events stream for live UI updates.
     */
    private void handleEvents(HttpExchange exchange, GameSession session, boolean moderatorStream) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (moderatorStream && !requireModerator(exchange, session)) {
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(exchange);
        if (moderatorStream) {
            session.moderatorSseHub.add(client);
            client.send(Json.stringify(moderatorState(session)));
        } else {
            session.publicSseHub.add(client);
            client.send(Json.stringify(publicState(session)));
        }
    }

    /**
     * Handles protected or unprotected POST routes without a request body.
     */
    private void handlePostNoBody(HttpExchange exchange, ThrowingSupplier<Map<String, Object>> action) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        writeJson(exchange, action.get());
    }

    /**
     * Handles form-encoded POST routes.
     */
    private void handleFormPost(HttpExchange exchange, ThrowingFunction<Map<String, String>, Map<String, Object>> action) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Map<String, String> form = parseForm(readBody(exchange));
        writeJson(exchange, action.apply(form));
    }

    /**
     * Security proxy wrapper for moderator POST actions.
     */
    private void handleModeratorPostNoBody(
            HttpExchange exchange,
            GameSession session,
            ThrowingSupplier<Map<String, Object>> action) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        handlePostNoBody(exchange, action);
    }

    /**
     * Security proxy wrapper for moderator form POST actions.
     */
    private void handleModeratorFormPost(
            HttpExchange exchange,
            GameSession session,
            ThrowingFunction<Map<String, String>, Map<String, Object>> action) throws IOException {
        if (!requireModerator(exchange, session)) {
            return;
        }
        handleFormPost(exchange, action);
    }

    /**
     * Stops a protected request unless the master manager is authenticated.
     */
    private boolean requireMaster(HttpExchange exchange) throws IOException {
        if (masterAuthManager.isModeratorAuthenticated(exchange)) {
            return true;
        }
        writeJson(exchange, 401, error("Master login required."));
        return false;
    }

    /**
     * Stops a protected request unless the selected game moderator is authenticated.
     */
    private boolean requireModerator(HttpExchange exchange, GameSession session) throws IOException {
        if (session.authManager.isModeratorAuthenticated(exchange)) {
            return true;
        }
        writeJson(exchange, 401, error("Moderator login required."));
        return false;
    }

    /**
     * Combines public game state with game metadata and public security metadata.
     */
    private Map<String, Object> publicState(GameSession session) {
        Map<String, Object> state = session.engine.getPublicState();
        state.put("gameInstance", session.publicMap());
        state.put("security", session.authManager.publicSecurityState());
        return state;
    }

    /**
     * Combines moderator game state with game metadata and moderator security metadata.
     */
    private Map<String, Object> moderatorState(GameSession session) {
        Map<String, Object> state = session.engine.getModeratorState();
        state.put("gameInstance", session.publicMap());
        state.put("questionSource", session.questionSourceMap());
        state.put("security", session.authManager.moderatorSecurityState());
        return state;
    }

    /**
     * Broadcasts the latest state for one game to its live browser streams.
     */
    private void broadcastState(GameSession session) {
        session.publicSseHub.broadcast(Json.stringify(publicState(session)));
        session.moderatorSseHub.broadcast(Json.stringify(moderatorState(session)));
    }

    /**
     * Builds the public game list payload.
     */
    private Map<String, Object> gamesListPayload() {
        Map<String, Object> payload = ok("Games loaded.");
        List<Map<String, Object>> summaries = games.values().stream()
                .sorted((left, right) -> left.id.compareTo(right.id))
                .map(this::gameSummary)
                .toList();
        payload.put("games", summaries);
        return payload;
    }

    /**
     * Summarizes one game for the master view.
     */
    private Map<String, Object> gameSummary(GameSession session) {
        Map<String, Object> state = session.engine.getPublicState();
        Map<String, Object> definition = Json.asObject(state.get("definition"));
        Map<String, Object> config = Json.asObject(definition.get("config"));
        Map<String, Object> game = Json.asObject(state.get("game"));
        int maxPlayersPerTeam = Json.asInt(config.get("maxPlayersPerTeam"), 1);
        int joined = 0;
        int capacity = 0;
        for (Object rawTeam : Json.asList(game.get("teams"))) {
            Map<String, Object> team = Json.asObject(rawTeam);
            if (!Json.asBoolean(team.get("active"), true)) {
                continue;
            }
            capacity += maxPlayersPerTeam;
            joined += Json.asList(team.get("players")).size();
        }

        Map<String, Object> summary = session.publicMap();
        summary.put("title", session.displayName);
        summary.put("definitionTitle", Json.asString(definition.get("title"), ""));
        summary.put("phase", Json.asString(game.get("phase"), "SETUP"));
        summary.put("round", Json.asString(game.get("round"), "SINGLE"));
        summary.put("joinedPlayers", joined);
        summary.put("playerCapacity", capacity);
        summary.put("questionSource", session.questionSourceMap());
        return summary;
    }

    /**
     * Creates a new game session around a GameEngine and game-scoped AuthManager.
     */
    private GameSession createGameSession(
            String id,
            String displayName,
            Path definitionPath,
            boolean persistDefinitionChanges,
            String moderatorPassword,
            String initialPlayerJoinPassword) {
        GameEngine engine = new GameEngine(definitionPath, persistDefinitionChanges);
        AuthManager authManager = new AuthManager(
                moderatorPassword,
                initialPlayerJoinPassword,
                "cheme_mod_" + id.replace("-", "_"));
        GameSession session = new GameSession(
                id,
                displayName,
                engine,
                authManager,
                displayPath(definitionPath));
        engine.setChangeListener(() -> broadcastState(session));
        return session;
    }

    /**
     * Resolves a request path into a selected game and local route path.
     */
    private GameRoute resolveGameRoute(String path) {
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        String gameId = slash < 0 ? trimmed : trimmed.substring(0, slash);
        String localPath = slash < 0 ? "/" : "/" + trimmed.substring(slash + 1);
        return new GameRoute(games.get(gameId), localPath);
    }

    /**
     * Resolves a server-side question file. Relative paths are resolved from the app root.
     */
    private Path resolveExistingFile(String configuredPath) {
        String cleaned = cleanText(configuredPath);
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Enter a JSON file path.");
        }
        Path path = Path.of(cleaned);
        Path resolved = path.isAbsolute()
                ? path.normalize()
                : rootDir.resolve(path).normalize();
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("File does not exist: " + cleaned);
        }
        if (Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Path is a directory: " + cleaned);
        }
        return resolved;
    }

    /**
     * Converts a path to a compact display label when it is inside the app root.
     */
    private String displayPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            if (absolute.startsWith(rootDir)) {
                return rootDir.relativize(absolute).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
            // Fall through to the absolute path label.
        }
        return absolute.toString();
    }

    /**
     * Normalizes a requested game slug or chooses the next available game-N id.
     */
    private String slugOrDefault(String requested) {
        String cleaned = cleanText(requested).toLowerCase()
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (!cleaned.isBlank()) {
            return cleaned;
        }
        int index = games.size() + 1;
        while (games.containsKey("game-" + index)) {
            index++;
        }
        return "game-" + index;
    }

    /**
     * Validates a game id URL segment.
     */
    private boolean isValidGameId(String id) {
        return GAME_ID_PATTERN.matcher(id).matches();
    }

    /**
     * Prevents collisions with app-level routes.
     */
    private boolean isReservedPath(String id) {
        return id.equals("api")
                || id.equals("assets")
                || id.equals("games")
                || id.equals("player")
                || id.equals("moderator")
                || id.equals("display");
    }

    /**
     * Gives a generated display name to a game id.
     */
    private String displayNameFromId(String id) {
        String[] pieces = id.split("-");
        StringBuilder builder = new StringBuilder();
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(piece.charAt(0))).append(piece.substring(1));
        }
        return builder.isEmpty() ? id : builder.toString();
    }

    /**
     * Convenience payload builder for successful operations.
     */
    private Map<String, Object> ok(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("message", message);
        return payload;
    }

    /**
     * Sends a browser redirect.
     */
    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /**
     * Sends a 405 response with an Allow header.
     */
    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        exchange.sendResponseHeaders(405, -1);
        exchange.close();
    }

    /**
     * Sends a 404 response.
     */
    private void notFound(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    /**
     * Writes JSON and chooses status by the payload ok flag.
     */
    private void writeJson(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        int status = Json.asBoolean(payload.get("ok"), false) ? 200 : 400;
        writeJson(exchange, status, payload);
    }

    /**
     * Writes JSON with an explicit HTTP status code.
     */
    private void writeJson(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] content = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(content);
        }
    }

    /**
     * Convenience payload builder for validation and authorization errors.
     */
    private Map<String, Object> error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("message", message);
        return payload;
    }

    /**
     * Reads the full request body as UTF-8 text.
     */
    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Reads either JSON or form-encoded request data into a simple object map.
     */
    private Map<String, Object> readRequestMap(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.toLowerCase().contains("application/json")) {
            return Json.asObject(Json.parse(body));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.putAll(parseForm(body));
        return payload;
    }

    /**
     * Reads a string field from a request payload.
     */
    private String payloadString(Map<String, Object> payload, String key) {
        return cleanText(Json.asString(payload.get(key), ""));
    }

    /**
     * Parses application/x-www-form-urlencoded request bodies.
     */
    private Map<String, String> parseForm(String body) {
        Map<String, String> form = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return form;
        }
        for (String pair : body.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] pieces = pair.split("=", 2);
            String key = decode(pieces[0]);
            String value = pieces.length > 1 ? decode(pieces[1]) : "";
            form.put(key, value);
        }
        return form;
    }

    /**
     * URL-decodes one form key or value.
     */
    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * Parses an integer form value with a safe fallback.
     */
    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * Parses a long form value with a safe fallback.
     */
    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * Removes accidental path pieces from browser file names.
     */
    private String cleanFileName(String fileName) {
        String cleaned = cleanText(fileName).replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.isBlank() ? "uploaded-game.json" : cleaned;
    }

    private boolean isPackageFile(String fileName) {
        String name = fileName.toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".chemej");
    }

    private String stripUtf8Bom(String text) {
        return text != null && text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private boolean isAllowedImageFile(String fileName) {
        String name = fileName.toLowerCase();
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp")
                || name.endsWith(".gif");
    }

    private String normalizePackagePath(String rawPath) {
        String path = cleanText(rawPath).replace('\\', '/');
        if (path.isBlank() || path.startsWith("/") || path.contains(":")) {
            throw new IllegalArgumentException("Package contains an unsafe file path.");
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isBlank() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Package contains an unsafe file path.");
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }

    private byte[] readZipEntry(ZipInputStream zip, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
            if (output.size() > maxBytes) {
                throw new IllegalArgumentException("Package game.json is too large.");
            }
        }
        return output.toByteArray();
    }

    private void copyZipEntry(ZipInputStream zip, Path target, long[] extractedBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (OutputStream output = Files.newOutputStream(target)) {
            while ((read = zip.read(buffer)) >= 0) {
                extractedBytes[0] += read;
                if (extractedBytes[0] > MAX_EXTRACTED_PACKAGE_BYTES) {
                    throw new IllegalArgumentException("Extracted image package is too large.");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private void validatePackageImageReferences(Object node, Path packageDir) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> map.put(String.valueOf(key), value));
            validateImageReference(map.get("image"), packageDir);
            for (Object value : map.values()) {
                validatePackageImageReferences(value, packageDir);
            }
            return;
        }
        if (node instanceof List<?> list) {
            for (Object value : list) {
                validatePackageImageReferences(value, packageDir);
            }
        }
    }

    private void validateImageReference(Object rawImage, Path packageDir) {
        String src = "";
        if (rawImage instanceof String rawString) {
            src = rawString;
        } else if (rawImage instanceof Map<?, ?> rawMap) {
            src = Json.asString(new LinkedHashMap<>(rawMap).get("src"), "");
        }
        src = cleanText(src);
        if (src.isBlank() || src.startsWith("/") || src.startsWith("http://") || src.startsWith("https://")) {
            return;
        }
        String safePath = normalizePackagePath(src);
        if (!isAllowedImageFile(safePath)) {
            throw new IllegalArgumentException("Image references must point to png, jpg, jpeg, webp, or gif files.");
        }
        Path target = packageDir.resolve(safePath.replace('/', java.io.File.separatorChar)).normalize();
        if (!target.startsWith(packageDir) || !Files.exists(target) || Files.isDirectory(target)) {
            throw new IllegalArgumentException("Image reference not found in package: " + src);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        if (!directory.normalize().startsWith(runtimeAssetsDir)) {
            throw new IOException("Refusing to delete directory outside runtime assets.");
        }
        try (var stream = Files.walk(directory)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Normalizes display text and form fields.
     */
    private String cleanText(String value) {
        return value == null ? "" : value.replace("\r", "").trim();
    }

    /**
     * Normalizes request paths by removing trailing slashes except for root.
     */
    private String cleanPath(String path) {
        String cleaned = path == null || path.isBlank() ? "/" : path;
        while (cleaned.length() > 1 && cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    /**
     * Minimal content type detection for static assets.
     */
    private String contentType(Path target) {
        String name = target.getFileName().toString().toLowerCase();
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws IOException;
    }

    private static final class GameRoute {
        private final GameSession session;
        private final String localPath;

        private GameRoute(GameSession session, String localPath) {
            this.session = session;
            this.localPath = localPath;
        }
    }

    private static final class UploadedQuestionSet {
        private final Object definition;
        private final String fileName;
        private final String label;
        private final Path extractedAssets;
        private final boolean packageUpload;

        private UploadedQuestionSet(
                Object definition,
                String fileName,
                String label,
                Path extractedAssets,
                boolean packageUpload) {
            this.definition = definition;
            this.fileName = fileName;
            this.label = label;
            this.extractedAssets = extractedAssets;
            this.packageUpload = packageUpload;
        }
    }

    private static final class GameSession {
        private String id;
        private String displayName;
        private final GameEngine engine;
        private final AuthManager authManager;
        private final SseHub publicSseHub = new SseHub();
        private final SseHub moderatorSseHub = new SseHub();
        private volatile String questionSourceLabel;
        private volatile String uploadedFileName = "";
        private volatile Path packageAssetDir;
        private volatile boolean packageUpload;

        private GameSession(
                String id,
                String displayName,
                GameEngine engine,
                AuthManager authManager,
                String questionSourceLabel) {
            this.id = id;
            this.displayName = displayName;
            this.engine = engine;
            this.authManager = authManager;
            this.questionSourceLabel = questionSourceLabel;
        }

        private Map<String, Object> publicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("displayName", displayName);
            map.put("basePath", "/" + id);
            map.put("playerUrl", "/" + id + "/player");
            map.put("moderatorUrl", "/" + id + "/moderator");
            map.put("displayUrl", "/" + id + "/display");
            return map;
        }

        private Map<String, Object> questionSourceMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("label", questionSourceLabel);
            map.put("uploadedFileName", uploadedFileName.isBlank() ? null : uploadedFileName);
            map.put("hasRuntimeUpload", !uploadedFileName.isBlank());
            map.put("hasPackageAssets", packageUpload);
            return map;
        }
    }

    private static final class SseHub {
        /** Thread-safe list because broadcasts can overlap with connection setup. */
        private final List<SseClient> clients = new CopyOnWriteArrayList<>();

        private void add(SseClient client) {
            clients.add(client);
        }

        private void broadcast(String json) {
            List<SseClient> deadClients = new ArrayList<>();
            for (SseClient client : clients) {
                if (!client.send(json)) {
                    deadClients.add(client);
                }
            }
            clients.removeAll(deadClients);
        }
    }

    private static final class SseClient {
        /** Open HTTP exchange whose response body receives SSE events. */
        private final HttpExchange exchange;

        private SseClient(HttpExchange exchange) {
            this.exchange = exchange;
        }

        private boolean send(String json) {
            try {
                String payload = "event: state\ndata: " + json.replace("\n", "") + "\n\n";
                exchange.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                return true;
            } catch (IOException ex) {
                try {
                    exchange.close();
                } catch (Exception ignored) {
                }
                return false;
            }
        }
    }
}
