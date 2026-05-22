/*
 * File: player.js
 * Description: Player UI state manager for joining, synchronizing, buzzing, and player submissions.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */

const playerBasePath = gameBasePath();

const playerState = {
    snapshot: null,
    session: loadSession(),
    syncInfo: null,
    toastTimer: null,
    selectedTeamId: '',
};

document.addEventListener('DOMContentLoaded', async () => {
    bindPlayerTheme();
    bindPlayerEvents();
    await refreshPlayerState();
    connectPlayerEvents();
    if (playerState.session) {
        await synchronizeClock();
        setInterval(synchronizeClock, 15000);
    }
});

function bindPlayerEvents() {
    document.getElementById('join-form').addEventListener('submit', handleJoinSubmit);
    document.getElementById('join-team-grid').addEventListener('click', event => {
        const button = event.target.closest('[data-team-id]');
        if (!button) {
            return;
        }
        playerState.selectedTeamId = button.dataset.teamId;
        document.getElementById('team-select').value = playerState.selectedTeamId;
        renderPlayer();
    });
    document.getElementById('resync-button').addEventListener('click', synchronizeClock);
    document.getElementById('clear-session-button').addEventListener('click', () => {
        playerState.session = null;
        localStorage.removeItem(playerSessionStorageKey());
        playerState.syncInfo = null;
        renderPlayer();
        showToast('Local player session cleared.');
    });
}

function bindPlayerTheme() {
    ChemETheme.bindLocalControls({
        colorId: 'player-theme-color',
        modeId: 'player-theme-mode',
        storageKey: playerThemeStorageKey(),
        defaults: { color: 'blue', mode: 'system' },
    });
}

async function refreshPlayerState() {
    const response = await fetch(apiUrl('/api/state'));
    playerState.snapshot = await response.json();
    renderPlayer();
}

function connectPlayerEvents() {
    const source = new EventSource(apiUrl('/api/events'));
    source.addEventListener('state', event => {
        playerState.snapshot = JSON.parse(event.data);
        renderPlayer();
    });
    source.onerror = () => {
        setTimeout(() => {
            source.close();
            connectPlayerEvents();
        }, 1500);
    };
}

async function handleJoinSubmit(event) {
    event.preventDefault();
    const displayName = document.getElementById('display-name').value.trim();
    const teamId = document.getElementById('team-select').value;
    const joinPassword = document.getElementById('join-password').value;
    const response = await postForm('/api/player/join', { displayName, teamId, joinPassword });
    if (!response.ok) {
        showToast(response.message || 'Unable to join the team.');
        return;
    }
    playerState.session = {
        playerId: response.playerId,
        sessionKey: response.sessionKey,
        teamId: response.teamId,
        displayName: response.displayName,
    };
    saveSession(playerState.session);
    document.getElementById('join-password').value = '';
    showToast(response.message);
    await synchronizeClock();
    await refreshPlayerState();
}

async function synchronizeClock() {
    if (!playerState.session) {
        showToast('Join a team before synchronizing.');
        return;
    }
    let best = null;
    for (let attempt = 0; attempt < 6; attempt += 1) {
        const t0 = Date.now();
        const response = await fetch(apiUrl('/api/time-sync'));
        const payload = await response.json();
        const t1 = Date.now();
        const latency = t1 - t0;
        const offset = payload.serverTime - ((t0 + t1) / 2);
        if (!best || latency < best.latency) {
            best = { latency, offset, clientSentAt: t0, clientReceivedAt: t1 };
        }
    }
    playerState.syncInfo = best;
    await postForm('/api/player/sync', {
        playerId: playerState.session.playerId,
        sessionKey: playerState.session.sessionKey,
        clientSentAt: best.clientSentAt,
        clientReceivedAt: best.clientReceivedAt,
    });
    renderPlayer();
}

function renderPlayer() {
    const snapshot = playerState.snapshot;
    if (!snapshot) {
        return;
    }
    const game = snapshot.game;
    reconcileSession(game);
    const myTeam = getMyTeam();
    const myPlayer = getMyPlayer();
    const activeClue = game.activeClue;
    const countdown = game.countdown;
    const hasJoined = Boolean(myPlayer);

    document.getElementById('player-identity-chip').textContent = myPlayer
        ? `${myPlayer.displayName} • ${myTeam?.name || 'Team'}`
        : 'Not joined yet';
    document.body.classList.toggle('player-joined', hasJoined);
    document.getElementById('join-panel').classList.toggle('hidden', hasJoined);
    document.getElementById('player-app-shell').classList.toggle('hidden', !hasJoined);

    document.getElementById('player-alert').textContent = game.statusMessage || 'Waiting for the moderator.';
    const activeTeams = (game.teams || []).filter(team => team.active);
    const teamSelect = document.getElementById('team-select');
    teamSelect.innerHTML = activeTeams
        .map(team => `<option value="${escapeHtml(team.id)}">${escapeHtml(team.name)}</option>`)
        .join('');
    if (!activeTeams.some(team => team.id === playerState.selectedTeamId)) {
        playerState.selectedTeamId = activeTeams[0]?.id || '';
    }
    teamSelect.value = playerState.selectedTeamId;
    document.getElementById('join-team-grid').innerHTML = renderJoinTeamGrid(snapshot.definition, activeTeams, playerState.selectedTeamId);
    const playerPasswordReady = snapshot.security?.playerJoinPasswordSet === true;
    document.getElementById('join-submit-button').disabled = !playerPasswordReady;
    document.getElementById('player-join-note').textContent = playerPasswordReady
        ? 'Enter the current player password from the moderator.'
        : 'Waiting for the moderator to set the player password.';

    document.getElementById('player-status-grid').innerHTML = [
        statusCard('Phase', humanize(game.phase)),
        statusCard('Round', humanize(game.round)),
        statusCard('Choosing Team', game.chooserTeamName || 'Moderator'),
        statusCard('Clock Sync', renderSyncSummary()),
        statusCard('Countdown', countdown ? `${countdown.label} • ${secondsLeft(countdown.endsAt)}s` : 'Idle'),
        statusCard('Recognized Player', game.recognizedPlayerName ? `${game.recognizedPlayerName} • ${game.recognizedTeamName}` : 'None'),
    ].join('');

    document.getElementById('player-scoreboard').innerHTML = (game.teams || []).map(team => {
        const roster = (team.players || []).map(player => `<li>${escapeHtml(player.displayName)}</li>`).join('') || '<li>No connected players</li>';
        const extras = team.finalJudged
            ? `<div class="detail-card"><span class="label">Final Jeopardy</span><div class="value">${team.finalResultCorrect ? 'Correct' : 'Incorrect'} • Wager ${team.finalWager ?? 0}</div></div>`
            : '';
        return `
            <article class="score-card" style="--team-accent:${escapeHtml(team.color)}">
                <span class="label">Team</span>
                <div class="value">${escapeHtml(team.name)}</div>
                <div class="score">${team.score}</div>
                <div class="label">Connected Players</div>
                <ul>${roster}</ul>
                ${extras}
            </article>
        `;
    }).join('');

    if (activeClue) {
        const cluePrompt = activeClue.prompt
            ? escapeHtml(activeClue.prompt)
            : activeClue.dailyDouble && game.phase === 'DAILY_DOUBLE_WAGER'
                ? 'Daily Double selected. The moderator is collecting the wager before revealing the clue.'
                : 'The clue will appear here when the moderator reveals it.';
        document.getElementById('active-clue-panel').innerHTML = `
            <article class="clue-card">
                <div class="clue-metadata">
                    <span class="pill">${escapeHtml(activeClue.categoryName)}</span>
                    <span class="pill">${activeClue.value} points</span>
                    ${activeClue.dailyDouble ? '<span class="pill">Daily Double</span>' : ''}
                </div>
                <div class="clue-prompt">${cluePrompt}</div>
                ${ChemETheme.imageHtml(activeClue.image, playerBasePath)}
                ${game.answerVisible && activeClue.response ? `<div class="clue-response"><strong>Correct response:</strong> ${escapeHtml(activeClue.response)}</div>` : ''}
            </article>
        `;
    } else if (game.lastRevealResponse && game.phase !== 'ROUND_BOARD') {
        document.getElementById('active-clue-panel').innerHTML = `
            <article class="clue-card">
                <div class="clue-metadata">
                    <span class="pill">${escapeHtml(game.lastRevealTitle || 'Previous clue')}</span>
                </div>
                <div class="clue-response"><strong>Correct response:</strong> ${escapeHtml(game.lastRevealResponse)}</div>
            </article>
        `;
    } else {
        document.getElementById('active-clue-panel').innerHTML = '';
    }

    document.getElementById('player-final-panel').innerHTML = renderPlayerFinalPanel(snapshot.definition, game, myTeam);
    document.getElementById('player-controls-panel').innerHTML = renderPlayerControls(game, myTeam, myPlayer);
    wireDynamicPlayerButtons();
    document.getElementById('player-board').innerHTML = renderBoard(game.currentBoard);
}

function renderJoinTeamGrid(definition, teams, selectedTeamId) {
    const maxPlayersPerTeam = Math.max(1, Number(definition?.config?.maxPlayersPerTeam || 1));
    return teams.map(team => {
        const players = team.players || [];
        const roster = players.length
            ? players.map(player => `<li>${escapeHtml(player.displayName)}${player.connected ? '' : ' (idle)'}</li>`).join('')
            : '<li>Waiting for players</li>';
        return `
            <button class="join-team-card ${team.id === selectedTeamId ? 'selected' : ''}"
                    type="button"
                    data-team-id="${escapeHtml(team.id)}"
                    style="--team-accent:${escapeHtml(team.color)}">
                <span class="join-team-name">${escapeHtml(team.name)}</span>
                <span class="join-team-count">${players.length}/${maxPlayersPerTeam}</span>
                <ul>${roster}</ul>
            </button>
        `;
    }).join('');
}

function renderPlayerControls(game, myTeam, myPlayer) {
    if (!myPlayer || !myTeam) {
        return '';
    }

    const controls = [];
    const buzzReady = canBuzz(game, myTeam);
    const buzzLockedReason = buzzStatusLabel(game, myTeam);
    const early = game.phase === 'CLUE_READING';
    controls.push(`
        <section class="player-buzz-card action-area">
            <span class="label">Buzz In</span>
            <button class="button primary-button buzz-button ${early && buzzReady ? 'early' : ''}"
                    id="buzz-button"
                    type="button"
                    ${buzzReady ? '' : 'disabled'}>
                ${buzzReady ? (early ? 'Buzz Early' : 'Buzz Now') : buzzLockedReason}
            </button>
        </section>
    `);

    if (game.phase === 'DAILY_DOUBLE_WAGER' && game.activeClueSelectingTeamId === myTeam.id) {
        const floor = game.round === 'DOUBLE' ? 1000 : 500;
        const max = Math.max(myTeam.score, floor);
        controls.push(`
            <section class="detail-card action-area">
                <span class="label">Daily Double Wager</span>
                <div class="value">Enter a wager from 0 to ${max}.</div>
                <input type="number" id="daily-wager-input" min="0" max="${max}" value="${Math.min(max, game.dailyDoubleWager || 0)}">
                <button class="button primary-button" id="daily-wager-button" type="button">Submit Wager</button>
            </section>
        `);
    }

    if (game.phase === 'FINAL_WAGER' && myTeam.finalEligible) {
        controls.push(`
            <section class="detail-card action-area">
                <span class="label">Final Jeopardy Wager</span>
                <div class="value">Enter a wager from 0 to ${myTeam.score}.</div>
                <input type="number" id="final-wager-input" min="0" max="${myTeam.score}" value="${myTeam.finalWager ?? 0}">
                <button class="button primary-button" id="final-wager-button" type="button">Submit Final Wager</button>
            </section>
        `);
    }

    if (game.phase === 'FINAL_RESPONSE' && myTeam.finalEligible) {
        controls.push(`
            <section class="detail-card action-area">
                <span class="label">Final Jeopardy Response</span>
                <textarea id="final-response-input" placeholder="Type your response in the form of a question.">${escapeHtml(myTeam.finalResponse || '')}</textarea>
                <button class="button primary-button" id="final-response-button" type="button">Submit Final Response</button>
            </section>
        `);
    }

    return `<div class="control-stack">${controls.join('')}</div>`;
}

function wireDynamicPlayerButtons() {
    const buzzButton = document.getElementById('buzz-button');
    if (buzzButton && !buzzButton.disabled) {
        buzzButton.addEventListener('click', handleBuzzClick);
    }
    const dailyButton = document.getElementById('daily-wager-button');
    if (dailyButton) {
        dailyButton.addEventListener('click', handleDailyWagerSubmit);
    }
    const finalWagerButton = document.getElementById('final-wager-button');
    if (finalWagerButton) {
        finalWagerButton.addEventListener('click', handleFinalWagerSubmit);
    }
    const finalResponseButton = document.getElementById('final-response-button');
    if (finalResponseButton) {
        finalResponseButton.addEventListener('click', handleFinalResponseSubmit);
    }
}

async function handleBuzzClick() {
    if (!playerState.session || !playerState.syncInfo) {
        showToast('Please synchronize your clock before buzzing.');
        return;
    }
    const syncedTimestamp = Date.now() + playerState.syncInfo.offset;
    const response = await postForm('/api/player/buzz', {
        playerId: playerState.session.playerId,
        sessionKey: playerState.session.sessionKey,
        syncedTimestamp,
    });
    showToast(response.message || 'Buzz sent.');
}

async function handleDailyWagerSubmit() {
    const myTeam = getMyTeam();
    const wager = document.getElementById('daily-wager-input').value;
    const response = await postForm('/api/player/daily-wager', {
        playerId: playerState.session.playerId,
        sessionKey: playerState.session.sessionKey,
        teamId: myTeam.id,
        wager,
    });
    showToast(response.message);
}

async function handleFinalWagerSubmit() {
    const wager = document.getElementById('final-wager-input').value;
    const response = await postForm('/api/player/final-wager', {
        playerId: playerState.session.playerId,
        sessionKey: playerState.session.sessionKey,
        wager,
    });
    showToast(response.message);
}

async function handleFinalResponseSubmit() {
    const responseText = document.getElementById('final-response-input').value;
    const response = await postForm('/api/player/final-response', {
        playerId: playerState.session.playerId,
        sessionKey: playerState.session.sessionKey,
        response: responseText,
    });
    showToast(response.message);
}

function renderBoard(board) {
    if (!board || !board.categories) {
        return '<div class="detail-card"><span class="label">Board</span><div class="value">No board is active right now.</div></div>';
    }
    return `
        <div class="board-grid">
            ${board.categories.map(category => `
                <section class="board-category">
                    <div class="board-category-title">${escapeHtml(category.name)}</div>
                    ${(category.clues || []).map(clue => `
                        <div class="clue-tile ${clue.used ? 'used' : ''}">
                            ${clue.used ? 'USED' : clue.value}
                        </div>
                    `).join('')}
                </section>
            `).join('')}
        </div>
    `;
}

function renderPlayerFinalPanel(definition, game, myTeam) {
    const finalEnabled = definition?.config?.includeFinalJeopardy !== false;
    const final = game.finalJeopardy || {};
    const tieBreakers = game.tieBreakers || [];
    const finalPhaseActive = ['FINAL_CATEGORY', 'FINAL_WAGER', 'FINAL_CLUE_READY', 'FINAL_RESPONSE', 'FINAL_REVEAL'].includes(game.phase);
    const finalHistoryVisible = (game.teams || []).some(team =>
        team.finalEligible || team.finalWagerSubmitted || team.finalResponseSubmitted || team.finalJudged);
    const showFinal = finalEnabled && (Boolean(final.category || final.clue) || finalPhaseActive || finalHistoryVisible);
    const cards = [];

    if (showFinal) {
        cards.push(`
            <article class="clue-card">
                <div class="final-meta">
                    <span class="pill">Final Jeopardy</span>
                    ${final.category ? `<span class="pill">${escapeHtml(final.category)}</span>` : ''}
                    ${myTeam && myTeam.finalEligible ? '<span class="pill">Your team is eligible</span>' : '<span class="pill">Your team is not eligible</span>'}
                </div>
                <div class="clue-prompt">${final.clue ? escapeHtml(final.clue) : 'The moderator will reveal the category or clue here when that stage begins.'}</div>
                ${ChemETheme.imageHtml(final.image, playerBasePath)}
                <div class="detail-card">
                    <span class="label">Your Team Status</span>
                    <div class="value">${renderFinalTeamStatus(game, myTeam)}</div>
                </div>
            </article>
        `);
    }

    if (game.tiePending || game.round === 'TIEBREAKER' || game.phase === 'GAME_OVER') {
        const teamsInTie = (game.teams || []).filter(team => (game.tieTeamIds || []).includes(team.id)).map(team => team.name);
        cards.push(`
            <article class="detail-card">
                <span class="label">Tie-Breaker Watch</span>
                <div class="value">${teamsInTie.length ? `Teams tied for first: ${teamsInTie.join(', ')}` : 'Tie-breaker status will appear here if needed.'}</div>
                <p>${tieBreakers.length ? `Configured tie-breaker categories: ${tieBreakers.map(item => item.category).join(', ')}` : 'No tie-breakers configured yet.'}</p>
            </article>
        `);
    }

    return cards.length ? `<div class="final-grid">${cards.join('')}</div>` : '';
}

function renderFinalTeamStatus(game, myTeam) {
    if (!myTeam) {
        return 'Join a team to participate.';
    }
    if (!myTeam.finalEligible && ['FINAL_CATEGORY', 'FINAL_WAGER', 'FINAL_CLUE_READY', 'FINAL_RESPONSE', 'FINAL_REVEAL'].includes(game.phase)) {
        return 'Your team is not eligible because the score is zero or below.';
    }
    if (game.phase === 'FINAL_WAGER') {
        return myTeam.finalWagerSubmitted ? 'Wager submitted.' : 'Waiting for your team wager.';
    }
    if (game.phase === 'FINAL_RESPONSE') {
        return myTeam.finalResponseSubmitted ? 'Response submitted.' : 'Waiting for your team response.';
    }
    if (game.phase === 'FINAL_REVEAL' || game.phase === 'GAME_OVER') {
        if (myTeam.finalJudged) {
            return `${myTeam.finalResultCorrect ? 'Correct' : 'Incorrect'} in Final Jeopardy.`;
        }
        return myTeam.finalEligible ? 'Waiting for Final Jeopardy reveal.' : 'Not eligible for Final Jeopardy.';
    }
    return myTeam.finalEligible ? 'Ready for Final Jeopardy if the round reaches it.' : 'Not eligible at the moment.';
}

function canBuzz(game, myTeam) {
    if (!game.activeClue || game.activeClue.dailyDouble) {
        return false;
    }
    if (!['CLUE_READING', 'BUZZ_OPEN'].includes(game.phase)) {
        return false;
    }
    if ((game.lockedOutTeamIds || []).includes(myTeam.id)) {
        return false;
    }
    if (game.round === 'TIEBREAKER' && !(game.tieTeamIds || []).includes(myTeam.id)) {
        return false;
    }
    return true;
}

function buzzStatusLabel(game, myTeam) {
    if (!game.activeClue) {
        return game.phase === 'ROUND_BOARD' ? 'Waiting For Clue' : 'Stand By';
    }
    if (game.activeClue.dailyDouble) {
        return 'Daily Double';
    }
    if ((game.lockedOutTeamIds || []).includes(myTeam.id)) {
        return 'Locked Out';
    }
    if (game.round === 'TIEBREAKER' && !(game.tieTeamIds || []).includes(myTeam.id)) {
        return 'Tie-Breaker Only';
    }
    if (game.phase === 'PLAYER_RESPONSE') {
        return game.recognizedTeamId === myTeam.id ? 'Answer Now' : 'Other Team Answering';
    }
    return 'Stand By';
}

function getMyTeam() {
    if (!playerState.session || !playerState.snapshot) {
        return null;
    }
    return (playerState.snapshot.game.teams || []).find(team => team.id === playerState.session.teamId) || null;
}

function getMyPlayer() {
    const myTeam = getMyTeam();
    if (!myTeam || !playerState.session) {
        return null;
    }
    return (myTeam.players || []).find(player => player.id === playerState.session.playerId) || null;
}

function renderSyncSummary() {
    if (!playerState.session) {
        return 'Join first';
    }
    if (!playerState.syncInfo) {
        return 'Not synced';
    }
    return `Ready • offset ${Math.round(playerState.syncInfo.offset)} ms • RTT ${playerState.syncInfo.latency} ms`;
}

function statusCard(label, value) {
    return `<article class="status-card"><span class="label">${escapeHtml(label)}</span><div class="value">${escapeHtml(String(value))}</div></article>`;
}

function humanize(value) {
    return String(value || '')
        .toLowerCase()
        .split('_')
        .map(piece => piece.charAt(0).toUpperCase() + piece.slice(1))
        .join(' ');
}

function secondsLeft(endsAt) {
    return Math.max(0, Math.ceil((endsAt - Date.now()) / 1000));
}

function loadSession() {
    try {
        const raw = localStorage.getItem(playerSessionStorageKey());
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

function saveSession(session) {
    localStorage.setItem(playerSessionStorageKey(), JSON.stringify(session));
}

function reconcileSession(game) {
    if (!playerState.session) {
        return;
    }
    const liveTeam = (game.teams || []).find(team => team.id === playerState.session.teamId);
    const livePlayer = liveTeam ? (liveTeam.players || []).find(player => player.id === playerState.session.playerId) : null;
    if (livePlayer) {
        return;
    }
    const presentSomewhere = (game.teams || []).some(team =>
        (team.players || []).some(player => player.id === playerState.session.playerId));
    if (presentSomewhere) {
        return;
    }
    playerState.session = null;
    playerState.syncInfo = null;
    localStorage.removeItem(playerSessionStorageKey());
    if (game.phase === 'SETUP') {
        showToast('Your saved session is no longer active. Please join again.');
    } else {
        showToast('You were removed from your team. Rejoin to keep playing.');
    }
}

async function postForm(url, payload) {
    const body = new URLSearchParams();
    Object.entries(payload).forEach(([key, value]) => body.set(key, value ?? ''));
    const response = await fetch(apiUrl(url), {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    return response.json();
}

function showToast(message) {
    const toast = document.getElementById('toast');
    toast.textContent = message;
    toast.classList.add('visible');
    clearTimeout(playerState.toastTimer);
    playerState.toastTimer = setTimeout(() => toast.classList.remove('visible'), 2600);
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
    return `${playerBasePath}${path}`;
}

function playerSessionStorageKey() {
    return `cheme-player-session:${playerBasePath || 'default'}`;
}

function playerThemeStorageKey() {
    return `cheme-player-theme:${playerBasePath || 'default'}`;
}

function gameBasePath() {
    const firstSegment = window.location.pathname.split('/').filter(Boolean)[0] || '';
    return ['moderator', 'player', 'display', 'games'].includes(firstSegment) ? '' : `/${firstSegment}`;
}
