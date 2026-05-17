/*
 * File: GameEngine.java
 * Description: Encapsulated rules engine and runtime state manager for ChemE Jeopardy.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */
package com.chemejeopardy.game;

import com.chemejeopardy.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns game rules, mutable runtime state, and persistence of the editable game definition.
 *
 * <p>The class uses encapsulation deliberately: runtime state is private, and callers can
 * only mutate it through methods that enforce ChemE Jeopardy rules. The nested definition
 * classes use factory-style {@code fromMap} methods to convert JSON data into typed objects.</p>
 */
public final class GameEngine {
    /** Source file containing setup data such as categories, clues, responses, and point values. */
    private final Path definitionPath;

    /** Controls whether moderator Save Setup writes the current definition back to definitionPath. */
    private final boolean persistDefinitionChanges;

    /** Secure randomness used for player/session IDs and random first chooser selection. */
    private final SecureRandom random = new SecureRandom();

    /** Single-threaded scheduler used for game timers to avoid concurrent timer callbacks. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** Current configurable game definition loaded from JSON. */
    private GameDefinition definition;

    /** Current in-memory game state for a running event. */
    private RuntimeState state;

    /** Observer callback used by the server to broadcast state changes to browsers. */
    private Runnable changeListener;

    public GameEngine(Path definitionPath) {
        this(definitionPath, true);
    }

    /**
     * Creates a game engine around a question source file.
     *
     * @param definitionPath JSON file containing the full game definition
     * @param persistDefinitionChanges true to save moderator setup edits back to the source file
     */
    public GameEngine(Path definitionPath, boolean persistDefinitionChanges) {
        this.definitionPath = definitionPath;
        this.persistDefinitionChanges = persistDefinitionChanges;
        this.definition = loadDefinition();
        this.state = createRuntimeState(null);
    }

    /**
     * Registers an observer that is called after state-changing operations.
     *
     * @param changeListener callback supplied by the HTTP/SSE layer
     */
    public void setChangeListener(Runnable changeListener) {
        this.changeListener = changeListener;
    }

    /**
     * @return public state safe for player and display screens
     */
    public Map<String, Object> getPublicState() {
        synchronized (this) {
            return snapshot(false);
        }
    }

    /**
     * @return moderator state, including hidden prompts and official responses
     */
    public Map<String, Object> getModeratorState() {
        synchronized (this) {
            return snapshot(true);
        }
    }

    /**
     * Joins a browser session to a team and returns its session key.
     *
     * @param teamId target team identifier
     * @param displayName player's display name
     * @return success or validation error payload
     */
    public Map<String, Object> joinPlayer(String teamId, String displayName) {
        boolean changed = false;
        Map<String, Object> response = new LinkedHashMap<>();
        synchronized (this) {
            TeamRuntime team = state.teamStates.get(teamId);
            String cleanedName = sanitizeText(displayName);
            if (team == null || !team.active) {
                return error("That team is not available.");
            }
            if (cleanedName.isBlank()) {
                return error("Please enter a display name.");
            }
            if (team.players.size() >= definition.config.maxPlayersPerTeam) {
                return error("That team is already full.");
            }
            PlayerRuntime player = new PlayerRuntime();
            player.id = UUID.randomUUID().toString();
            player.sessionKey = UUID.randomUUID().toString();
            player.displayName = cleanedName;
            player.teamId = teamId;
            player.joinedAt = System.currentTimeMillis();
            player.lastSyncAt = 0L;
            player.connected = true;

            state.players.put(player.id, player);
            team.players.add(player.id);
            changed = true;

            response.put("ok", true);
            response.put("message", "Joined " + team.name + ".");
            response.put("playerId", player.id);
            response.put("sessionKey", player.sessionKey);
            response.put("teamId", teamId);
            response.put("displayName", cleanedName);
        }
        if (changed) {
            fireChange();
        }
        return response;
    }

    /**
     * Records a player's latest clock synchronization sample.
     */
    public Map<String, Object> registerSync(String playerId, String sessionKey, long clientSentAt, long clientReceivedAt) {
        boolean changed = false;
        synchronized (this) {
            PlayerRuntime player = requirePlayer(playerId, sessionKey);
            if (player == null) {
                return error("Unknown player session.");
            }
            player.connected = true;
            player.lastSyncAt = System.currentTimeMillis();
            player.lastClientSendAt = clientSentAt;
            player.lastClientReceiveAt = clientReceivedAt;
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("serverTime", System.currentTimeMillis());
        return response;
    }

    /**
     * Replaces the editable game definition. This is the source-file save feature when
     * persistence is enabled, and an in-memory event update when persistence is disabled.
     */
    public Map<String, Object> updateDefinition(Object jsonBody) {
        return replaceDefinition(
                jsonBody,
                persistDefinitionChanges,
                persistDefinitionChanges
                        ? "Game configuration saved to the question source file."
                        : "Game configuration saved for this running session only.");
    }

    /**
     * Replaces the game definition from a selected question source without writing it to disk.
     *
     * @param jsonBody parsed game definition JSON
     * @param sourceLabel human-readable source name used in the response
     * @return success or validation error payload
     */
    public Map<String, Object> loadQuestionSet(Object jsonBody, String sourceLabel) {
        String label = sanitizeText(sourceLabel);
        return replaceDefinition(
                jsonBody,
                false,
                "Question set loaded" + (label.isBlank() ? "." : " from " + label + "."));
    }

    /**
     * Updates the display screen appearance without changing gameplay state.
     *
     * @param color display color family
     * @param mode display light/dark mode
     * @return success payload
     */
    public Map<String, Object> setDisplayTheme(String color, String mode) {
        synchronized (this) {
            definition.config.displayThemeColor = normalizeThemeColor(color);
            definition.config.displayThemeMode = normalizeThemeMode(mode);
        }
        fireChange();
        return ok("Display appearance updated.");
    }

    /**
     * Internal shared definition replacement path used by setup saves and question-set loads.
     */
    private Map<String, Object> replaceDefinition(Object jsonBody, boolean persistChanges, String successMessage) {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.SETUP) {
                return error("Game settings can only be edited before the game starts.");
            }
            try {
                GameDefinition candidate = GameDefinition.fromMap(Json.asObject(jsonBody));
                normalizeDefinition(candidate);
                RuntimeState previousState = state;
                definition = candidate;
                if (persistChanges) {
                    persistDefinition();
                }
                state = createRuntimeState(previousState);
                changed = true;
            } catch (Exception ex) {
                return error("Unable to parse the game definition: " + ex.getMessage());
            }
        }
        if (changed) {
            fireChange();
        }
        return ok(successMessage);
    }

    /**
     * Resets scores, players, timers, and clue progress while preserving compatible sessions.
     */
    public Map<String, Object> resetRuntime() {
        synchronized (this) {
            state = createRuntimeState(state);
        }
        fireChange();
        return ok("Game reset to setup.");
    }

    /**
     * Starts Single Jeopardy after setup validation.
     */
    public Map<String, Object> startGame() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.SETUP) {
                return error("The game has already started.");
            }
            List<TeamRuntime> activeTeams = activeTeams();
            if (activeTeams.size() < 2) {
                return error("At least two active teams are required.");
            }
            state.phase = Phase.ROUND_BOARD;
            state.currentRound = RoundType.SINGLE;
            state.chooserTeamId = activeTeams.get(random.nextInt(activeTeams.size())).id;
            state.statusMessage = "Single Jeopardy has started. " + teamName(state.chooserTeamId) + " selects first.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Game started.");
    }

    /**
     * Selects a clue from the active board and moves the game into reading/wager flow.
     */
    public Map<String, Object> selectClue(String clueId) {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.ROUND_BOARD) {
                return error("A clue cannot be selected right now.");
            }
            Clue clue = findClueForCurrentRound(clueId);
            if (clue == null) {
                return error("That clue does not exist in the current round.");
            }
            if (clue.used) {
                return error("That clue has already been used.");
            }
            clue.used = true;
            state.activeClue = clue;
            state.activeClueSelectingTeamId = state.chooserTeamId;
            state.answerVisible = false;
            state.lastRevealTitle = "";
            state.lastRevealResponse = "";
            state.lockedOutTeamIds.clear();
            state.pendingBuzzes.clear();
            state.recognizedPlayerId = null;
            state.recognizedTeamId = null;
            state.dailyDoubleWager = 0;
            clearCountdownLocked();

            if (clue.dailyDouble && (state.currentRound == RoundType.SINGLE || state.currentRound == RoundType.DOUBLE)) {
                state.phase = Phase.DAILY_DOUBLE_WAGER;
                state.statusMessage = "Daily Double selected by " + teamName(state.chooserTeamId) + ". Awaiting wager.";
            } else if (state.currentRound == RoundType.TIEBREAKER) {
                state.phase = Phase.CLUE_READING;
                state.statusMessage = "Tie-breaker clue selected. Moderator should read the clue, then unlock buzzing.";
            } else {
                state.phase = Phase.CLUE_READING;
                state.statusMessage = "Clue selected. Moderator should read the clue, then unlock buzzing.";
            }
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Clue selected.");
    }

    /**
     * Opens the buzz window after the moderator finishes reading a clue.
     */
    public Map<String, Object> finishReading() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase == Phase.CLUE_READING) {
                openBuzzWindowLocked();
                changed = true;
            } else {
                return error("There is no clue waiting for the moderator to finish reading.");
            }
        }
        if (changed) {
            fireChange();
        }
        return ok("Buzzing is now open.");
    }

    /**
     * Submits a synchronized buzz timestamp for a player session.
     */
    public Map<String, Object> submitBuzz(String playerId, String sessionKey, long syncedTimestamp) {
        boolean changed = false;
        synchronized (this) {
            PlayerRuntime player = requirePlayer(playerId, sessionKey);
            if (player == null) {
                return error("Unknown player session.");
            }
            player.connected = true;
            if (player.lastSyncAt == 0L) {
                return error("Please synchronize your clock before buzzing.");
            }
            if (state.activeClue == null) {
                return error("There is no active clue.");
            }
            if (state.phase != Phase.CLUE_READING && state.phase != Phase.BUZZ_OPEN) {
                return error("Buzzing is not currently available.");
            }
            if (!isTeamEligibleToBuzzLocked(player.teamId)) {
                return error("Your team is not eligible to buzz for this clue.");
            }
            BuzzRecord existing = state.pendingBuzzes.get(player.teamId);
            BuzzRecord candidate = new BuzzRecord(playerId, player.teamId, syncedTimestamp, System.currentTimeMillis());
            if (existing == null || candidate.compareTo(existing) < 0) {
                state.pendingBuzzes.put(player.teamId, candidate);
            }
            if (!state.buzzResolutionScheduled) {
                state.buzzResolutionScheduled = true;
                scheduler.schedule(this::resolvePendingBuzzes, 1, TimeUnit.SECONDS);
            }
            changed = true;
            if (state.phase == Phase.CLUE_READING) {
                state.statusMessage = "Early buzz received from " + teamName(player.teamId) + ". Waiting one second before applying the penalty.";
            } else {
                state.statusMessage = "Buzz received from " + teamName(player.teamId) + ". Waiting one second to determine who buzzed first.";
            }
        }
        if (changed) {
            fireChange();
        }
        return ok("Buzz received.");
    }

    /**
     * Applies moderator judgment to the current recognized response.
     */
    public Map<String, Object> judgeCurrent(boolean correct) {
        boolean changed = false;
        synchronized (this) {
            if (state.phase == Phase.PLAYER_RESPONSE) {
                changed = handlePlayerJudgementLocked(correct);
            } else if (state.phase == Phase.DAILY_DOUBLE_RESPONSE) {
                changed = handleDailyDoubleJudgementLocked(correct);
            } else if (state.phase == Phase.FINAL_REVEAL) {
                changed = handleFinalJudgementLocked(correct);
            } else {
                return error("There is no active response awaiting judgment.");
            }
        }
        if (changed) {
            fireChange();
        }
        return ok(correct ? "Marked correct." : "Marked incorrect.");
    }

    /**
     * Records a Daily Double wager from either the owning team or the moderator.
     */
    public Map<String, Object> setDailyWager(String teamId, Integer wager, String playerId, String sessionKey, boolean moderatorOverride) {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.DAILY_DOUBLE_WAGER || state.activeClue == null) {
                return error("There is no Daily Double wager to set.");
            }
            String ownerTeamId = state.activeClueSelectingTeamId;
            if (!moderatorOverride) {
                PlayerRuntime player = requirePlayer(playerId, sessionKey);
                if (player == null) {
                    return error("Unknown player session.");
                }
                if (!Objects.equals(player.teamId, ownerTeamId)) {
                    return error("Only the team that selected the Daily Double can submit a wager.");
                }
                teamId = player.teamId;
            }
            if (!Objects.equals(teamId, ownerTeamId)) {
                return error("The wager must belong to the team that selected the Daily Double.");
            }
            TeamRuntime team = state.teamStates.get(teamId);
            int maximum = Math.max(team.score, state.currentRound == RoundType.DOUBLE ? 1000 : 500);
            int normalized = Math.max(0, Math.min(maximum, wager == null ? 0 : wager));
            state.dailyDoubleWager = normalized;
            state.statusMessage = team.name + " wagered " + normalized + " points on the Daily Double.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Daily Double wager recorded.");
    }

    /**
     * Reveals the Daily Double clue after the wager is entered.
     */
    public Map<String, Object> startDailyDouble() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.DAILY_DOUBLE_WAGER || state.activeClue == null) {
                return error("No Daily Double is awaiting reveal.");
            }
            state.phase = Phase.DAILY_DOUBLE_RESPONSE;
            TeamRuntime team = state.teamStates.get(state.activeClueSelectingTeamId);
            state.recognizedTeamId = team.id;
            state.recognizedPlayerId = null;
            scheduleCountdownLocked("Daily Double response", definition.config.dailyDoubleResponseSeconds, this::handleResponseTimeout);
            state.statusMessage = "Daily Double clue revealed. " + team.name + " has " + definition.config.dailyDoubleResponseSeconds + " seconds to answer.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Daily Double clue revealed.");
    }

    /**
     * Manually adjusts a team's score from the moderator console.
     */
    public Map<String, Object> adjustScore(String teamId, int delta) {
        boolean changed = false;
        synchronized (this) {
            TeamRuntime team = state.teamStates.get(teamId);
            if (team == null) {
                return error("Unknown team.");
            }
            team.score += delta;
            state.statusMessage = team.name + " adjusted by " + delta + " points.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Score updated.");
    }

    /**
     * Starts Final Jeopardy wager collection for eligible teams.
     */
    public Map<String, Object> startFinalWager() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_CATEGORY) {
                return error("Final Jeopardy is not ready for wagers.");
            }
            state.phase = Phase.FINAL_WAGER;
            for (TeamRuntime team : state.teamStates.values()) {
                team.finalWager = 0;
                team.finalResponse = "";
                team.finalJudged = false;
                team.finalResultCorrect = false;
                team.finalWagerSubmitted = false;
                team.finalResponseSubmitted = false;
                team.finalEligible = team.active && team.score > 0;
            }
            scheduleCountdownLocked("Final Jeopardy wagers", definition.config.finalWagerSeconds, this::handleFinalWagerExpired);
            state.statusMessage = "Final Jeopardy wagers are open for " + definition.config.finalWagerSeconds + " seconds.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy wagers started.");
    }

    /**
     * Stores a Final Jeopardy wager for the player's team.
     */
    public Map<String, Object> submitFinalWager(String playerId, String sessionKey, int wager) {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_WAGER) {
                return error("Final Jeopardy wagers are not open.");
            }
            PlayerRuntime player = requirePlayer(playerId, sessionKey);
            if (player == null) {
                return error("Unknown player session.");
            }
            TeamRuntime team = state.teamStates.get(player.teamId);
            if (team == null || !team.finalEligible) {
                return error("Your team is not eligible for Final Jeopardy.");
            }
            int normalized = Math.max(0, Math.min(team.score, wager));
            team.finalWager = normalized;
            team.finalWagerSubmitted = true;
            state.statusMessage = team.name + " submitted a Final Jeopardy wager.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy wager submitted.");
    }

    /**
     * Reveals the Final Jeopardy clue to public clients.
     */
    public Map<String, Object> revealFinalClue() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_CLUE_READY) {
                return error("Final Jeopardy clue is not ready to reveal yet.");
            }
            state.phase = Phase.FINAL_RESPONSE;
            scheduleCountdownLocked("Final Jeopardy responses", definition.config.finalResponseSeconds, this::handleFinalResponseExpired);
            state.statusMessage = "Final Jeopardy clue revealed. Teams now have " + definition.config.finalResponseSeconds + " seconds to submit responses.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy clue revealed.");
    }

    /**
     * Stores a typed Final Jeopardy response for the player's team.
     */
    public Map<String, Object> submitFinalResponse(String playerId, String sessionKey, String responseText) {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_RESPONSE) {
                return error("Final Jeopardy responses are not open.");
            }
            PlayerRuntime player = requirePlayer(playerId, sessionKey);
            if (player == null) {
                return error("Unknown player session.");
            }
            TeamRuntime team = state.teamStates.get(player.teamId);
            if (team == null || !team.finalEligible) {
                return error("Your team is not eligible for Final Jeopardy.");
            }
            team.finalResponse = sanitizeText(responseText);
            team.finalResponseSubmitted = !team.finalResponse.isBlank();
            state.statusMessage = team.name + " submitted a Final Jeopardy response.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy response submitted.");
    }

    /**
     * Moderator manually locks Final Jeopardy wagers without waiting for the timer.
     */
    public Map<String, Object> lockFinalWagers() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_WAGER) {
                return error("Final Jeopardy wagers are not open.");
            }
            clearCountdownLocked();
            state.phase = Phase.FINAL_CLUE_READY;
            state.statusMessage = "Final Jeopardy wagers locked by moderator. Moderator can now reveal the clue.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy wagers locked.");
    }

    /**
     * Moderator manually locks Final Jeopardy responses and starts the reveal phase.
     */
    public Map<String, Object> lockFinalResponses() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_RESPONSE) {
                return error("Final Jeopardy responses are not open.");
            }
            clearCountdownLocked();
            state.phase = Phase.FINAL_REVEAL;
            state.finalRevealOrder.clear();
            activeTeams().stream()
                    .filter(team -> team.finalEligible)
                    .sorted(Comparator.comparingInt(team -> team.score))
                    .forEach(team -> state.finalRevealOrder.add(team.id));
            state.finalRevealIndex = 0;
            if (state.finalRevealOrder.isEmpty()) {
                finishFinalRevealLocked();
            } else {
                TeamRuntime firstTeam = state.teamStates.get(state.finalRevealOrder.getFirst());
                state.statusMessage = "Final Jeopardy responses locked by moderator. Reveal begins with " + firstTeam.name + ".";
            }
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy responses locked.");
    }

    /**
     * Moderator manually advances past the current Final Jeopardy reveal team without scoring. Falls through to game end when the queue is exhausted.
     */
    public Map<String, Object> skipFinalRevealTeam() {
        boolean changed = false;
        String skippedTeamName = "Team";
        synchronized (this) {
            if (state.phase != Phase.FINAL_REVEAL) {
                return error("Final Jeopardy reveal is not in progress.");
            }
            if (state.finalRevealOrder.isEmpty() || state.finalRevealIndex >= state.finalRevealOrder.size()) {
                finishFinalRevealLocked();
            } else {
                TeamRuntime team = state.teamStates.get(state.finalRevealOrder.get(state.finalRevealIndex));
                if (team != null) {
                    skippedTeamName = team.name;
                }
                state.finalRevealIndex++;
                if (state.finalRevealIndex >= state.finalRevealOrder.size()) {
                    finishFinalRevealLocked();
                } else {
                    TeamRuntime nextTeam = state.teamStates.get(state.finalRevealOrder.get(state.finalRevealIndex));
                    String nextName = nextTeam == null ? "next team" : nextTeam.name;
                    state.statusMessage = skippedTeamName + " was skipped. Next up: " + nextName + ".";
                }
            }
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Skipped " + skippedTeamName + ".");
    }

    /**
     * Moderator escape hatch that ends Final Jeopardy from any final phase, jumping straight to game over or tie-breaker.
     */
    public Map<String, Object> endFinalReveal() {
        boolean changed = false;
        synchronized (this) {
            boolean inFinal = state.phase == Phase.FINAL_CATEGORY
                    || state.phase == Phase.FINAL_WAGER
                    || state.phase == Phase.FINAL_CLUE_READY
                    || state.phase == Phase.FINAL_RESPONSE
                    || state.phase == Phase.FINAL_REVEAL;
            if (!inFinal) {
                return error("Final Jeopardy is not in progress.");
            }
            clearCountdownLocked();
            finishFinalRevealLocked();
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Final Jeopardy ended.");
    }

    /**
     * Starts the first tie-breaker clue when final scores end in a tie.
     */
    public Map<String, Object> startTieBreaker() {
        boolean changed = false;
        synchronized (this) {
            if (!state.tiePending) {
                return error("There is no tie-breaker pending.");
            }
            if (definition.tieBreakers.isEmpty()) {
                return error("No tie-breaker clues are configured.");
            }
            changed = prepareNextTieBreakerLocked();
        }
        if (changed) {
            fireChange();
        }
        return ok("Tie-breaker started.");
    }

    /**
     * Prepares the next tie-breaker clue after an unresolved tie.
     */
    public Map<String, Object> nextTieBreakerClue() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.TIEBREAKER_READY) {
                return error("A tie-breaker clue is not ready right now.");
            }
            changed = prepareNextTieBreakerLocked();
        }
        if (changed) {
            fireChange();
        }
        return ok("Next tie-breaker clue prepared.");
    }

    /**
     * Returns from answer reveal mode to the board or the next round.
     */
    public Map<String, Object> continueAfterReveal() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.CLUE_REVEAL || state.activeClue == null || !state.answerVisible) {
                return error("There is no revealed clue waiting to continue.");
            }
            state.answerVisible = false;
            state.recognizedPlayerId = null;
            state.recognizedTeamId = null;
            state.pendingBuzzes.clear();
            state.buzzResolutionScheduled = false;
            state.lockedOutTeamIds.clear();
            state.activeClue = null;
            state.activeClueSelectingTeamId = null;
            advanceRoundIfNeededLocked();
            changed = true;
        }
        if (changed) {
            fireChange();
        }
        return ok("Returned to the board.");
    }

    /**
     * Provides server time for client-side clock calibration.
     */
    public Map<String, Object> moderatorTime() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("serverTime", System.currentTimeMillis());
        response.put("serverIso", Instant.ofEpochMilli(System.currentTimeMillis()).toString());
        return response;
    }

    /**
     * Factory-style helper that converts a tie-breaker definition into an active clue.
     */
    private boolean prepareNextTieBreakerLocked() {
        if (state.tieBreakerIndex >= definition.tieBreakers.size()) {
            state.statusMessage = "No more tie-breaker clues are configured.";
            state.phase = Phase.GAME_OVER;
            return true;
        }
        TieBreaker tieBreaker = definition.tieBreakers.get(state.tieBreakerIndex++);
        Clue clue = new Clue();
        clue.id = "tiebreaker-" + state.tieBreakerIndex;
        clue.prompt = tieBreaker.clue;
        clue.response = tieBreaker.response;
        clue.image = tieBreaker.image;
        clue.value = 0;
        clue.dailyDouble = false;
        clue.categoryName = tieBreaker.category;
        clue.used = true;
        state.currentRound = RoundType.TIEBREAKER;
        state.phase = Phase.CLUE_READING;
        state.activeClue = clue;
        state.activeClueSelectingTeamId = null;
        state.answerVisible = false;
        state.lockedOutTeamIds.clear();
        state.pendingBuzzes.clear();
        state.recognizedPlayerId = null;
        state.recognizedTeamId = null;
        clearCountdownLocked();
        state.statusMessage = "Tie-breaker clue prepared. Moderator should read the clue, then unlock buzzing.";
        return true;
    }

    /**
     * Applies normal clue scoring after the moderator has judged a response.
     */
    private boolean handlePlayerJudgementLocked(boolean correct) {
        if (state.activeClue == null || state.recognizedTeamId == null) {
            return false;
        }
        clearCountdownLocked();
        TeamRuntime team = state.teamStates.get(state.recognizedTeamId);
        if (correct) {
            if (state.currentRound == RoundType.TIEBREAKER) {
                state.winnerTeamId = team.id;
                state.statusMessage = team.name + " won the tie-breaker and the game.";
                state.phase = Phase.GAME_OVER;
                state.answerVisible = true;
                state.lastRevealTitle = state.activeClue.categoryName;
                state.lastRevealResponse = state.activeClue.response;
                return true;
            }
            team.score += state.activeClue.value;
            state.chooserTeamId = team.id;
            revealAndHoldLocked(team.name + " answered correctly.");
            return true;
        }

        if (state.currentRound == RoundType.TIEBREAKER) {
            state.lockedOutTeamIds.add(team.id);
            state.recognizedPlayerId = null;
            state.recognizedTeamId = null;
            if (remainingEligibleTieTeamsLocked().isEmpty()) {
                revealAndHoldLocked(team.name + " was incorrect. Moving to the next tie-breaker clue.");
                state.tiePending = true;
            } else {
                openBuzzWindowLocked();
                state.statusMessage = team.name + " was incorrect. Remaining tied teams may buzz in.";
            }
            return true;
        }

        team.score -= state.activeClue.value;
        state.lockedOutTeamIds.add(team.id);
        state.recognizedPlayerId = null;
        state.recognizedTeamId = null;
        if (remainingEligibleTeamsLocked().isEmpty()) {
            revealAndHoldLocked(team.name + " was incorrect. No teams remain eligible for this clue.");
        } else {
            openBuzzWindowLocked();
            state.statusMessage = team.name + " was incorrect. Other teams have " + definition.config.buzzWindowSeconds + " seconds to buzz in.";
        }
        return true;
    }

    /**
     * Applies Daily Double scoring after the moderator has judged the response.
     */
    private boolean handleDailyDoubleJudgementLocked(boolean correct) {
        if (state.activeClue == null || state.activeClueSelectingTeamId == null) {
            return false;
        }
        clearCountdownLocked();
        TeamRuntime team = state.teamStates.get(state.activeClueSelectingTeamId);
        if (correct) {
            team.score += state.dailyDoubleWager;
            revealAndHoldLocked(team.name + " answered the Daily Double correctly.");
        } else {
            team.score -= state.dailyDoubleWager;
            revealAndHoldLocked(team.name + " missed the Daily Double.");
        }
        state.chooserTeamId = team.id;
        return true;
    }

    /**
     * Applies Final Jeopardy scoring in reveal order. Defensively advances even when state is partially broken so the moderator never gets stuck.
     */
    private boolean handleFinalJudgementLocked(boolean correct) {
        if (state.finalRevealOrder.isEmpty() || state.finalRevealIndex >= state.finalRevealOrder.size()) {
            finishFinalRevealLocked();
            return true;
        }
        TeamRuntime team = state.teamStates.get(state.finalRevealOrder.get(state.finalRevealIndex));
        String judgedName = team == null ? "Team" : team.name;
        if (team != null) {
            team.finalJudged = true;
            team.finalResultCorrect = correct;
            if (correct) {
                team.score += team.finalWager;
            } else {
                team.score -= team.finalWager;
            }
        }
        state.finalRevealIndex++;
        if (state.finalRevealIndex >= state.finalRevealOrder.size()) {
            finishFinalRevealLocked();
        } else {
            TeamRuntime nextTeam = state.teamStates.get(state.finalRevealOrder.get(state.finalRevealIndex));
            String nextName = nextTeam == null ? "next team" : nextTeam.name;
            state.statusMessage = judgedName + " judged " + (correct ? "correct" : "incorrect") + ". Next up: " + nextName + ".";
        }
        return true;
    }

    /**
     * Decides whether Final Jeopardy produced a winner or needs a tie-breaker.
     */
    private void finishFinalRevealLocked() {
        List<TeamRuntime> contenders = new ArrayList<>(activeTeams());
        contenders.sort(Comparator.comparingInt((TeamRuntime team) -> team.score).reversed());
        TeamRuntime leader = contenders.isEmpty() ? null : contenders.getFirst();
        if (leader == null) {
            state.phase = Phase.GAME_OVER;
            state.statusMessage = "Game over.";
            return;
        }
        List<TeamRuntime> tiedLeaders = contenders.stream()
                .filter(team -> team.score == leader.score)
                .toList();
        if (tiedLeaders.size() > 1) {
            state.tiePending = true;
            state.tieTeamIds.clear();
            tiedLeaders.forEach(team -> state.tieTeamIds.add(team.id));
            state.phase = Phase.TIEBREAKER_READY;
            state.statusMessage = "Tie for first place. Moderator can start the tie-breaker.";
            return;
        }
        state.tiePending = false;
        state.winnerTeamId = leader.id;
        state.phase = Phase.GAME_OVER;
        state.statusMessage = leader.name + " won the game.";
    }

    /**
     * Resolves buzzes after a short settling period to reduce network-arrival bias.
     */
    private void resolvePendingBuzzes() {
        boolean changed = false;
        synchronized (this) {
            if (!state.buzzResolutionScheduled) {
                return;
            }
            state.buzzResolutionScheduled = false;
            if (state.pendingBuzzes.isEmpty()) {
                return;
            }
            BuzzRecord winner = state.pendingBuzzes.values().stream()
                    .min(BuzzRecord::compareTo)
                    .orElse(null);
            state.pendingBuzzes.clear();
            if (winner == null) {
                return;
            }
            TeamRuntime team = state.teamStates.get(winner.teamId);
            PlayerRuntime player = state.players.get(winner.playerId);
            if (team == null || player == null) {
                return;
            }
            if (state.phase == Phase.CLUE_READING) {
                if (state.currentRound == RoundType.TIEBREAKER) {
                    state.lockedOutTeamIds.add(team.id);
                    state.statusMessage = team.name + " buzzed early during the tie-breaker clue and is locked out for this clue.";
                    if (remainingEligibleTieTeamsLocked().isEmpty()) {
                        state.phase = Phase.TIEBREAKER_READY;
                        state.tiePending = true;
                    }
                } else {
                    team.score -= state.activeClue.value;
                    state.lockedOutTeamIds.add(team.id);
                    state.statusMessage = team.name + " buzzed early, so " + state.activeClue.value + " points were deducted.";
                }
                changed = true;
                return;
            }

            if (state.phase != Phase.BUZZ_OPEN) {
                return;
            }
            clearCountdownLocked();
            state.recognizedTeamId = team.id;
            state.recognizedPlayerId = player.id;
            state.phase = Phase.PLAYER_RESPONSE;
            scheduleCountdownLocked("Response window", definition.config.responseWindowSeconds, this::handleResponseTimeout);
            state.statusMessage = player.displayName + " from " + team.name + " buzzed in first.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
    }

    /**
     * Response timeout is informational only; moderator judgment remains authoritative.
     */
    private void handleResponseTimeout() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase == Phase.PLAYER_RESPONSE) {
                clearCountdownLocked();
                state.statusMessage = "Time is up. Moderator can still mark the response correct or incorrect.";
                changed = true;
            } else if (state.phase == Phase.DAILY_DOUBLE_RESPONSE) {
                clearCountdownLocked();
                state.statusMessage = "Daily Double time is up. Moderator can still mark the response correct or incorrect.";
                changed = true;
            }
        }
        if (changed) {
            fireChange();
        }
    }

    /**
     * Reveals the official response when no eligible team buzzes in time.
     */
    private void handleBuzzWindowExpired() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.BUZZ_OPEN) {
                return;
            }
            clearCountdownLocked();
            revealAndHoldLocked("No team buzzed in time.");
            changed = true;
        }
        if (changed) {
            fireChange();
        }
    }

    /**
     * Locks Final Jeopardy wagers and prepares the clue reveal stage.
     */
    private void handleFinalWagerExpired() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_WAGER) {
                return;
            }
            clearCountdownLocked();
            state.phase = Phase.FINAL_CLUE_READY;
            state.statusMessage = "Final Jeopardy wagers are locked. Moderator can now reveal the clue.";
            changed = true;
        }
        if (changed) {
            fireChange();
        }
    }

    /**
     * Locks Final Jeopardy responses and starts low-score-to-high-score reveal order.
     */
    private void handleFinalResponseExpired() {
        boolean changed = false;
        synchronized (this) {
            if (state.phase != Phase.FINAL_RESPONSE) {
                return;
            }
            clearCountdownLocked();
            state.phase = Phase.FINAL_REVEAL;
            state.finalRevealOrder.clear();
            activeTeams().stream()
                    .filter(team -> team.finalEligible)
                    .sorted(Comparator.comparingInt(team -> team.score))
                    .forEach(team -> state.finalRevealOrder.add(team.id));
            state.finalRevealIndex = 0;
            if (state.finalRevealOrder.isEmpty()) {
                finishFinalRevealLocked();
            } else {
                TeamRuntime firstTeam = state.teamStates.get(state.finalRevealOrder.getFirst());
                state.statusMessage = "Final Jeopardy responses are locked. Reveal begins with " + firstTeam.name + ".";
            }
            changed = true;
        }
        if (changed) {
            fireChange();
        }
    }

    /**
     * Shows the official answer while waiting for the moderator to return to the board.
     */
    private void revealAndHoldLocked(String statusMessage) {
        state.answerVisible = true;
        state.lastRevealTitle = state.activeClue.categoryName + " for " + state.activeClue.value;
        state.lastRevealResponse = state.activeClue.response;
        state.statusMessage = statusMessage;
        state.recognizedPlayerId = null;
        state.recognizedTeamId = null;
        state.pendingBuzzes.clear();
        state.buzzResolutionScheduled = false;
        state.lockedOutTeamIds.clear();
        state.phase = Phase.CLUE_REVEAL;
    }

    /**
     * Advances from a revealed clue to the next board, round, Final Jeopardy, or game over.
     */
    private void advanceRoundIfNeededLocked() {
        if (state.currentRound == RoundType.TIEBREAKER) {
            state.phase = Phase.TIEBREAKER_READY;
            state.tiePending = true;
            return;
        }
        Board board = boardForRound(state.currentRound);
        boolean boardComplete = board == null || board.categories.stream()
                .flatMap(category -> category.clues.stream())
                .allMatch(clue -> clue.used);

        if (!boardComplete) {
            state.phase = Phase.ROUND_BOARD;
            state.statusMessage = (state.chooserTeamId == null ? "Moderator" : teamName(state.chooserTeamId)) + " selects the next clue.";
            return;
        }

        if (state.currentRound == RoundType.SINGLE && definition.config.includeDoubleJeopardy) {
            state.currentRound = RoundType.DOUBLE;
            state.phase = Phase.ROUND_BOARD;
            state.chooserTeamId = lowestScoreTeamId();
            state.statusMessage = "Double Jeopardy begins. " + teamName(state.chooserTeamId) + " selects first.";
            return;
        }

        if ((state.currentRound == RoundType.SINGLE || state.currentRound == RoundType.DOUBLE) && definition.config.includeFinalJeopardy) {
            for (TeamRuntime team : state.teamStates.values()) {
                team.finalEligible = team.active && team.score > 0;
            }
            boolean anyEligible = state.teamStates.values().stream().anyMatch(team -> team.finalEligible);
            if (!anyEligible) {
                state.phase = Phase.GAME_OVER;
                TeamRuntime leader = activeTeams().stream()
                        .max(Comparator.comparingInt(team -> team.score))
                        .orElse(null);
                state.winnerTeamId = leader == null ? null : leader.id;
                state.statusMessage = leader == null
                        ? "No teams were eligible for Final Jeopardy."
                        : "No teams were eligible for Final Jeopardy. " + leader.name + " won the game.";
                return;
            }
            state.currentRound = RoundType.FINAL;
            state.phase = Phase.FINAL_CATEGORY;
            state.statusMessage = "Final Jeopardy category revealed. Teams with scores above zero are eligible.";
            return;
        }

        state.phase = Phase.GAME_OVER;
        TeamRuntime leader = activeTeams().stream()
                .max(Comparator.comparingInt(team -> team.score))
                .orElse(null);
        state.winnerTeamId = leader == null ? null : leader.id;
        state.statusMessage = leader == null ? "Game over." : leader.name + " won the game.";
    }

    /**
     * Opens a timed buzz window for all eligible teams.
     */
    private void openBuzzWindowLocked() {
        state.phase = Phase.BUZZ_OPEN;
        state.pendingBuzzes.clear();
        state.buzzResolutionScheduled = false;
        clearCountdownLocked();
        scheduleCountdownLocked("Buzz window", definition.config.buzzWindowSeconds, this::handleBuzzWindowExpired);
        state.statusMessage = "Buzzing is open for " + definition.config.buzzWindowSeconds + " seconds.";
    }

    private boolean isTeamEligibleToBuzzLocked(String teamId) {
        if (state.currentRound == RoundType.TIEBREAKER && !state.tieTeamIds.contains(teamId)) {
            return false;
        }
        return !state.lockedOutTeamIds.contains(teamId);
    }

    private List<String> remainingEligibleTeamsLocked() {
        return activeTeams().stream()
                .filter(team -> !state.lockedOutTeamIds.contains(team.id))
                .map(team -> team.id)
                .toList();
    }

    private List<String> remainingEligibleTieTeamsLocked() {
        return state.tieTeamIds.stream()
                .filter(teamId -> !state.lockedOutTeamIds.contains(teamId))
                .toList();
    }

    private void scheduleCountdownLocked(String label, int seconds, Runnable callback) {
        clearCountdownLocked();
        Countdown countdown = new Countdown();
        countdown.label = label;
        countdown.totalSeconds = seconds;
        countdown.endsAt = System.currentTimeMillis() + (seconds * 1000L);
        countdown.future = scheduler.schedule(callback, seconds, TimeUnit.SECONDS);
        state.countdown = countdown;
    }

    private void clearCountdownLocked() {
        if (state.countdown != null && state.countdown.future != null) {
            state.countdown.future.cancel(false);
        }
        state.countdown = null;
    }

    private PlayerRuntime requirePlayer(String playerId, String sessionKey) {
        PlayerRuntime player = state.players.get(playerId);
        if (player == null) {
            return null;
        }
        return Objects.equals(player.sessionKey, sessionKey) ? player : null;
    }

    private TeamRuntime teamByPlayer(String playerId) {
        PlayerRuntime player = state.players.get(playerId);
        return player == null ? null : state.teamStates.get(player.teamId);
    }

    private String lowestScoreTeamId() {
        return activeTeams().stream()
                .min(Comparator.comparingInt((TeamRuntime team) -> team.score).thenComparing(team -> team.name))
                .map(team -> team.id)
                .orElseGet(() -> activeTeams().isEmpty() ? null : activeTeams().getFirst().id);
    }

    private Clue findClueForCurrentRound(String clueId) {
        Board board = boardForRound(state.currentRound);
        if (board == null) {
            return null;
        }
        for (Category category : board.categories) {
            for (Clue clue : category.clues) {
                if (Objects.equals(clue.id, clueId)) {
                    return clue;
                }
            }
        }
        return null;
    }

    private Board boardForRound(RoundType roundType) {
        return switch (roundType) {
            case SINGLE -> definition.singleBoard;
            case DOUBLE -> definition.doubleBoard;
            default -> null;
        };
    }

    private RuntimeState createRuntimeState(RuntimeState previousState) {
        RuntimeState runtime = new RuntimeState();
        runtime.phase = Phase.SETUP;
        runtime.currentRound = RoundType.SINGLE;
        runtime.statusMessage = "Configure the game, let players join, and then start.";
        runtime.teamStates = new LinkedHashMap<>();
        runtime.players = new LinkedHashMap<>();
        runtime.pendingBuzzes = new LinkedHashMap<>();
        runtime.lockedOutTeamIds = new LinkedHashSet<>();
        runtime.finalRevealOrder = new ArrayList<>();
        runtime.tieTeamIds = new ArrayList<>();

        for (TeamDefinition teamDefinition : definition.teams) {
            TeamRuntime teamRuntime = new TeamRuntime();
            teamRuntime.id = teamDefinition.id;
            teamRuntime.name = teamDefinition.name;
            teamRuntime.color = teamDefinition.color;
            teamRuntime.active = teamDefinition.active;
            teamRuntime.score = 0;
            teamRuntime.players = new ArrayList<>();
            runtime.teamStates.put(teamRuntime.id, teamRuntime);
        }

        if (previousState != null && previousState.players != null) {
            for (PlayerRuntime existingPlayer : previousState.players.values()) {
                if (existingPlayer == null) {
                    continue;
                }
                TeamRuntime team = runtime.teamStates.get(existingPlayer.teamId);
                if (team == null || !team.active) {
                    continue;
                }
                if (team.players.size() >= definition.config.maxPlayersPerTeam) {
                    continue;
                }
                PlayerRuntime preserved = new PlayerRuntime();
                preserved.id = existingPlayer.id;
                preserved.sessionKey = existingPlayer.sessionKey;
                preserved.displayName = existingPlayer.displayName;
                preserved.teamId = existingPlayer.teamId;
                preserved.connected = existingPlayer.connected;
                preserved.joinedAt = existingPlayer.joinedAt;
                preserved.lastSyncAt = existingPlayer.lastSyncAt;
                preserved.lastClientSendAt = existingPlayer.lastClientSendAt;
                preserved.lastClientReceiveAt = existingPlayer.lastClientReceiveAt;
                runtime.players.put(preserved.id, preserved);
                team.players.add(preserved.id);
            }
            if (!runtime.players.isEmpty()) {
                runtime.statusMessage = "Configure the game, review the connected teams, and then start.";
            }
        }
        return runtime;
    }

    private List<TeamRuntime> activeTeams() {
        return state.teamStates.values().stream()
                .filter(team -> team.active)
                .toList();
    }

    private String teamName(String teamId) {
        TeamRuntime team = state.teamStates.get(teamId);
        return team == null ? "Unknown Team" : team.name;
    }

    private String sanitizeText(String value) {
        return value == null ? "" : value.replace("\r", "").trim();
    }

    private String normalizeThemeColor(String value) {
        return switch (sanitizeText(value).toLowerCase()) {
            case "black", "red", "green" -> sanitizeText(value).toLowerCase();
            default -> "blue";
        };
    }

    private String normalizeThemeMode(String value) {
        return "light".equals(sanitizeText(value).toLowerCase()) ? "light" : "dark";
    }

    private Map<String, Object> ok(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("message", message);
        return response;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", false);
        response.put("message", message);
        return response;
    }

    private void fireChange() {
        Runnable listener = this.changeListener;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Loads the question source file, falling back to normalized defaults when needed.
     */
    private GameDefinition loadDefinition() {
        if (Files.exists(definitionPath)) {
            try {
                Object parsed = Json.parse(Files.readString(definitionPath, StandardCharsets.UTF_8));
                GameDefinition loaded = GameDefinition.fromMap(Json.asObject(parsed));
                normalizeDefinition(loaded);
                return loaded;
            } catch (Exception ignored) {
                // Fall back to defaults if the on-disk definition is malformed.
            }
        }

        GameDefinition defaults = GameDefinition.defaults();
        normalizeDefinition(defaults);
        if (persistDefinitionChanges) {
            try {
                persistDefinition(defaults);
            } catch (Exception ignored) {
            }
        }
        return defaults;
    }

    /**
     * Saves the current definition back to the configured source file.
     */
    private void persistDefinition() {
        persistDefinition(definition);
    }

    /**
     * Writes a definition object as JSON. This is intentionally centralized so the
     * persistence policy is easy to audit.
     */
    private void persistDefinition(GameDefinition target) {
        try {
            Files.createDirectories(definitionPath.getParent());
            Files.writeString(definitionPath, Json.stringify(target.toMap()), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to save the game definition.", ex);
        }
    }

    /**
     * Normalizes user-editable setup data so downstream game flow can rely on bounds.
     */
    private void normalizeDefinition(GameDefinition target) {
        target.config.teamCount = clamp(target.config.teamCount, 2, 8);
        target.config.maxPlayersPerTeam = clamp(target.config.maxPlayersPerTeam, 1, 8);
        target.config.categoryCount = clamp(target.config.categoryCount, 1, 10);
        target.config.cluesPerCategory = clamp(target.config.cluesPerCategory, 1, 10);
        target.config.singleBaseValue = Math.max(100, target.config.singleBaseValue);
        target.config.doubleBaseValue = Math.max(200, target.config.doubleBaseValue);
        target.config.buzzWindowSeconds = clamp(target.config.buzzWindowSeconds, 1, 30);
        target.config.responseWindowSeconds = clamp(target.config.responseWindowSeconds, 1, 30);
        target.config.dailyDoubleResponseSeconds = clamp(target.config.dailyDoubleResponseSeconds, 1, 60);
        target.config.finalWagerSeconds = clamp(target.config.finalWagerSeconds, 1, 120);
        target.config.finalResponseSeconds = clamp(target.config.finalResponseSeconds, 1, 120);
        target.config.displayThemeColor = normalizeThemeColor(target.config.displayThemeColor);
        target.config.displayThemeMode = normalizeThemeMode(target.config.displayThemeMode);

        while (target.teams.size() < target.config.teamCount) {
            TeamDefinition team = new TeamDefinition();
            int index = target.teams.size() + 1;
            team.id = "team-" + index;
            team.name = "Team " + index;
            team.color = defaultTeamColor(index);
            team.active = index <= 3;
            target.teams.add(team);
        }
        if (target.teams.size() > target.config.teamCount) {
            target.teams = new ArrayList<>(target.teams.subList(0, target.config.teamCount));
        }
        for (int index = 0; index < target.teams.size(); index++) {
            TeamDefinition team = target.teams.get(index);
            if (team.id == null || team.id.isBlank()) {
                team.id = "team-" + (index + 1);
            }
            if (team.name == null || team.name.isBlank()) {
                team.name = "Team " + (index + 1);
            }
            if (team.color == null || team.color.isBlank()) {
                team.color = defaultTeamColor(index + 1);
            }
        }

        normalizeBoard(target.singleBoard, "single", target.config.categoryCount, target.config.cluesPerCategory, target.config.singleBaseValue, 1);
        normalizeBoard(target.doubleBoard, "double", target.config.categoryCount, target.config.cluesPerCategory, target.config.doubleBaseValue, 2);

        if (target.finalJeopardy == null) {
            target.finalJeopardy = FinalJeopardy.defaults();
        }
        if (target.finalJeopardy.category == null || target.finalJeopardy.category.isBlank()) {
            target.finalJeopardy.category = "Chemical Engineering";
        }
        if (target.finalJeopardy.clue == null || target.finalJeopardy.clue.isBlank()) {
            target.finalJeopardy.clue = "Enter the Final Jeopardy clue here.";
        }
        if (target.finalJeopardy.response == null || target.finalJeopardy.response.isBlank()) {
            target.finalJeopardy.response = "Enter the correct Final Jeopardy response here.";
        }

        if (target.tieBreakers == null) {
            target.tieBreakers = new ArrayList<>();
        }
        if (target.tieBreakers.isEmpty()) {
            TieBreaker tieBreaker = new TieBreaker();
            tieBreaker.category = "Chemical Engineering";
            tieBreaker.clue = "Enter the first tie-breaker clue here.";
            tieBreaker.response = "Enter the first tie-breaker response here.";
            target.tieBreakers.add(tieBreaker);
        }
    }

    private void normalizeBoard(Board board, String key, int categoryCount, int cluesPerCategory, int baseValue, int dailyDoubleDefaultCount) {
        if (board == null) {
            board = new Board();
            if ("single".equals(key)) {
                definition.singleBoard = board;
            } else {
                definition.doubleBoard = board;
            }
        }
        board.key = key;
        board.title = "single".equals(key) ? "Jeopardy" : "Double Jeopardy";
        if (board.categories == null) {
            board.categories = new ArrayList<>();
        }
        while (board.categories.size() < categoryCount) {
            board.categories.add(new Category());
        }
        if (board.categories.size() > categoryCount) {
            board.categories = new ArrayList<>(board.categories.subList(0, categoryCount));
        }

        int selectedDailyDoubles = 0;
        for (int categoryIndex = 0; categoryIndex < board.categories.size(); categoryIndex++) {
            Category category = board.categories.get(categoryIndex);
            category.id = key + "-category-" + (categoryIndex + 1);
            if (category.name == null || category.name.isBlank()) {
                category.name = ("single".equals(key) ? "Single" : "Double") + " Category " + (categoryIndex + 1);
            }
            if (category.clues == null) {
                category.clues = new ArrayList<>();
            }
            while (category.clues.size() < cluesPerCategory) {
                category.clues.add(new Clue());
            }
            if (category.clues.size() > cluesPerCategory) {
                category.clues = new ArrayList<>(category.clues.subList(0, cluesPerCategory));
            }
            for (int clueIndex = 0; clueIndex < category.clues.size(); clueIndex++) {
                Clue clue = category.clues.get(clueIndex);
                clue.id = key + "-c" + (categoryIndex + 1) + "-q" + (clueIndex + 1);
                clue.categoryName = category.name;
                clue.value = baseValue * (clueIndex + 1);
                clue.prompt = clue.prompt == null || clue.prompt.isBlank()
                        ? "Enter the clue prompt for " + category.name + " " + clue.value + "."
                        : clue.prompt;
                clue.response = clue.response == null || clue.response.isBlank()
                        ? "Enter the correct response for " + category.name + " " + clue.value + "."
                        : clue.response;
                clue.used = false;
                if (clue.dailyDouble) {
                    selectedDailyDoubles++;
                }
            }
        }

        if (selectedDailyDoubles == 0 && dailyDoubleDefaultCount > 0) {
            List<Clue> clues = board.categories.stream().flatMap(category -> category.clues.stream()).toList();
            for (int index = 0; index < Math.min(dailyDoubleDefaultCount, clues.size()); index++) {
                clues.get(Math.min(index * 4 + 2, clues.size() - 1)).dailyDouble = true;
            }
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String defaultTeamColor(int index) {
        String[] colors = {"#ffd166", "#ef476f", "#06d6a0", "#118ab2", "#f78c6b", "#c77dff", "#8ecae6", "#90be6d"};
        return colors[(index - 1) % colors.length];
    }

    private Map<String, Object> snapshot(boolean moderatorView) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("serverTime", System.currentTimeMillis());
        root.put("definition", moderatorView ? definition.toMap() : definition.toPublicMap());
        root.put("game", state.toMap(definition, moderatorView));
        if (moderatorView) {
            root.put("definitionPath", definitionPath.toString());
            root.put("persistDefinitionChanges", persistDefinitionChanges);
        }
        return root;
    }

    private enum Phase {
        /** Moderator is configuring the game and players can join. */
        SETUP,
        /** Standard clue board is visible and ready for clue selection. */
        ROUND_BOARD,
        /** Moderator is reading the clue; early buzzes are penalized. */
        CLUE_READING,
        /** Teams may legally buzz. */
        BUZZ_OPEN,
        /** A player is recognized and responding. */
        PLAYER_RESPONSE,
        /** Correct response is visible until the moderator returns to the board. */
        CLUE_REVEAL,
        /** Daily Double owner is entering a wager. */
        DAILY_DOUBLE_WAGER,
        /** Daily Double owner is answering. */
        DAILY_DOUBLE_RESPONSE,
        /** Final Jeopardy category has been revealed. */
        FINAL_CATEGORY,
        /** Eligible teams are entering Final Jeopardy wagers. */
        FINAL_WAGER,
        /** Moderator can reveal the Final Jeopardy clue. */
        FINAL_CLUE_READY,
        /** Eligible teams are entering Final Jeopardy responses. */
        FINAL_RESPONSE,
        /** Moderator is revealing and judging Final Jeopardy responses. */
        FINAL_REVEAL,
        /** A tie-breaker clue can be prepared. */
        TIEBREAKER_READY,
        /** The match has ended. */
        GAME_OVER
    }

    private enum RoundType {
        /** First standard Jeopardy round. */
        SINGLE,
        /** Higher-value Double Jeopardy round. */
        DOUBLE,
        /** Final Jeopardy round, covering category, wager, response, and reveal phases. */
        FINAL,
        /** Sudden-death tie-breaker round. */
        TIEBREAKER
    }

    /**
     * Immutable buzz value object. Comparable implements the fair-buzz ordering rule.
     */
    private static final class BuzzRecord implements Comparable<BuzzRecord> {
        /** Player who submitted the buzz. */
        private final String playerId;
        /** Team represented by the buzz. */
        private final String teamId;
        /** Client timestamp corrected by the synchronization estimate. */
        private final long syncedTimestamp;
        /** Server arrival timestamp used only as a deterministic tie-breaker. */
        private final long arrivalTimestamp;

        private BuzzRecord(String playerId, String teamId, long syncedTimestamp, long arrivalTimestamp) {
            this.playerId = playerId;
            this.teamId = teamId;
            this.syncedTimestamp = syncedTimestamp;
            this.arrivalTimestamp = arrivalTimestamp;
        }

        @Override
        public int compareTo(BuzzRecord other) {
            int compare = Long.compare(this.syncedTimestamp, other.syncedTimestamp);
            if (compare != 0) {
                return compare;
            }
            compare = Long.compare(this.arrivalTimestamp, other.arrivalTimestamp);
            if (compare != 0) {
                return compare;
            }
            return this.playerId.compareTo(other.playerId);
        }
    }

    /**
     * Runtime timer model with a scheduled callback.
     */
    private static final class Countdown {
        /** Human-readable label shown in the UIs. */
        private String label;
        /** Original countdown duration. */
        private int totalSeconds;
        /** Epoch millisecond when the countdown expires. */
        private long endsAt;
        /** Scheduler handle used to cancel stale timers. */
        private ScheduledFuture<?> future;

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("label", label);
            map.put("totalSeconds", totalSeconds);
            map.put("endsAt", endsAt);
            return map;
        }
    }

    /**
     * Mutable state for one running game session.
     */
    private static final class RuntimeState {
        /** Current state-machine phase. */
        private Phase phase;
        /** Current round type. */
        private RoundType currentRound;
        /** Team allowed to choose the next standard clue. */
        private String chooserTeamId;
        /** Team that selected the active clue. */
        private String activeClueSelectingTeamId;
        /** Active clue being read, answered, or revealed. */
        private Clue activeClue;
        /** True only after the official response should be visible publicly. */
        private boolean answerVisible;
        /** Title for the most recently revealed response. */
        private String lastRevealTitle = "";
        /** Official response from the most recently revealed clue. */
        private String lastRevealResponse = "";
        /** Team currently recognized to answer. */
        private String recognizedTeamId;
        /** Player currently recognized to answer. */
        private String recognizedPlayerId;
        /** Current Daily Double wager. */
        private int dailyDoubleWager;
        /** Moderator-facing status message mirrored to public views when safe. */
        private String statusMessage;
        /** Runtime score and roster state keyed by team ID. */
        private Map<String, TeamRuntime> teamStates;
        /** Runtime player sessions keyed by player ID. */
        private Map<String, PlayerRuntime> players;
        /** Pending buzzes gathered during the settling window. */
        private Map<String, BuzzRecord> pendingBuzzes;
        /** Guard flag that prevents scheduling duplicate buzz-resolution callbacks. */
        private boolean buzzResolutionScheduled;
        /** Teams no longer eligible to buzz for the current clue. */
        private Set<String> lockedOutTeamIds;
        /** Active countdown, if any. */
        private Countdown countdown;
        /** Final Jeopardy reveal order, low score to high score. */
        private List<String> finalRevealOrder;
        /** Index into finalRevealOrder. */
        private int finalRevealIndex;
        /** True when first place is tied and tie-breakers are needed. */
        private boolean tiePending;
        /** Team IDs still involved in a tie-breaker. */
        private List<String> tieTeamIds;
        /** Next tie-breaker clue index. */
        private int tieBreakerIndex;
        /** Winner team ID after the game is complete. */
        private String winnerTeamId;

        private Map<String, Object> toMap(GameDefinition definition, boolean moderatorView) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("phase", phase.name());
            map.put("round", currentRound.name());
            map.put("statusMessage", statusMessage);
            map.put("chooserTeamId", chooserTeamId);
            map.put("chooserTeamName", chooserTeamId == null ? "" : teamStates.get(chooserTeamId).name);
            map.put("activeClueSelectingTeamId", activeClueSelectingTeamId);
            map.put("answerVisible", answerVisible);
            map.put("lastRevealTitle", lastRevealTitle);
            map.put("lastRevealResponse", lastRevealResponse);
            map.put("recognizedTeamId", recognizedTeamId);
            map.put("recognizedPlayerId", recognizedPlayerId);
            map.put("recognizedTeamName", recognizedTeamId == null ? "" : teamStates.get(recognizedTeamId).name);
            map.put("recognizedPlayerName", recognizedPlayerId == null || !players.containsKey(recognizedPlayerId)
                    ? ""
                    : players.get(recognizedPlayerId).displayName);
            map.put("lockedOutTeamIds", new ArrayList<>(lockedOutTeamIds));
            map.put("winnerTeamId", winnerTeamId);
            map.put("tiePending", tiePending);
            map.put("tieTeamIds", new ArrayList<>(tieTeamIds));
            map.put("dailyDoubleWager", dailyDoubleWager);
            map.put("finalRevealOrder", new ArrayList<>(finalRevealOrder));
            map.put("finalRevealIndex", finalRevealIndex);
            map.put("finalRevealTeamId", finalRevealIndex < finalRevealOrder.size() ? finalRevealOrder.get(finalRevealIndex) : null);

            if (countdown != null) {
                map.put("countdown", countdown.toMap());
            } else {
                map.put("countdown", null);
            }

            if (activeClue != null) {
                Map<String, Object> clue = new LinkedHashMap<>();
                clue.put("id", activeClue.id);
                clue.put("categoryName", activeClue.categoryName);
                clue.put("value", activeClue.value);
                clue.put("dailyDouble", activeClue.dailyDouble);
                if (activeClue.image != null) {
                    clue.put("image", activeClue.image.toMap());
                }
                boolean revealPrompt = moderatorView || !(activeClue.dailyDouble && phase == Phase.DAILY_DOUBLE_WAGER);
                if (revealPrompt) {
                    clue.put("prompt", activeClue.prompt);
                }
                if (moderatorView || answerVisible) {
                    clue.put("response", activeClue.response);
                }
                map.put("activeClue", clue);
            } else {
                map.put("activeClue", null);
            }

            List<Map<String, Object>> teams = new ArrayList<>();
            for (TeamRuntime team : teamStates.values()) {
                Map<String, Object> teamMap = new LinkedHashMap<>();
                teamMap.put("id", team.id);
                teamMap.put("name", team.name);
                teamMap.put("color", team.color);
                teamMap.put("active", team.active);
                teamMap.put("score", team.score);
                teamMap.put("finalEligible", team.finalEligible);
                teamMap.put("finalWagerSubmitted", team.finalWagerSubmitted);
                teamMap.put("finalResponseSubmitted", team.finalResponseSubmitted);
                teamMap.put("finalJudged", team.finalJudged);
                teamMap.put("finalResultCorrect", team.finalResultCorrect);
                if (moderatorView || team.finalJudged) {
                    teamMap.put("finalWager", team.finalWager);
                    teamMap.put("finalResponse", team.finalResponse);
                }
                List<Map<String, Object>> roster = new ArrayList<>();
                for (String playerId : team.players) {
                    PlayerRuntime player = players.get(playerId);
                    if (player == null) {
                        continue;
                    }
                    Map<String, Object> playerMap = new LinkedHashMap<>();
                    playerMap.put("id", player.id);
                    playerMap.put("displayName", player.displayName);
                    playerMap.put("connected", player.connected);
                    playerMap.put("lastSyncAt", player.lastSyncAt);
                    roster.add(playerMap);
                }
                teamMap.put("players", roster);
                teams.add(teamMap);
            }
            map.put("teams", teams);

            Board board = switch (currentRound) {
                case SINGLE -> definition.singleBoard;
                case DOUBLE -> definition.doubleBoard;
                case FINAL -> null;
                case TIEBREAKER -> null;
            };
            map.put("currentBoard", board == null ? null : board.toMap(moderatorView));
            boolean revealFinalCategory = phase == Phase.FINAL_CATEGORY
                    || phase == Phase.FINAL_WAGER
                    || phase == Phase.FINAL_CLUE_READY
                    || phase == Phase.FINAL_RESPONSE
                    || phase == Phase.FINAL_REVEAL
                    || phase == Phase.GAME_OVER
                    || tiePending;
            boolean revealFinalClue = moderatorView
                    || phase == Phase.FINAL_RESPONSE
                    || phase == Phase.FINAL_REVEAL
                    || phase == Phase.GAME_OVER;
            map.put("finalJeopardy", definition.finalJeopardy.toPublicMap(moderatorView, revealFinalCategory, revealFinalClue));
            map.put("tieBreakers", moderatorView
                    ? definition.tieBreakers.stream().map(TieBreaker::toMap).toList()
                    : definition.tieBreakers.stream().map(TieBreaker::toPublicMap).toList());

            return map;
        }
    }

    /**
     * Runtime player session data kept private to preserve session-key integrity.
     */
    private static final class PlayerRuntime {
        /** Stable player identifier returned to the browser after joining. */
        private String id;
        /** Secret browser session key used to authorize player actions. */
        private String sessionKey;
        /** Player-facing display name. */
        private String displayName;
        /** Team selected by the player. */
        private String teamId;
        /** Connection hint updated when the player syncs or acts. */
        private boolean connected;
        /** Join timestamp. */
        private long joinedAt;
        /** Last successful clock synchronization timestamp. */
        private long lastSyncAt;
        /** Last client send timestamp from sync. */
        private long lastClientSendAt;
        /** Last client receive timestamp from sync. */
        private long lastClientReceiveAt;
    }

    /**
     * Runtime team data: score, roster, and Final Jeopardy state.
     */
    private static final class TeamRuntime {
        /** Stable team identifier from the definition file. */
        private String id;
        /** Display name shown in all UIs. */
        private String name;
        /** Team accent color. */
        private String color;
        /** Whether this team participates in the current setup. */
        private boolean active;
        /** Current score. */
        private int score;
        /** Player IDs currently assigned to this team. */
        private List<String> players;
        /** Whether the team can play Final Jeopardy. */
        private boolean finalEligible;
        /** Final Jeopardy wager. */
        private int finalWager;
        /** True after the team submits a Final Jeopardy wager. */
        private boolean finalWagerSubmitted;
        /** Typed Final Jeopardy response. */
        private String finalResponse = "";
        /** True after the team submits a nonblank final response. */
        private boolean finalResponseSubmitted;
        /** True after the moderator judges this team's final response. */
        private boolean finalJudged;
        /** Moderator judgment result for Final Jeopardy. */
        private boolean finalResultCorrect;
    }

    /**
     * Full editable source-file model for one game.
     */
    private static final class GameDefinition {
        /** Game title shown in setup and browser titles. */
        private String title;
        /** Numeric and boolean game configuration. */
        private GameConfig config;
        /** Team definitions. */
        private List<TeamDefinition> teams;
        /** Single Jeopardy board. */
        private Board singleBoard;
        /** Double Jeopardy board. */
        private Board doubleBoard;
        /** Final Jeopardy content. */
        private FinalJeopardy finalJeopardy;
        /** Tie-breaker clue queue. */
        private List<TieBreaker> tieBreakers;

        /**
         * Factory method for a complete default definition.
         */
        private static GameDefinition defaults() {
            GameDefinition definition = new GameDefinition();
            definition.title = "ChemE Jeopardy";
            definition.config = GameConfig.defaults();
            definition.teams = new ArrayList<>();
            definition.singleBoard = new Board();
            definition.doubleBoard = new Board();
            definition.finalJeopardy = FinalJeopardy.defaults();
            definition.tieBreakers = new ArrayList<>();
            return definition;
        }

        /**
         * Factory method that maps parsed JSON into the typed definition model.
         */
        private static GameDefinition fromMap(Map<String, Object> map) {
            GameDefinition definition = defaults();
            definition.title = Json.asString(map.get("title"), definition.title);
            definition.config = GameConfig.fromMap(Json.asObject(map.get("config")));
            definition.teams = new ArrayList<>();
            for (Object raw : Json.asList(map.get("teams"))) {
                definition.teams.add(TeamDefinition.fromMap(Json.asObject(raw)));
            }
            definition.singleBoard = Board.fromMap(Json.asObject(map.get("singleBoard")));
            definition.doubleBoard = Board.fromMap(Json.asObject(map.get("doubleBoard")));
            definition.finalJeopardy = FinalJeopardy.fromMap(Json.asObject(map.get("finalJeopardy")));
            definition.tieBreakers = new ArrayList<>();
            for (Object raw : Json.asList(map.get("tieBreakers"))) {
                definition.tieBreakers.add(TieBreaker.fromMap(Json.asObject(raw)));
            }
            return definition;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", title);
            map.put("config", config.toMap());
            map.put("teams", teams.stream().map(TeamDefinition::toMap).toList());
            map.put("singleBoard", singleBoard.toMap(true));
            map.put("doubleBoard", doubleBoard.toMap(true));
            map.put("finalJeopardy", finalJeopardy.toMap());
            map.put("tieBreakers", tieBreakers.stream().map(TieBreaker::toMap).toList());
            return map;
        }

        private Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("title", title);
            map.put("config", config.toMap());
            map.put("teams", teams.stream().map(TeamDefinition::toMap).toList());
            map.put("singleBoard", singleBoard.toMap(false));
            map.put("doubleBoard", doubleBoard.toMap(false));
            map.put("finalJeopardy", finalJeopardy.toPublicMap(false, false, false));
            map.put("tieBreakers", tieBreakers.stream().map(TieBreaker::toPublicMap).toList());
            return map;
        }
    }

    /**
     * User-editable game settings.
     */
    private static final class GameConfig {
        /** Number of configured teams. */
        private int teamCount;
        /** Maximum player sessions allowed per team. */
        private int maxPlayersPerTeam;
        /** Categories per standard board. */
        private int categoryCount;
        /** Clues per category. */
        private int cluesPerCategory;
        /** Base value for Single Jeopardy clues. */
        private int singleBaseValue;
        /** Base value for Double Jeopardy clues. */
        private int doubleBaseValue;
        /** Seconds teams have to buzz after reading. */
        private int buzzWindowSeconds;
        /** Seconds a recognized player has to answer. */
        private int responseWindowSeconds;
        /** Seconds for Daily Double responses. */
        private int dailyDoubleResponseSeconds;
        /** Seconds for Final Jeopardy wagers. */
        private int finalWagerSeconds;
        /** Seconds for Final Jeopardy responses. */
        private int finalResponseSeconds;
        /** Whether Double Jeopardy is included. */
        private boolean includeDoubleJeopardy;
        /** Whether Final Jeopardy is included. */
        private boolean includeFinalJeopardy;
        /** Display color family controlled by the moderator. */
        private String displayThemeColor;
        /** Display light/dark mode controlled by the moderator. */
        private String displayThemeMode;

        private static GameConfig defaults() {
            GameConfig config = new GameConfig();
            config.teamCount = 3;
            config.maxPlayersPerTeam = 4;
            config.categoryCount = 6;
            config.cluesPerCategory = 5;
            config.singleBaseValue = 100;
            config.doubleBaseValue = 200;
            config.buzzWindowSeconds = 5;
            config.responseWindowSeconds = 5;
            config.dailyDoubleResponseSeconds = 10;
            config.finalWagerSeconds = 30;
            config.finalResponseSeconds = 30;
            config.includeDoubleJeopardy = true;
            config.includeFinalJeopardy = true;
            config.displayThemeColor = "blue";
            config.displayThemeMode = "dark";
            return config;
        }

        private static GameConfig fromMap(Map<String, Object> map) {
            GameConfig config = defaults();
            config.teamCount = Json.asInt(map.get("teamCount"), config.teamCount);
            config.maxPlayersPerTeam = Json.asInt(map.get("maxPlayersPerTeam"), config.maxPlayersPerTeam);
            config.categoryCount = Json.asInt(map.get("categoryCount"), config.categoryCount);
            config.cluesPerCategory = Json.asInt(map.get("cluesPerCategory"), config.cluesPerCategory);
            config.singleBaseValue = Json.asInt(map.get("singleBaseValue"), config.singleBaseValue);
            config.doubleBaseValue = Json.asInt(map.get("doubleBaseValue"), config.doubleBaseValue);
            config.buzzWindowSeconds = Json.asInt(map.get("buzzWindowSeconds"), config.buzzWindowSeconds);
            config.responseWindowSeconds = Json.asInt(map.get("responseWindowSeconds"), config.responseWindowSeconds);
            config.dailyDoubleResponseSeconds = Json.asInt(map.get("dailyDoubleResponseSeconds"), config.dailyDoubleResponseSeconds);
            config.finalWagerSeconds = Json.asInt(map.get("finalWagerSeconds"), config.finalWagerSeconds);
            config.finalResponseSeconds = Json.asInt(map.get("finalResponseSeconds"), config.finalResponseSeconds);
            config.includeDoubleJeopardy = Json.asBoolean(map.get("includeDoubleJeopardy"), config.includeDoubleJeopardy);
            config.includeFinalJeopardy = Json.asBoolean(map.get("includeFinalJeopardy"), config.includeFinalJeopardy);
            config.displayThemeColor = Json.asString(map.get("displayThemeColor"), config.displayThemeColor);
            config.displayThemeMode = Json.asString(map.get("displayThemeMode"), config.displayThemeMode);
            return config;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("teamCount", teamCount);
            map.put("maxPlayersPerTeam", maxPlayersPerTeam);
            map.put("categoryCount", categoryCount);
            map.put("cluesPerCategory", cluesPerCategory);
            map.put("singleBaseValue", singleBaseValue);
            map.put("doubleBaseValue", doubleBaseValue);
            map.put("buzzWindowSeconds", buzzWindowSeconds);
            map.put("responseWindowSeconds", responseWindowSeconds);
            map.put("dailyDoubleResponseSeconds", dailyDoubleResponseSeconds);
            map.put("finalWagerSeconds", finalWagerSeconds);
            map.put("finalResponseSeconds", finalResponseSeconds);
            map.put("includeDoubleJeopardy", includeDoubleJeopardy);
            map.put("includeFinalJeopardy", includeFinalJeopardy);
            map.put("displayThemeColor", displayThemeColor);
            map.put("displayThemeMode", displayThemeMode);
            return map;
        }
    }

    /**
     * Editable team metadata from the source file.
     */
    private static final class TeamDefinition {
        /** Stable team identifier. */
        private String id;
        /** Display name. */
        private String name;
        /** Accent color in CSS hex format. */
        private String color;
        /** Whether the team is active in this game. */
        private boolean active;

        private static TeamDefinition fromMap(Map<String, Object> map) {
            TeamDefinition team = new TeamDefinition();
            team.id = Json.asString(map.get("id"), "");
            team.name = Json.asString(map.get("name"), "");
            team.color = Json.asString(map.get("color"), "");
            team.active = Json.asBoolean(map.get("active"), true);
            return team;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("color", color);
            map.put("active", active);
            return map;
        }
    }

    /**
     * Editable board definition.
     */
    private static final class Board {
        /** Round key: single or double. */
        private String key;
        /** Board title. */
        private String title;
        /** Category columns. */
        private List<Category> categories;

        private static Board fromMap(Map<String, Object> map) {
            Board board = new Board();
            board.key = Json.asString(map.get("key"), "");
            board.title = Json.asString(map.get("title"), "");
            board.categories = new ArrayList<>();
            for (Object raw : Json.asList(map.get("categories"))) {
                board.categories.add(Category.fromMap(Json.asObject(raw)));
            }
            return board;
        }

        private Map<String, Object> toMap(boolean moderatorView) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", key);
            map.put("title", title);
            map.put("categories", categories == null
                    ? List.of()
                    : categories.stream().map(category -> category.toMap(moderatorView)).toList());
            return map;
        }
    }

    /**
     * Editable category definition containing a list of clues.
     */
    private static final class Category {
        /** Stable category identifier. */
        private String id;
        /** Category label shown atop the board. */
        private String name;
        /** Clues in ascending value order. */
        private List<Clue> clues;

        private static Category fromMap(Map<String, Object> map) {
            Category category = new Category();
            category.id = Json.asString(map.get("id"), "");
            category.name = Json.asString(map.get("name"), "");
            category.clues = new ArrayList<>();
            for (Object raw : Json.asList(map.get("clues"))) {
                category.clues.add(Clue.fromMap(Json.asObject(raw)));
            }
            return category;
        }

        private Map<String, Object> toMap(boolean moderatorView) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("name", name);
            map.put("clues", clues == null
                    ? List.of()
                    : clues.stream().map(clue -> clue.toMap(moderatorView)).toList());
            return map;
        }
    }

    /**
     * Editable clue definition.
     */
    private static final class Clue {
        /** Stable clue identifier. */
        private String id;
        /** Cached category name for display during clue reveal. */
        private String categoryName;
        /** Prompt read by the moderator and shown on the display. */
        private String prompt;
        /** Optional image shown with this clue. */
        private ClueImage image;
        /** Official response visible after moderator judgment. */
        private String response;
        /** Point value. */
        private int value;
        /** Whether this clue is a Daily Double. */
        private boolean dailyDouble;
        /** Runtime board-completion flag. */
        private boolean used;

        private static Clue fromMap(Map<String, Object> map) {
            Clue clue = new Clue();
            clue.id = Json.asString(map.get("id"), "");
            clue.categoryName = Json.asString(map.get("categoryName"), "");
            clue.prompt = Json.asString(map.get("prompt"), "");
            clue.image = ClueImage.fromObject(map.get("image"));
            if (clue.image == null) {
                clue.image = ClueImage.fromObject(map.get("imageUrl"));
            }
            clue.response = Json.asString(map.get("response"), "");
            clue.value = Json.asInt(map.get("value"), 0);
            clue.dailyDouble = Json.asBoolean(map.get("dailyDouble"), false);
            clue.used = Json.asBoolean(map.get("used"), false);
            return clue;
        }

        private Map<String, Object> toMap(boolean moderatorView) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("categoryName", categoryName);
            map.put("value", value);
            map.put("used", used);
            if (moderatorView) {
                map.put("dailyDouble", dailyDouble);
                map.put("prompt", prompt);
                if (image != null) {
                    map.put("image", image.toMap());
                }
                map.put("response", response);
            }
            return map;
        }
    }

    /**
     * Optional image metadata attached to a clue.
     */
    private static final class ClueImage {
        /** Relative package path, absolute app path, or external HTTPS URL. */
        private String src;
        /** Accessible image description. */
        private String alt;

        private static ClueImage fromObject(Object raw) {
            if (raw instanceof String rawString) {
                String src = rawString.trim();
                if (src.isBlank()) {
                    return null;
                }
                ClueImage image = new ClueImage();
                image.src = src;
                image.alt = "";
                return image;
            }
            Map<String, Object> map = Json.asObject(raw);
            String src = Json.asString(map.get("src"), "");
            if (src.isBlank()) {
                src = Json.asString(map.get("url"), "");
            }
            if (src.isBlank()) {
                return null;
            }
            ClueImage image = new ClueImage();
            image.src = src;
            image.alt = Json.asString(map.get("alt"), "");
            return image;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("src", src);
            map.put("alt", alt);
            return map;
        }
    }

    /**
     * Editable Final Jeopardy definition.
     */
    private static final class FinalJeopardy {
        /** Final Jeopardy category. */
        private String category;
        /** Final Jeopardy clue. */
        private String clue;
        /** Optional image shown with the Final Jeopardy clue. */
        private ClueImage image;
        /** Official Final Jeopardy response. */
        private String response;

        private static FinalJeopardy defaults() {
            FinalJeopardy finalJeopardy = new FinalJeopardy();
            finalJeopardy.category = "Chemical Engineering";
            finalJeopardy.clue = "Enter the Final Jeopardy clue here.";
            finalJeopardy.response = "Enter the Final Jeopardy response here.";
            return finalJeopardy;
        }

        private static FinalJeopardy fromMap(Map<String, Object> map) {
            FinalJeopardy finalJeopardy = defaults();
            finalJeopardy.category = Json.asString(map.get("category"), finalJeopardy.category);
            finalJeopardy.clue = Json.asString(map.get("clue"), finalJeopardy.clue);
            finalJeopardy.image = ClueImage.fromObject(map.get("image"));
            if (finalJeopardy.image == null) {
                finalJeopardy.image = ClueImage.fromObject(map.get("imageUrl"));
            }
            finalJeopardy.response = Json.asString(map.get("response"), finalJeopardy.response);
            return finalJeopardy;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", category);
            map.put("clue", clue);
            if (image != null) {
                map.put("image", image.toMap());
            }
            map.put("response", response);
            return map;
        }

        private Map<String, Object> toPublicMap(boolean moderatorView, boolean revealCategory, boolean revealClue) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", moderatorView || revealCategory ? category : null);
            map.put("clue", moderatorView || revealClue ? clue : null);
            map.put("image", (moderatorView || revealClue) && image != null ? image.toMap() : null);
            map.put("response", moderatorView ? response : null);
            return map;
        }
    }

    /**
     * Editable tie-breaker clue definition.
     */
    private static final class TieBreaker {
        /** Tie-breaker category. */
        private String category;
        /** Tie-breaker clue. */
        private String clue;
        /** Optional image shown with the tie-breaker clue. */
        private ClueImage image;
        /** Official tie-breaker response. */
        private String response;

        private static TieBreaker fromMap(Map<String, Object> map) {
            TieBreaker tieBreaker = new TieBreaker();
            tieBreaker.category = Json.asString(map.get("category"), "");
            tieBreaker.clue = Json.asString(map.get("clue"), "");
            tieBreaker.image = ClueImage.fromObject(map.get("image"));
            if (tieBreaker.image == null) {
                tieBreaker.image = ClueImage.fromObject(map.get("imageUrl"));
            }
            tieBreaker.response = Json.asString(map.get("response"), "");
            return tieBreaker;
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", category);
            map.put("clue", clue);
            if (image != null) {
                map.put("image", image.toMap());
            }
            map.put("response", response);
            return map;
        }

        private Map<String, Object> toPublicMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("category", category);
            return map;
        }
    }
}
