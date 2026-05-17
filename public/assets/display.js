/*
 * File: display.js
 * Description: Read-only live display renderer for public game state.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */

const displayBasePath = gameBasePath();

const displayState = {
    snapshot: null,
};

document.addEventListener('DOMContentLoaded', async () => {
    await refreshDisplayState();
    connectDisplayEvents();
    setInterval(renderDisplay, 1000);
});

async function refreshDisplayState() {
    const response = await fetch(apiUrl('/api/state'));
    displayState.snapshot = await response.json();
    renderDisplay();
}

function connectDisplayEvents() {
    const source = new EventSource(apiUrl('/api/events'));
    source.addEventListener('state', event => {
        displayState.snapshot = JSON.parse(event.data);
        renderDisplay();
    });
    source.onerror = () => {
        setTimeout(() => {
            source.close();
            connectDisplayEvents();
        }, 1500);
    };
}

function renderDisplay() {
    const snapshot = displayState.snapshot;
    if (!snapshot) {
        return;
    }
    const game = snapshot.game;
    const setupLobbyVisible = game.phase === 'SETUP';
    ChemETheme.apply(displayTheme(snapshot), { color: 'blue', mode: 'dark' });
    document.body.classList.toggle('display-lobby-active', setupLobbyVisible);
    document.title = `${displayTitle(snapshot)} Display`;
    document.getElementById('display-scorebar').innerHTML = setupLobbyVisible ? '' : renderScorebar(game);
    document.getElementById('display-stage').innerHTML = renderStage(snapshot);
}

function renderScorebar(game) {
    return (game.teams || [])
        .filter(team => team.active)
        .map(team => `
            <article class="display-team" style="--team-accent:${escapeHtml(team.color)}">
                <div class="display-team-name">${escapeHtml(team.name)}</div>
                <div class="display-team-score">${team.score}</div>
            </article>
        `)
        .join('');
}

function renderStage(snapshot) {
    const game = snapshot.game;
    if (game.phase === 'SETUP') {
        return renderLobby(snapshot);
    }
    if (game.activeClue) {
        return renderActiveClue(game);
    }
    if (isFinalStage(game)) {
        return renderFinalStage(snapshot);
    }
    if (game.phase === 'GAME_OVER') {
        return renderGameOver(game);
    }
    return renderBoard(game.currentBoard, game.statusMessage, displayTitle(snapshot));
}

function renderLobby(snapshot) {
    const game = snapshot.game;
    const maxPlayersPerTeam = Math.max(1, Number(snapshot.definition?.config?.maxPlayersPerTeam || 1));
    const teams = (game.teams || []).filter(team => team.active);
    const joinedPlayers = teams.reduce((total, team) => total + (team.players || []).length, 0);
    const playerCapacity = teams.length * maxPlayersPerTeam;

    return `
        <article class="display-lobby">
            <header class="display-lobby-head">
                <div class="display-meta">
                    <span class="display-pill">Waiting Room</span>
                    <span class="display-pill">${joinedPlayers}/${playerCapacity} players</span>
                </div>
                <h1>${escapeHtml(displayTitle(snapshot))}</h1>
                <p>${escapeHtml(game.statusMessage || 'Players can join now. The board will appear when the moderator starts the game.')}</p>
            </header>
            <div class="display-lobby-grid">
                ${teams.map(team => renderLobbyTeam(team, maxPlayersPerTeam)).join('')}
            </div>
        </article>
    `;
}

function renderLobbyTeam(team, maxPlayersPerTeam) {
    const players = team.players || [];
    const openSlots = Math.max(0, maxPlayersPerTeam - players.length);
    const slots = [
        ...players.map(player => `
            <li class="${player.connected ? '' : 'idle'}">
                <span class="display-player-dot"></span>
                <span>${escapeHtml(player.displayName)}${player.connected ? '' : ' (idle)'}</span>
            </li>
        `),
        ...Array.from({ length: openSlots }, () => `
            <li class="waiting">
                <span class="display-player-dot"></span>
                <span>Waiting for player</span>
            </li>
        `),
    ];

    return `
        <section class="display-lobby-team" style="--team-accent:${escapeHtml(team.color)}">
            <div class="display-lobby-team-head">
                <h2>${escapeHtml(team.name)}</h2>
                <span>${players.length}/${maxPlayersPerTeam}</span>
            </div>
            <ul class="display-lobby-roster">
                ${slots.join('')}
            </ul>
        </section>
    `;
}

function renderActiveClue(game) {
    const clue = game.activeClue;
    const prompt = clue.prompt || 'Daily Double';
    const response = game.answerVisible && clue.response
        ? `<div class="display-response">${escapeHtml(clue.response)}</div>`
        : '';
    const countdown = game.countdown ? `<span class="display-pill">${secondsLeft(game.countdown.endsAt)}s</span>` : '';
    const recognized = game.recognizedTeamName
        ? `<span class="display-pill">${escapeHtml(game.recognizedTeamName)}</span>`
        : '';

    return `
        <article class="display-clue">
            <div class="display-meta">
                <span class="display-pill">${escapeHtml(clue.categoryName)}</span>
                <span class="display-pill">${clue.dailyDouble ? 'Daily Double' : `${clue.value} points`}</span>
                ${recognized}
                ${countdown}
            </div>
            <div class="display-prompt">${escapeHtml(prompt)}</div>
            ${ChemETheme.imageHtml(clue.image, displayBasePath, 'display-clue-image')}
            ${response}
            <div class="display-status">${escapeHtml(game.statusMessage || '')}</div>
        </article>
    `;
}

function renderBoard(board, statusMessage, title = 'ChemE Jeopardy') {
    if (!board || !board.categories) {
        return `
            <article class="display-message">
                <h1>${escapeHtml(title)}</h1>
                <p>${escapeHtml(statusMessage || 'Waiting for the moderator.')}</p>
            </article>
        `;
    }
    return `
        <div class="display-board">
            ${board.categories.map(category => `
                <section class="display-category">
                    <div class="display-category-title">${escapeHtml(category.name)}</div>
                    ${(category.clues || []).map(clue => `
                        <div class="display-tile ${clue.used ? 'used' : ''}">
                            ${clue.used ? '' : clue.value}
                        </div>
                    `).join('')}
                </section>
            `).join('')}
        </div>
    `;
}

function renderFinalStage(snapshot) {
    const game = snapshot.game;
    const final = game.finalJeopardy || {};
    const content = game.phase === 'FINAL_CATEGORY' || game.phase === 'FINAL_WAGER'
        ? final.category || 'Final Jeopardy'
        : final.clue || final.category || 'Final Jeopardy';
    const image = game.phase === 'FINAL_CATEGORY' || game.phase === 'FINAL_WAGER'
        ? ''
        : ChemETheme.imageHtml(final.image, displayBasePath, 'display-clue-image');
    return `
        <article class="display-clue">
            <div class="display-meta">
                <span class="display-pill">Final Jeopardy</span>
                ${game.countdown ? `<span class="display-pill">${secondsLeft(game.countdown.endsAt)}s</span>` : ''}
            </div>
            <div class="display-prompt">${escapeHtml(content)}</div>
            ${image}
            <div class="display-status">${escapeHtml(game.statusMessage || '')}</div>
        </article>
    `;
}

function renderGameOver(game) {
    const winner = (game.teams || []).find(team => team.id === game.winnerTeamId);
    return `
        <article class="display-message">
            <h1>${winner ? escapeHtml(winner.name) : 'Game Over'}</h1>
            <p>${escapeHtml(game.statusMessage || 'Game over.')}</p>
        </article>
    `;
}

function isFinalStage(game) {
    return ['FINAL_CATEGORY', 'FINAL_WAGER', 'FINAL_CLUE_READY', 'FINAL_RESPONSE', 'FINAL_REVEAL'].includes(game.phase);
}

function secondsLeft(endsAt) {
    return Math.max(0, Math.ceil((endsAt - Date.now()) / 1000));
}

function displayTitle(snapshot) {
    return snapshot.gameInstance?.displayName || snapshot.definition?.title || 'ChemE Jeopardy';
}

function displayTheme(snapshot) {
    return ChemETheme.normalize({
        color: snapshot.definition?.config?.displayThemeColor,
        mode: snapshot.definition?.config?.displayThemeMode,
    }, { color: 'blue', mode: 'dark' });
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
    return `${displayBasePath}${path}`;
}

function gameBasePath() {
    const firstSegment = window.location.pathname.split('/').filter(Boolean)[0] || '';
    return ['moderator', 'player', 'display', 'games'].includes(firstSegment) ? '' : `/${firstSegment}`;
}
