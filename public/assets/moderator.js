/*
 * File: moderator.js
 * Description: Moderator UI state manager for authentication, setup editing, game control, and scoring.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */

const moderatorBasePath = gameBasePath();

const moderatorState = {
    snapshot: null,
    draft: null,
    dirty: false,
    toastTimer: null,
    authenticated: false,
    eventSource: null,
};

const teamColors = ['#ffd166', '#ef476f', '#06d6a0', '#118ab2', '#f78c6b', '#c77dff', '#8ecae6', '#90be6d'];

document.addEventListener('DOMContentLoaded', async () => {
    bindModeratorTheme();
    bindModeratorEvents();
    await refreshModeratorAuth();
});

function bindModeratorEvents() {
    document.getElementById('moderator-login-form').addEventListener('submit', handleModeratorLogin);
    document.getElementById('moderator-logout-button').addEventListener('click', handleModeratorLogout);
    document.getElementById('player-password-form').addEventListener('submit', handlePlayerPasswordSubmit);
    document.getElementById('save-definition-button').addEventListener('click', saveDefinition);
    document.getElementById('reset-runtime-button').addEventListener('click', async () => {
        const response = await post('/api/mod/reset');
        showToast(response.message);
    });
    document.getElementById('start-game-button').addEventListener('click', async () => {
        const response = await post('/api/mod/start');
        showToast(response.message);
    });
    document.getElementById('setup-editors').addEventListener('input', handleDraftInput);
    document.getElementById('setup-editors').addEventListener('change', handleDraftInput);
    document.getElementById('setup-editors').addEventListener('click', handleSetupClick);
    document.getElementById('moderator-controls').addEventListener('click', handleControlClick);
    document.getElementById('moderator-roster').addEventListener('click', handleControlClick);
    document.getElementById('moderator-board').addEventListener('click', handleBoardClick);
    document.getElementById('display-theme-form').addEventListener('change', handleDisplayThemeChange);
}

function bindModeratorTheme() {
    const defaults = { color: 'blue', mode: 'dark' };
    ChemETheme.apply(defaults, defaults);
    ChemETheme.setControls('moderator-theme-color', 'moderator-theme-mode', defaults);
    const update = () => {
        ChemETheme.apply(
            ChemETheme.readControls('moderator-theme-color', 'moderator-theme-mode', defaults),
            defaults,
        );
    };
    document.getElementById('moderator-theme-color')?.addEventListener('change', update);
    document.getElementById('moderator-theme-mode')?.addEventListener('change', update);
}

async function refreshModeratorAuth() {
    const response = await fetch(apiUrl('/api/mod/auth-status'));
    const payload = await response.json();
    if (payload.authenticated) {
        setModeratorAuthenticated(true);
        await refreshModeratorState();
        connectModeratorEvents();
    } else {
        setModeratorAuthenticated(false);
    }
}

async function handleModeratorLogin(event) {
    event.preventDefault();
    const passwordInput = document.getElementById('moderator-password');
    const response = await postForm('/api/mod/login', { password: passwordInput.value }, false);
    if (!response.ok) {
        showToast(response.message || 'Unable to unlock moderator console.');
        return;
    }
    passwordInput.value = '';
    setModeratorAuthenticated(true);
    showToast(response.message);
    await refreshModeratorState();
    connectModeratorEvents();
}

async function handleModeratorLogout() {
    const response = await post('/api/mod/logout', false);
    setModeratorAuthenticated(false);
    showToast(response.message || 'Moderator console locked.');
}

async function handlePlayerPasswordSubmit(event) {
    event.preventDefault();
    const input = document.getElementById('player-password');
    const response = await postForm('/api/mod/player-password', { password: input.value });
    if (response.ok) {
        input.value = '';
    }
    showToast(response.message);
}

function setModeratorAuthenticated(authenticated) {
    moderatorState.authenticated = authenticated;
    document.getElementById('moderator-login-shell').classList.toggle('hidden', authenticated);
    document.getElementById('moderator-app-shell').classList.toggle('hidden', !authenticated);
    document.getElementById('moderator-phase-chip').textContent = authenticated ? 'Unlocked' : 'Locked';
    if (!authenticated) {
        if (moderatorState.eventSource) {
            moderatorState.eventSource.close();
            moderatorState.eventSource = null;
        }
        moderatorState.snapshot = null;
        moderatorState.draft = null;
        moderatorState.dirty = false;
    }
}

async function refreshModeratorState() {
    const response = await fetch(apiUrl('/api/mod/state'));
    if (response.status === 401) {
        await readJsonResponse(response);
        return;
    }
    moderatorState.snapshot = await response.json();
    if (!moderatorState.draft || !moderatorState.dirty || moderatorState.snapshot.game.phase !== 'SETUP') {
        moderatorState.draft = structuredClone(moderatorState.snapshot.definition);
        normalizeDefinitionDraft(moderatorState.draft);
        moderatorState.dirty = false;
    }
    renderModerator();
}

function connectModeratorEvents() {
    if (!moderatorState.authenticated) {
        return;
    }
    if (moderatorState.eventSource) {
        moderatorState.eventSource.close();
    }
    const source = new EventSource(apiUrl('/api/mod/events'));
    moderatorState.eventSource = source;
    source.addEventListener('state', event => {
        moderatorState.snapshot = JSON.parse(event.data);
        if (!moderatorState.dirty || moderatorState.snapshot.game.phase !== 'SETUP') {
            moderatorState.draft = structuredClone(moderatorState.snapshot.definition);
            normalizeDefinitionDraft(moderatorState.draft);
            moderatorState.dirty = false;
        }
        renderModerator();
    });
    source.onerror = () => {
        setTimeout(() => {
            source.close();
            if (moderatorState.authenticated) {
                refreshModeratorAuth();
            }
        }, 1500);
    };
}

function renderModerator() {
    const snapshot = moderatorState.snapshot;
    if (!snapshot) {
        return;
    }
    const game = snapshot.game;
    const displayTheme = displayThemeFromSnapshot(snapshot);
    ChemETheme.setControls('display-theme-color', 'display-theme-mode', displayTheme);
    document.getElementById('moderator-phase-chip').textContent = `${humanize(game.phase)} • ${humanize(game.round)}`;
    document.getElementById('moderator-alert').textContent = game.statusMessage || 'Waiting for setup.';
    document.getElementById('player-password-status').textContent = snapshot.security?.playerJoinPasswordSet
        ? 'A player password is set. Saving a new one replaces it for future joins.'
        : 'No player password is set yet. Players cannot join until you save one.';
    const setupModeNote = game.phase === 'SETUP'
        ? (moderatorState.dirty ? 'Setup has local changes that still need to be saved.' : 'Setup is editable. Save before starting the game.')
        : 'Settings are editable mid-game. Live scores and used clues are preserved across saves. Removing a team kicks its players.';
    const persistenceNote = snapshot.persistDefinitionChanges
        ? `Save Setup writes to ${snapshot.definitionPath || 'the configured question source file'}.`
        : 'Save Setup updates this running event only; the source file is unchanged.';
    document.getElementById('setup-lock-note').textContent = `${setupModeNote} ${persistenceNote}`;

    const countdown = game.countdown ? `${game.countdown.label} • ${secondsLeft(game.countdown.endsAt)}s` : 'Idle';
    const rosterSummary = playerRosterSummary(snapshot.definition, game);
    document.getElementById('moderator-status-grid').innerHTML = [
        statusCard('Phase', humanize(game.phase)),
        statusCard('Round', humanize(game.round)),
        statusCard('Chooser', game.chooserTeamName || 'Moderator'),
        statusCard('Countdown', countdown),
        statusCard('Players', `${rosterSummary.joined}/${rosterSummary.capacity} players`),
        statusCard('Recognized Player', game.recognizedPlayerName ? `${game.recognizedPlayerName} • ${game.recognizedTeamName}` : 'None'),
        statusCard('Winner', winnerLabel(game)),
    ].join('');

    document.getElementById('moderator-roster').innerHTML = renderModeratorRoster(snapshot.definition, game);
    document.getElementById('moderator-controls').innerHTML = renderControls(snapshot);
    document.getElementById('moderator-final-panel').innerHTML = renderModeratorFinalPanel(snapshot);
    document.getElementById('moderator-board').innerHTML = renderModeratorBoard(game);
    document.getElementById('setup-editors').innerHTML = renderSetupEditors();

    const setupLocked = game.phase !== 'SETUP';
    document.getElementById('save-definition-button').disabled = false;
    document.getElementById('reset-runtime-button').disabled = false;
    document.getElementById('start-game-button').disabled = setupLocked || moderatorState.dirty;
}

async function handleDisplayThemeChange() {
    const theme = ChemETheme.readControls('display-theme-color', 'display-theme-mode', { color: 'blue', mode: 'dark' });
    if (moderatorState.draft?.config) {
        moderatorState.draft.config.displayThemeColor = theme.color;
        moderatorState.draft.config.displayThemeMode = theme.mode;
    }
    const response = await postForm('/api/mod/display-theme', theme);
    showToast(response.message || 'Display appearance updated.');
}

function displayThemeFromSnapshot(snapshot) {
    return ChemETheme.normalize({
        color: snapshot.definition?.config?.displayThemeColor,
        mode: snapshot.definition?.config?.displayThemeMode,
    }, { color: 'blue', mode: 'dark' });
}

function renderControls(snapshot) {
    const game = snapshot.game;
    const activeClue = game.activeClue;
    const teams = game.teams || [];
    const controls = [];

    if (activeClue) {
        controls.push(`
            <article class="clue-card">
                <div class="clue-metadata">
                    <span class="pill">${escapeHtml(activeClue.categoryName)}</span>
                    <span class="pill">${activeClue.value} points</span>
                    ${activeClue.dailyDouble ? '<span class="pill">Daily Double</span>' : ''}
                </div>
                <div class="clue-prompt">${escapeHtml(activeClue.prompt)}</div>
                ${ChemETheme.imageHtml(activeClue.image, moderatorBasePath)}
                ${activeClue.response ? `<div class="clue-response"><strong>Official response:</strong> ${escapeHtml(activeClue.response)}</div>` : ''}
            </article>
        `);
    }

    const actionButtons = [];
    if (game.phase === 'CLUE_READING') {
        actionButtons.push(controlButton('finish-reading', 'Finish Reading And Open Buzzing', 'primary-button'));
    }
    if (game.phase === 'PLAYER_RESPONSE' || game.phase === 'DAILY_DOUBLE_RESPONSE' || game.phase === 'FINAL_REVEAL') {
        actionButtons.push(controlButton('judge-correct', 'Mark Correct', 'primary-button'));
        actionButtons.push(controlButton('judge-incorrect', 'Mark Incorrect', 'danger-button'));
    }
    if (game.phase === 'CLUE_REVEAL') {
        if (!game.answerVisible) {
            actionButtons.push(controlButton('reveal-response', 'Reveal Response', 'primary-button'));
            actionButtons.push(controlButton('continue', 'Return To Board (skip reveal)', 'ghost-button'));
        } else {
            actionButtons.push(controlButton('continue', 'Return To Board', 'primary-button'));
        }
    }
    if (game.phase === 'DAILY_DOUBLE_WAGER') {
        const chooser = teams.find(team => team.id === game.activeClueSelectingTeamId);
        const maxWager = chooser ? Math.max(chooser.score, game.round === 'DOUBLE' ? 1000 : 500) : 0;
        actionButtons.push(`
            <div class="detail-card action-area">
                <span class="label">Daily Double Wager</span>
                <div class="value">${chooser ? `${escapeHtml(chooser.name)} may wager 0 to ${maxWager}.` : 'Set the Daily Double wager.'}</div>
                <input type="number" id="moderator-daily-wager-input" min="0" max="${maxWager}" value="${game.dailyDoubleWager || 0}">
                <div class="button-row">
                    ${controlButton('save-daily-wager', 'Save Wager', 'ghost-button')}
                    ${controlButton('start-daily-double', 'Reveal Daily Double', 'primary-button')}
                </div>
            </div>
        `);
    }
    if (game.phase === 'FINAL_CATEGORY') {
        actionButtons.push(controlButton('start-final-wager', 'Start Final Wager Timer', 'primary-button'));
    }
    if (game.phase === 'FINAL_WAGER') {
        actionButtons.push(controlButton('lock-final-wagers', 'Lock Wagers Now', 'ghost-button'));
    }
    if (game.phase === 'FINAL_CLUE_READY') {
        actionButtons.push(controlButton('reveal-final-clue', 'Reveal Final Jeopardy Clue', 'primary-button'));
    }
    if (game.phase === 'FINAL_RESPONSE') {
        actionButtons.push(controlButton('lock-final-responses', 'Lock Responses Now', 'ghost-button'));
    }
    if (game.phase === 'FINAL_REVEAL') {
        actionButtons.push(controlButton('skip-final-team', 'Skip This Team', 'ghost-button'));
    }
    const finalPhases = ['FINAL_CATEGORY', 'FINAL_WAGER', 'FINAL_CLUE_READY', 'FINAL_RESPONSE', 'FINAL_REVEAL'];
    if (finalPhases.includes(game.phase)) {
        actionButtons.push(controlButton('end-final-reveal', 'End Final Jeopardy', 'danger-button'));
    }
    if (game.phase === 'TIEBREAKER_READY' && game.tiePending) {
        const action = game.round === 'TIEBREAKER' ? 'next-tiebreaker' : 'start-tiebreaker';
        actionButtons.push(controlButton(action, 'Prepare Tie-Breaker Clue', 'primary-button'));
    }

    controls.push(`<div class="control-stack">${actionButtons.join('')}</div>`);
    controls.push(renderManualScoring(teams));

    const inFinalActive = finalPhases.includes(game.phase);
    const finalJustEnded = (game.phase === 'GAME_OVER' || game.phase === 'TIEBREAKER_READY')
        && (game.round === 'FINAL' || game.round === 'TIEBREAKER');
    if (inFinalActive || finalJustEnded) {
        controls.push(renderFinalJeopardyControlsCard(game, teams));
    }

    return controls.join('');
}

function renderFinalJeopardyControlsCard(game, teams) {
    const isFinalReveal = game.phase === 'FINAL_REVEAL';
    const isGameOver = game.phase === 'GAME_OVER';
    const isTieReady = game.phase === 'TIEBREAKER_READY';
    const winner = isGameOver ? teams.find(t => t.id === game.winnerTeamId) : null;

    let header;
    if (winner) {
        header = `<div class="value">Game Over &mdash; ${escapeHtml(winner.name)} wins with ${winner.score} points</div>`;
    } else if (isGameOver) {
        header = `<div class="value">Game Over</div>`;
    } else if (isTieReady) {
        header = `<div class="value">Tie-Breaker Ready</div>`;
    } else {
        header = `<div class="value">${humanize(game.phase)}</div>`;
    }

    const statusLine = game.statusMessage
        ? `<p>${escapeHtml(game.statusMessage)}</p>`
        : '';

    let currentBlock = '';
    if (isFinalReveal && game.finalRevealTeamId) {
        const current = teams.find(t => t.id === game.finalRevealTeamId);
        if (current) {
            currentBlock = `
                <p><strong>Now revealing:</strong> ${escapeHtml(current.name)}</p>
                <p><strong>Wager:</strong> ${current.finalWager ?? 0}</p>
                <p><strong>Response:</strong> ${escapeHtml(current.finalResponse || 'No response submitted')}</p>
            `;
        }
    }

    const participatedTeams = teams.filter(t =>
        t.finalEligible || t.finalWagerSubmitted || t.finalResponseSubmitted || t.finalJudged);
    let teamsBlock = '';
    if (participatedTeams.length) {
        const cards = participatedTeams.map(team => {
            const isCurrent = isFinalReveal && team.id === game.finalRevealTeamId;
            const bits = [];
            if (team.finalWagerSubmitted || team.finalJudged) {
                bits.push(`Wager ${team.finalWager ?? 0}`);
            } else if (team.finalEligible) {
                bits.push('Wager pending');
            }
            if (team.finalResponseSubmitted || team.finalJudged) {
                bits.push(team.finalResponse ? 'Response submitted' : 'Response blank');
            } else if (team.finalEligible) {
                bits.push('Response pending');
            }
            if (team.finalJudged) {
                bits.push(team.finalResultCorrect ? 'Marked correct' : 'Marked incorrect');
            }
            const tag = isCurrent ? ' &mdash; current' : '';
            return `
                <div class="team-editor">
                    <div class="value">${escapeHtml(team.name)}${tag}</div>
                    <p>Score: ${team.score}</p>
                    <p>${bits.join(' &middot; ') || 'Awaiting input'}</p>
                </div>
            `;
        }).join('');
        teamsBlock = `<div class="team-grid">${cards}</div>`;
    }

    return `
        <article class="detail-card">
            <span class="label">Final Jeopardy</span>
            ${header}
            ${statusLine}
            ${currentBlock}
            ${teamsBlock}
        </article>
    `;
}

function renderManualScoring(teams) {
    return `
        <article class="detail-card">
            <span class="label">Manual Score Adjustments</span>
            <div class="team-grid">
                ${teams.map(team => `
                    <div class="team-editor">
                        <div class="value">${escapeHtml(team.name)}</div>
                        <div class="button-row">
                            <button class="button ghost-button" data-control-action="adjust-score" data-team-id="${escapeHtml(team.id)}" data-delta="-100" type="button">-100</button>
                            <button class="button ghost-button" data-control-action="adjust-score" data-team-id="${escapeHtml(team.id)}" data-delta="100" type="button">+100</button>
                            <button class="button ghost-button" data-control-action="adjust-score" data-team-id="${escapeHtml(team.id)}" data-delta="-200" type="button">-200</button>
                            <button class="button ghost-button" data-control-action="adjust-score" data-team-id="${escapeHtml(team.id)}" data-delta="200" type="button">+200</button>
                        </div>
                    </div>
                `).join('')}
            </div>
        </article>
    `;
}

function renderModeratorRoster(definition, game) {
    const maxPlayersPerTeam = Math.max(1, Number(definition?.config?.maxPlayersPerTeam || 1));
    return `
        <div class="team-grid">
            ${(game.teams || []).filter(team => team.active).map(team => {
                const players = team.players || [];
                return `
                    <article class="detail-card">
                        <div class="roster-meta">
                            <span class="pill">${escapeHtml(team.name)}</span>
                            <span class="pill">${players.length}/${maxPlayersPerTeam} players</span>
                        </div>
                        <ul class="roster-list">
                            ${players.length
                                ? players.map(player => `<li><span>${escapeHtml(player.displayName)}${player.connected ? '' : ' (idle)'}</span> <button class="button ghost-button roster-kick" type="button" data-control-action="kick-player" data-player-id="${escapeHtml(player.id)}" data-player-name="${escapeHtml(player.displayName)}">Remove</button></li>`).join('')
                                : '<li>Waiting for players</li>'}
                        </ul>
                    </article>
                `;
            }).join('')}
        </div>
    `;
}

function playerRosterSummary(definition, game) {
    const maxPlayersPerTeam = Math.max(1, Number(definition?.config?.maxPlayersPerTeam || 1));
    const activeTeams = (game.teams || []).filter(team => team.active);
    const joined = activeTeams.reduce((total, team) => total + (team.players || []).length, 0);
    return {
        joined,
        capacity: activeTeams.length * maxPlayersPerTeam,
    };
}

function renderModeratorBoard(game) {
    const board = game.currentBoard;
    if (!board || !board.categories) {
        return '<div class="detail-card"><span class="label">Board</span><div class="value">No standard board is active right now.</div></div>';
    }
    const clickable = game.phase === 'ROUND_BOARD';
    return `
        <div class="board-grid">
            ${board.categories.map(category => `
                <section class="board-category">
                    <div class="board-category-title">${escapeHtml(category.name)}</div>
                    ${(category.clues || []).map(clue => `
                        <button
                            class="clue-tile ${clue.used ? 'used' : ''}"
                            type="button"
                            data-clue-id="${escapeHtml(clue.id)}"
                            ${clue.used || !clickable ? 'disabled' : ''}>
                            ${clue.used ? 'USED' : clue.value}
                        </button>
                    `).join('')}
                </section>
            `).join('')}
        </div>
    `;
}

function renderModeratorFinalPanel(snapshot) {
    const finalEnabled = snapshot.definition?.config?.includeFinalJeopardy !== false;
    const game = snapshot.game;
    const final = game.finalJeopardy || {};
    const teams = game.teams || [];
    const eligibleTeams = teams.filter(team => team.finalEligible);
    const tieBreakers = snapshot.definition.tieBreakers || [];
    const finalPhaseActive = ['FINAL_CATEGORY', 'FINAL_WAGER', 'FINAL_CLUE_READY', 'FINAL_RESPONSE', 'FINAL_REVEAL'].includes(game.phase);
    const finalHistoryVisible = teams.some(team =>
        team.finalEligible || team.finalWagerSubmitted || team.finalResponseSubmitted || team.finalJudged);
    const showFinal = finalEnabled && (finalPhaseActive || finalHistoryVisible || Boolean(final.category || final.clue));
    const cards = [];

    if (showFinal) {
        cards.push(`
            <article class="clue-card">
                <div class="final-meta">
                    <span class="pill">Final Jeopardy</span>
                    ${final.category ? `<span class="pill">${escapeHtml(final.category)}</span>` : '<span class="pill">Category hidden</span>'}
                    <span class="pill">${eligibleTeams.length} eligible team${eligibleTeams.length === 1 ? '' : 's'}</span>
                </div>
                <div class="clue-prompt">${final.clue ? escapeHtml(final.clue) : 'The Final Jeopardy clue will appear here after the moderator reveals it.'}</div>
                ${ChemETheme.imageHtml(final.image, moderatorBasePath)}
                <div class="team-grid">
                    ${teams.map(team => `
                        <article class="detail-card">
                            <span class="label">${escapeHtml(team.name)}</span>
                            <div class="value">${team.finalEligible ? 'Eligible' : 'Not eligible'}</div>
                            <p>Wager submitted: ${team.finalWagerSubmitted ? 'Yes' : 'No'}</p>
                            <p>Response submitted: ${team.finalResponseSubmitted ? 'Yes' : 'No'}</p>
                            ${team.finalJudged ? `<p>Judged: ${team.finalResultCorrect ? 'Correct' : 'Incorrect'}</p>` : ''}
                        </article>
                    `).join('')}
                </div>
            </article>
        `);
    } else {
        cards.push(`
            <article class="detail-card">
                <span class="label">Final Jeopardy</span>
                <div class="value">${finalEnabled ? 'Waiting for the board to reach Final Jeopardy.' : 'Final Jeopardy is disabled for this setup.'}</div>
            </article>
        `);
    }

    cards.push(`
            <article class="detail-card">
                <span class="label">Tie-Breaker Queue</span>
                <div class="value">${tieBreakers.length} configured clue${tieBreakers.length === 1 ? '' : 's'}</div>
                <p>${tieBreakers.map((item, index) => `${index + 1}. ${item.category}`).join(' | ') || 'No tie-breakers configured.'}</p>
            </article>
    `);

    return `<div class="final-grid">${cards.join('')}</div>`;
}

function renderSetupEditors() {
    ensureDraft();
    const draft = moderatorState.draft;
    return `
        <div class="setup-grid">
            ${renderQuestionSetSetup()}

            <article class="detail-card">
                <span class="label">Game Settings</span>
                <div class="setup-subgrid">
                    ${settingInput('Game title', 'title', draft.title)}
                    ${configInput('Team count', 'teamCount', draft.config.teamCount, 'number', 2, 8)}
                    ${configInput('Max players per team', 'maxPlayersPerTeam', draft.config.maxPlayersPerTeam, 'number', 1, 8)}
                    ${configInput('Categories per round', 'categoryCount', draft.config.categoryCount, 'number', 1, 10)}
                    ${configInput('Clues per category', 'cluesPerCategory', draft.config.cluesPerCategory, 'number', 1, 10)}
                    ${configInput('Single Jeopardy base value', 'singleBaseValue', draft.config.singleBaseValue, 'number', 100, 1000)}
                    ${configInput('Double Jeopardy base value', 'doubleBaseValue', draft.config.doubleBaseValue, 'number', 200, 2000)}
                    ${configInput('Buzz window seconds', 'buzzWindowSeconds', draft.config.buzzWindowSeconds, 'number', 1, 30)}
                    ${configInput('Response window seconds', 'responseWindowSeconds', draft.config.responseWindowSeconds, 'number', 1, 30)}
                    ${configInput('Daily Double response seconds', 'dailyDoubleResponseSeconds', draft.config.dailyDoubleResponseSeconds, 'number', 1, 60)}
                    ${configInput('Final wager seconds', 'finalWagerSeconds', draft.config.finalWagerSeconds, 'number', 1, 120)}
                    ${configInput('Final response seconds', 'finalResponseSeconds', draft.config.finalResponseSeconds, 'number', 1, 120)}
                </div>
                <div class="button-row">
                    ${configCheckbox('Include Double Jeopardy', 'includeDoubleJeopardy', draft.config.includeDoubleJeopardy)}
                    ${configCheckbox('Include Final Jeopardy', 'includeFinalJeopardy', draft.config.includeFinalJeopardy)}
                </div>
            </article>

            <article class="detail-card">
                <span class="label">Teams</span>
                <div class="team-grid">
                    ${draft.teams.map((team, index) => `
                        <div class="team-editor">
                            ${teamInput(index, 'name', 'Team name', team.name)}
                            ${teamInput(index, 'color', 'Team color', team.color, 'color')}
                            ${teamCheckbox(index, 'active', 'Active team', team.active)}
                        </div>
                    `).join('')}
                </div>
            </article>
        </div>
    `;
}

function renderQuestionSetSetup() {
    const snapshot = moderatorState.snapshot;
    const definition = snapshot.definition || {};
    const source = snapshot.questionSource || {};
    const singleCount = (definition.singleBoard?.categories || []).reduce((total, category) => total + (category.clues || []).length, 0);
    const doubleCount = (definition.doubleBoard?.categories || []).reduce((total, category) => total + (category.clues || []).length, 0);
    const singleNames = (definition.singleBoard?.categories || []).map(category => category.name).join(', ');
    const doubleNames = (definition.doubleBoard?.categories || []).map(category => category.name).join(', ');
    const defaultPath = snapshot.definitionPath || 'data/game-definition.json';
    return `
        <article class="detail-card">
            <span class="label">Question Set</span>
            <div class="question-set-grid">
                <div>
                    <div class="value">${escapeHtml(source.label || defaultPath)}</div>
                    <div class="game-card-meta">
                        <span class="pill">${singleCount} Single clues</span>
                        <span class="pill">${doubleCount} Double clues</span>
                        <span class="pill">${definition.finalJeopardy?.category ? 'Final ready' : 'No Final'}</span>
                    </div>
                    <p class="minor-note">Single: ${escapeHtml(singleNames || 'No categories loaded')}</p>
                    <p class="minor-note">Double: ${escapeHtml(doubleNames || 'No categories loaded')}</p>
                </div>
                <div class="file-action">
                    <label class="input-group">
                        <span>Server file path</span>
                        <input type="text" id="question-file-path" value="${escapeHtml(defaultPath)}">
                    </label>
                    <button class="button ghost-button" type="button" data-question-action="load-file">Load Server File</button>
                </div>
                <div class="file-action">
                    <label class="input-group">
                        <span>Upload JSON or image package</span>
                        <input type="file" id="question-upload-input" accept=".json,.zip,.chemej,application/json,application/zip">
                    </label>
                    <div class="minor-note">${source.hasRuntimeUpload ? `Current upload ${escapeHtml(source.uploadedFileName)} will be replaced.` : 'Only one runtime upload is kept per game.'}</div>
                    <button class="button ghost-button" type="button" data-question-action="upload-file">Upload Question Set</button>
                </div>
            </div>
        </article>
    `;
}

function boardEditor(boardKey, board) {
    return `
        <div class="setup-board">
            ${board.categories.map((category, categoryIndex) => `
                <section class="category-editor">
                    ${boardInput(boardKey, categoryIndex, null, 'name', 'Category name', category.name)}
                    <div class="clue-editor-grid">
                        ${category.clues.map((clue, clueIndex) => `
                            <article class="clue-editor-card">
                                <div class="clue-editor-meta">
                                    <strong>${clue.value} points</strong>
                                    <label class="inline-checkbox">
                                        <input type="checkbox"
                                               data-board="${boardKey}"
                                               data-category-index="${categoryIndex}"
                                               data-clue-index="${clueIndex}"
                                               data-clue-key="dailyDouble"
                                               ${clue.dailyDouble ? 'checked' : ''}>
                                        Daily Double
                                    </label>
                                </div>
                                ${boardInput(boardKey, categoryIndex, clueIndex, 'prompt', 'Prompt', clue.prompt, true)}
                                ${boardInput(boardKey, categoryIndex, clueIndex, 'response', 'Correct response', clue.response, true)}
                            </article>
                        `).join('')}
                    </div>
                </section>
            `).join('')}
        </div>
    `;
}

function settingInput(label, key, value) {
    return `
        <label class="input-group">
            <span>${label}</span>
            <input type="text" data-root-key="${key}" value="${escapeHtml(value)}">
        </label>
    `;
}

function configInput(label, key, value, type, min, max) {
    return `
        <label class="input-group">
            <span>${label}</span>
            <input type="${type}" data-config-key="${key}" value="${value}" min="${min}" max="${max}">
        </label>
    `;
}

function configCheckbox(label, key, checked) {
    return `
        <label class="inline-checkbox">
            <input type="checkbox" data-config-key="${key}" ${checked ? 'checked' : ''}>
            ${label}
        </label>
    `;
}

function teamInput(index, key, label, value, type = 'text') {
    return `
        <label class="input-group">
            <span>${label}</span>
            <input type="${type}" data-team-index="${index}" data-team-key="${key}" value="${escapeHtml(value)}">
        </label>
    `;
}

function teamCheckbox(index, key, label, checked) {
    return `
        <label class="inline-checkbox">
            <input type="checkbox" data-team-index="${index}" data-team-key="${key}" ${checked ? 'checked' : ''}>
            ${label}
        </label>
    `;
}

function boardInput(boardKey, categoryIndex, clueIndex, key, label, value, multiline = false) {
    const attrs = `data-board="${boardKey}" data-category-index="${categoryIndex}" ${clueIndex === null ? `data-category-key="${key}"` : `data-clue-index="${clueIndex}" data-clue-key="${key}"`}`;
    if (multiline) {
        return `
            <label class="input-group">
                <span>${label}</span>
                <textarea ${attrs}>${escapeHtml(value)}</textarea>
            </label>
        `;
    }
    return `
        <label class="input-group">
            <span>${label}</span>
            <input type="text" ${attrs} value="${escapeHtml(value)}">
        </label>
    `;
}

function finalInput(label, key, value) {
    return `
        <label class="input-group">
            <span>${label}</span>
            <input type="text" data-final-key="${key}" value="${escapeHtml(value)}">
        </label>
    `;
}

function finalTextarea(label, key, value) {
    return `
        <label class="input-group">
            <span>${label}</span>
            <textarea data-final-key="${key}">${escapeHtml(value)}</textarea>
        </label>
    `;
}

function tieInput(index, key, label, value) {
    return `
        <label class="input-group">
            <span>${label}</span>
            <input type="text" data-tie-index="${index}" data-tie-key="${key}" value="${escapeHtml(value)}">
        </label>
    `;
}

function tieTextarea(index, key, label, value) {
    return `
        <label class="input-group">
            <span>${label}</span>
            <textarea data-tie-index="${index}" data-tie-key="${key}">${escapeHtml(value)}</textarea>
        </label>
    `;
}

async function handleSetupClick(event) {
    const button = event.target.closest('[data-question-action]');
    if (!button) {
        return;
    }
    const setupLocked = moderatorState.snapshot?.game?.phase !== 'SETUP';
    if (setupLocked) {
        showToast('Question sets can only be changed before the game starts.');
        return;
    }
    if (actionRequiresCleanDraft() && !confirm('You have unsaved setup changes. Continue and replace the current draft?')) {
        return;
    }
    if (button.dataset.questionAction === 'load-file') {
        await loadServerQuestionSet();
        return;
    }
    if (button.dataset.questionAction === 'upload-file') {
        await uploadQuestionSet();
    }
}

function actionRequiresCleanDraft() {
    return moderatorState.dirty === true;
}

async function loadServerQuestionSet() {
    const input = document.getElementById('question-file-path');
    const path = input ? input.value.trim() : '';
    const response = await postForm('/api/mod/load-file', { path });
    if (response.ok) {
        moderatorState.dirty = false;
    }
    showToast(response.message);
}

async function uploadQuestionSet() {
    const input = document.getElementById('question-upload-input');
    const file = input?.files?.[0];
    if (!file) {
        showToast('Choose a JSON file or image package first.');
        return;
    }
    const source = moderatorState.snapshot?.questionSource || {};
    if (source.hasRuntimeUpload && !confirm('Uploading a new question set replaces the current runtime upload for this game. Continue?')) {
        return;
    }
    const upload = await uploadPayload(file);
    const response = await fetch(apiUrl('/api/mod/upload-definition'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(upload),
    });
    const payload = await readJsonResponse(response);
    if (payload.ok) {
        moderatorState.dirty = false;
        input.value = '';
    }
    showToast(payload.message);
}

async function uploadPayload(file) {
    const payload = { fileName: file.name };
    if (isPackageFile(file)) {
        payload.contentBase64 = await fileToBase64(file);
    } else {
        payload.content = await file.text();
    }
    return payload;
}

function isPackageFile(file) {
    const name = file.name.toLowerCase();
    return name.endsWith('.zip') || name.endsWith('.chemej');
}

async function fileToBase64(file) {
    const buffer = await file.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (let index = 0; index < bytes.length; index += 8192) {
        binary += String.fromCharCode(...bytes.subarray(index, index + 8192));
    }
    return btoa(binary);
}

function handleDraftInput(event) {
    ensureDraft();
    const target = event.target;
    if (target.id === 'question-file-path' || target.id === 'question-upload-input') {
        return;
    }
    const isCheckbox = target.type === 'checkbox';
    const value = isCheckbox ? target.checked : target.value;
    let handled = true;

    if (target.dataset.rootKey) {
        moderatorState.draft[target.dataset.rootKey] = value;
    } else if (target.dataset.configKey) {
        moderatorState.draft.config[target.dataset.configKey] = isCheckbox ? target.checked : Number(target.value);
        if (['teamCount', 'categoryCount', 'cluesPerCategory'].includes(target.dataset.configKey)) {
            normalizeDefinitionDraft(moderatorState.draft);
            renderModerator();
            moderatorState.dirty = true;
            return;
        }
    } else if (target.dataset.teamIndex !== undefined) {
        const team = moderatorState.draft.teams[Number(target.dataset.teamIndex)];
        team[target.dataset.teamKey] = value;
    } else if (target.dataset.board) {
        const board = moderatorState.draft[target.dataset.board];
        const category = board.categories[Number(target.dataset.categoryIndex)];
        if (target.dataset.categoryKey) {
            category[target.dataset.categoryKey] = value;
        } else {
            const clue = category.clues[Number(target.dataset.clueIndex)];
            clue[target.dataset.clueKey] = value;
        }
    } else if (target.dataset.finalKey) {
        moderatorState.draft.finalJeopardy[target.dataset.finalKey] = value;
    } else if (target.dataset.tieIndex !== undefined) {
        moderatorState.draft.tieBreakers[Number(target.dataset.tieIndex)][target.dataset.tieKey] = value;
    } else {
        handled = false;
    }

    if (handled) {
        moderatorState.dirty = true;
    }
}

async function saveDefinition() {
    ensureDraft();
    normalizeDefinitionDraft(moderatorState.draft);
    const response = await fetch(apiUrl('/api/mod/definition'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(moderatorState.draft),
    });
    const payload = await readJsonResponse(response);
    if (payload.ok) {
        moderatorState.dirty = false;
    }
    showToast(payload.message);
}

async function handleControlClick(event) {
    const button = event.target.closest('[data-control-action]');
    if (!button) {
        return;
    }
    const action = button.dataset.controlAction;
    if (action === 'remove-tiebreaker') {
        ensureDraft();
        moderatorState.draft.tieBreakers.splice(Number(button.dataset.index), 1);
        if (!moderatorState.draft.tieBreakers.length) {
            moderatorState.draft.tieBreakers.push({
                category: 'Chemical Engineering',
                clue: 'Enter the next tie-breaker clue here.',
                response: 'Enter the corresponding tie-breaker response here.',
            });
        }
        moderatorState.dirty = true;
        renderModerator();
        return;
    }

    if (action === 'save-daily-wager') {
        const teamId = moderatorState.snapshot.game.activeClueSelectingTeamId;
        const wager = document.getElementById('moderator-daily-wager-input').value;
        const response = await postForm('/api/mod/daily-wager', { teamId, wager });
        showToast(response.message);
        return;
    }

    if (action === 'adjust-score') {
        const response = await postForm('/api/mod/adjust-score', {
            teamId: button.dataset.teamId,
            delta: button.dataset.delta,
        });
        showToast(response.message);
        return;
    }

    if (action === 'kick-player') {
        const playerId = button.dataset.playerId;
        const playerName = button.dataset.playerName || 'this player';
        if (!confirm(`Remove ${playerName} from their team?\n\nThey can rejoin from the player page and pick any open team.`)) {
            return;
        }
        const response = await postForm('/api/mod/kick-player', { playerId });
        showToast(response.message);
        return;
    }

    const route = {
        'finish-reading': '/api/mod/finish-reading',
        'judge-correct': '/api/mod/judge-correct',
        'judge-incorrect': '/api/mod/judge-incorrect',
        'continue': '/api/mod/continue',
        'reveal-response': '/api/mod/reveal-response',
        'start-daily-double': '/api/mod/start-daily-double',
        'start-final-wager': '/api/mod/start-final-wager',
        'lock-final-wagers': '/api/mod/lock-final-wagers',
        'reveal-final-clue': '/api/mod/reveal-final-clue',
        'lock-final-responses': '/api/mod/lock-final-responses',
        'skip-final-team': '/api/mod/skip-final-team',
        'end-final-reveal': '/api/mod/end-final-reveal',
        'start-tiebreaker': '/api/mod/start-tiebreaker',
        'next-tiebreaker': '/api/mod/next-tiebreaker',
    }[action];

    if (route) {
        const response = await post(route);
        showToast(response.message);
    }
}

async function handleBoardClick(event) {
    const button = event.target.closest('[data-clue-id]');
    if (!button || button.disabled) {
        return;
    }
    const response = await postForm('/api/mod/select-clue', { clueId: button.dataset.clueId });
    showToast(response.message);
}

function normalizeDefinitionDraft(draft) {
    draft.title ||= 'ChemE Jeopardy';
    draft.config ||= {};
    draft.config.teamCount = clamp(Number(draft.config.teamCount || 3), 2, 8);
    draft.config.maxPlayersPerTeam = clamp(Number(draft.config.maxPlayersPerTeam || 4), 1, 8);
    draft.config.categoryCount = clamp(Number(draft.config.categoryCount || 6), 1, 10);
    draft.config.cluesPerCategory = clamp(Number(draft.config.cluesPerCategory || 5), 1, 10);
    draft.config.singleBaseValue = Math.max(100, Number(draft.config.singleBaseValue || 100));
    draft.config.doubleBaseValue = Math.max(200, Number(draft.config.doubleBaseValue || 200));
    draft.config.buzzWindowSeconds = clamp(Number(draft.config.buzzWindowSeconds || 5), 1, 30);
    draft.config.responseWindowSeconds = clamp(Number(draft.config.responseWindowSeconds || 5), 1, 30);
    draft.config.dailyDoubleResponseSeconds = clamp(Number(draft.config.dailyDoubleResponseSeconds || 10), 1, 60);
    draft.config.finalWagerSeconds = clamp(Number(draft.config.finalWagerSeconds || 30), 1, 120);
    draft.config.finalResponseSeconds = clamp(Number(draft.config.finalResponseSeconds || 30), 1, 120);
    draft.config.includeDoubleJeopardy = draft.config.includeDoubleJeopardy !== false;
    draft.config.includeFinalJeopardy = draft.config.includeFinalJeopardy !== false;
    draft.config.displayThemeColor ||= 'blue';
    draft.config.displayThemeMode ||= 'dark';

    draft.teams ||= [];
    while (draft.teams.length < draft.config.teamCount) {
        const index = draft.teams.length + 1;
        draft.teams.push({
            id: `team-${index}`,
            name: `Team ${index}`,
            color: teamColors[(index - 1) % teamColors.length],
            active: index <= 3,
        });
    }
    draft.teams = draft.teams.slice(0, draft.config.teamCount).map((team, index) => ({
        id: team.id || `team-${index + 1}`,
        name: team.name || `Team ${index + 1}`,
        color: team.color || teamColors[index % teamColors.length],
        active: team.active !== false,
    }));

    normalizeBoardDraft(draft.singleBoard ||= { key: 'single', title: 'Jeopardy', categories: [] }, 'single', draft.config.categoryCount, draft.config.cluesPerCategory, draft.config.singleBaseValue, 1);
    normalizeBoardDraft(draft.doubleBoard ||= { key: 'double', title: 'Double Jeopardy', categories: [] }, 'double', draft.config.categoryCount, draft.config.cluesPerCategory, draft.config.doubleBaseValue, 2);

    draft.finalJeopardy ||= {};
    draft.finalJeopardy.category ||= 'Chemical Engineering';
    draft.finalJeopardy.clue ||= 'Enter the Final Jeopardy clue here.';
    draft.finalJeopardy.response ||= 'Enter the Final Jeopardy response here.';

    draft.tieBreakers ||= [];
    if (!draft.tieBreakers.length) {
        draft.tieBreakers.push({
            category: 'Chemical Engineering',
            clue: 'Enter the first tie-breaker clue here.',
            response: 'Enter the first tie-breaker response here.',
        });
    }
}

function normalizeBoardDraft(board, key, categoryCount, cluesPerCategory, baseValue, defaultDailyDoubles) {
    board.key = key;
    board.title = key === 'single' ? 'Jeopardy' : 'Double Jeopardy';
    board.categories ||= [];
    while (board.categories.length < categoryCount) {
        board.categories.push({ id: '', name: '', clues: [] });
    }
    board.categories = board.categories.slice(0, categoryCount);

    let selectedDailyDoubles = 0;
    board.categories.forEach((category, categoryIndex) => {
        category.id = `${key}-category-${categoryIndex + 1}`;
        category.name ||= `${key === 'single' ? 'Single' : 'Double'} Category ${categoryIndex + 1}`;
        category.clues ||= [];
        while (category.clues.length < cluesPerCategory) {
            category.clues.push({});
        }
        category.clues = category.clues.slice(0, cluesPerCategory);
        category.clues.forEach((clue, clueIndex) => {
            clue.id = `${key}-c${categoryIndex + 1}-q${clueIndex + 1}`;
            clue.categoryName = category.name;
            clue.value = baseValue * (clueIndex + 1);
            clue.prompt ||= `Enter the clue prompt for ${category.name} ${clue.value}.`;
            clue.response ||= `Enter the correct response for ${category.name} ${clue.value}.`;
            clue.used = false;
            clue.dailyDouble = Boolean(clue.dailyDouble);
            if (clue.dailyDouble) {
                selectedDailyDoubles += 1;
            }
        });
    });

    if (selectedDailyDoubles === 0 && defaultDailyDoubles > 0) {
        const allClues = board.categories.flatMap(category => category.clues);
        for (let index = 0; index < Math.min(defaultDailyDoubles, allClues.length); index += 1) {
            allClues[Math.min(index * 4 + 2, allClues.length - 1)].dailyDouble = true;
        }
    }
}

function ensureDraft() {
    if (!moderatorState.draft && moderatorState.snapshot) {
        moderatorState.draft = structuredClone(moderatorState.snapshot.definition);
        normalizeDefinitionDraft(moderatorState.draft);
    }
}

function winnerLabel(game) {
    if (!game.winnerTeamId) {
        return game.tiePending ? 'Tie pending' : 'TBD';
    }
    const winner = (game.teams || []).find(team => team.id === game.winnerTeamId);
    return winner ? winner.name : 'Winner decided';
}

function controlButton(action, label, className) {
    return `<button class="button ${className}" type="button" data-control-action="${action}">${label}</button>`;
}

function statusCard(label, value) {
    return `<article class="status-card"><span class="label">${escapeHtml(label)}</span><div class="value">${escapeHtml(String(value))}</div></article>`;
}

async function post(url, requireAuth = true) {
    const response = await fetch(apiUrl(url), { method: 'POST' });
    return readJsonResponse(response, requireAuth);
}

async function postForm(url, payload, requireAuth = true) {
    const body = new URLSearchParams();
    Object.entries(payload).forEach(([key, value]) => body.set(key, value ?? ''));
    const response = await fetch(apiUrl(url), {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    return readJsonResponse(response, requireAuth);
}

async function readJsonResponse(response, requireAuth = true) {
    let payload = { ok: false, message: 'Unexpected server response.' };
    try {
        payload = await response.json();
    } catch {
        // Keep the fallback payload.
    }
    if (response.status === 401 && requireAuth) {
        setModeratorAuthenticated(false);
        payload.message ||= 'Moderator login required.';
    }
    return payload;
}

function secondsLeft(endsAt) {
    return Math.max(0, Math.ceil((endsAt - Date.now()) / 1000));
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function humanize(value) {
    return String(value || '')
        .toLowerCase()
        .split('_')
        .map(piece => piece.charAt(0).toUpperCase() + piece.slice(1))
        .join(' ');
}

function showToast(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.classList.add('visible');
    clearTimeout(moderatorState.toastTimer);
    moderatorState.toastTimer = setTimeout(() => toast.classList.remove('visible'), 2800);
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

function apiUrl(path) {
    return `${moderatorBasePath}${path}`;
}

function moderatorThemeStorageKey() {
    return `cheme-moderator-theme:${moderatorBasePath || 'default'}`;
}

function gameBasePath() {
    const firstSegment = window.location.pathname.split('/').filter(Boolean)[0] || '';
    return ['moderator', 'player', 'display', 'games'].includes(firstSegment) ? '' : `/${firstSegment}`;
}
