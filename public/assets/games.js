/*
 * File: games.js
 * Description: Master game-instance manager UI for ChemE Jeopardy.
 * Author: Arturo Arias
 * Last updated: 2026-05-15
 */

const masterState = {
    authenticated: false,
    games: [],
    toastTimer: null,
};

document.addEventListener('DOMContentLoaded', async () => {
    bindMasterEvents();
    await Promise.all([refreshMasterAuth(), refreshGames()]);
});

function bindMasterEvents() {
    document.getElementById('refresh-games-button').addEventListener('click', refreshGames);
    document.getElementById('master-login-form').addEventListener('submit', handleMasterLogin);
    document.getElementById('master-logout-button').addEventListener('click', handleMasterLogout);
    document.getElementById('create-game-form').addEventListener('submit', handleCreateGame);
    document.getElementById('create-game-form').addEventListener('change', handleCreateFormChange);
    document.getElementById('games-list').addEventListener('submit', handleGameEdit);
    document.getElementById('games-list').addEventListener('click', handleGamesListClick);
}

async function handleGamesListClick(event) {
    const passwordButton = event.target.closest('[data-change-password]');
    if (passwordButton) {
        await changeGamePassword(passwordButton.dataset.gameId, passwordButton.dataset.changePassword);
        return;
    }
    await handleGameDelete(event);
}

async function changeGamePassword(gameId, kind) {
    const label = kind === 'moderator' ? 'moderator' : 'player';
    const newPassword = window.prompt(`Enter the new ${label} password for "${gameId}":`);
    if (newPassword === null) {
        return;
    }
    if (!newPassword.trim()) {
        showToast(`${label.charAt(0).toUpperCase() + label.slice(1)} password cannot be empty.`);
        return;
    }
    const game = masterState.games.find(entry => entry.id === gameId);
    if (!game) {
        showToast('That game no longer exists.');
        return;
    }
    const payload = {
        currentGameId: gameId,
        gameId,
        displayName: game.displayName || game.title || gameId,
        moderatorPassword: kind === 'moderator' ? newPassword : '',
        playerPassword: kind === 'player' ? newPassword : '',
    };
    const response = await postJson('/api/games/update', payload);
    showToast(response.message);
    if (response.ok) {
        await refreshGames();
    }
}

async function refreshMasterAuth() {
    const response = await fetch('/api/master/auth-status');
    const payload = await response.json();
    setMasterAuthenticated(Boolean(payload.authenticated));
}

async function refreshGames() {
    const response = await fetch('/api/games');
    const payload = await response.json();
    masterState.games = payload.games || [];
    renderGames();
}

async function handleMasterLogin(event) {
    event.preventDefault();
    const password = document.getElementById('master-password').value;
    const response = await postForm('/api/master/login', { password }, false);
    if (!response.ok) {
        showToast(response.message || 'Unable to unlock game manager.');
        return;
    }
    document.getElementById('master-password').value = '';
    setMasterAuthenticated(true);
    showToast(response.message);
}

async function handleMasterLogout() {
    const response = await post('/api/master/logout', false);
    setMasterAuthenticated(false);
    showToast(response.message || 'Game manager locked.');
}

async function handleCreateGame(event) {
    event.preventDefault();
    const sourceChoice = document.querySelector('input[name="questionSource"]:checked')?.value || 'default';
    const upload = document.getElementById('new-game-upload').files[0];
    const payload = {
        gameId: document.getElementById('new-game-id').value.trim(),
        displayName: document.getElementById('new-game-name').value.trim(),
        moderatorPassword: document.getElementById('new-game-moderator-password').value,
        playerPassword: document.getElementById('new-game-player-password').value,
    };
    if (sourceChoice === 'upload' && !upload) {
        showToast('Choose a question JSON file, or use the default question JSON.');
        return;
    }
    if (sourceChoice === 'upload') {
        Object.assign(payload, await uploadPayload(upload));
    }
    const response = await postJson('/api/games', payload);
    showToast(response.message);
    if (response.ok) {
        document.getElementById('create-game-form').reset();
        await refreshGames();
    }
}

async function uploadPayload(file) {
    const payload = { uploadedFileName: file.name };
    if (isPackageFile(file)) {
        payload.uploadedContentBase64 = await fileToBase64(file);
    } else {
        payload.uploadedContent = await file.text();
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

function handleCreateFormChange(event) {
    if (event.target.name !== 'questionSource') {
        return;
    }
    const uploadSelected = event.target.value === 'upload' && event.target.checked;
    const uploadInput = document.getElementById('new-game-upload');
    uploadInput.classList.toggle('hidden', !uploadSelected);
    if (uploadSelected) {
        uploadInput.click();
    } else {
        uploadInput.value = '';
    }
}

async function handleGameDelete(event) {
    const button = event.target.closest('[data-game-delete-button]');
    if (!button) {
        return;
    }
    const gameId = button.dataset.gameId;
    if (!confirm(`Delete game "${gameId}"?\n\nActive players will be disconnected and this cannot be undone.`)) {
        return;
    }
    const response = await postJson('/api/games/delete', { gameId });
    showToast(response.message);
    if (response.ok) {
        await refreshGames();
    }
}

async function handleGameEdit(event) {
    const form = event.target.closest('[data-game-edit-form]');
    if (!form) {
        return;
    }
    event.preventDefault();
    const payload = {
        currentGameId: form.dataset.gameId,
        gameId: form.querySelector('[name="gameId"]').value.trim(),
        displayName: form.querySelector('[name="displayName"]').value.trim(),
    };
    const response = await postJson('/api/games/update', payload);
    showToast(response.message);
    if (response.ok) {
        await refreshGames();
    }
}

function setMasterAuthenticated(authenticated) {
    masterState.authenticated = authenticated;
    document.getElementById('master-login-shell').classList.toggle('hidden', authenticated);
    document.getElementById('game-create-shell').classList.toggle('hidden', !authenticated);
    document.getElementById('master-status-chip').textContent = authenticated ? 'Unlocked' : 'Locked';
    renderGames();
}

function renderGames() {
    const list = document.getElementById('games-list');
    if (!masterState.games.length) {
        list.innerHTML = '<article class="detail-card"><span class="label">Games</span><div class="value">No games yet</div><p class="minor-note">Unlock the manager and create the first game room.</p></article>';
        return;
    }
    list.innerHTML = masterState.games.map(game => `
        <article class="game-card">
            <div>
                <span class="label">${escapeHtml(game.id)}</span>
                <div class="value">${escapeHtml(game.title || game.displayName || game.id)}</div>
            </div>
            <div class="game-card-meta">
                <span class="pill">${escapeHtml(humanize(game.phase))}</span>
                <span class="pill">${Number(game.joinedPlayers || 0)}/${Number(game.playerCapacity || 0)} players</span>
                <span class="pill">${escapeHtml(game.questionSource?.label || 'Default questions')}</span>
            </div>
            ${masterState.authenticated ? `
                <form class="stack-form" data-game-edit-form data-game-id="${escapeHtml(game.id)}">
                    <div class="setup-subgrid">
                        <label class="input-group">
                            <span>Display title</span>
                            <input type="text" name="displayName" value="${escapeHtml(game.displayName || game.title || game.id)}">
                        </label>
                        <label class="input-group">
                            <span>URL slug</span>
                            <input type="text" name="gameId" value="${escapeHtml(game.id)}" pattern="[a-z0-9][a-z0-9-]*">
                        </label>
                    </div>
                    <div class="button-row">
                        <button class="button ghost-button" type="submit">Update Game</button>
                        <button class="button ghost-button" type="button" data-change-password="moderator" data-game-id="${escapeHtml(game.id)}">Change Moderator Password</button>
                        <button class="button ghost-button" type="button" data-change-password="player" data-game-id="${escapeHtml(game.id)}">Change Player Password</button>
                        <button class="button danger-button" type="button" data-game-delete-button data-game-id="${escapeHtml(game.id)}">Delete Game</button>
                    </div>
                </form>
            ` : ''}
            <div class="button-row">
                <a class="button primary-button" href="${escapeHtml(game.moderatorUrl)}">Moderator</a>
                <a class="button ghost-button" href="${escapeHtml(game.playerUrl)}">Players</a>
                <a class="button ghost-button" href="${escapeHtml(game.displayUrl)}">Display</a>
            </div>
        </article>
    `).join('');
}

async function post(url, requireAuth = true) {
    const response = await fetch(url, { method: 'POST' });
    return readJsonResponse(response, requireAuth);
}

async function postForm(url, payload, requireAuth = true) {
    const body = new URLSearchParams();
    Object.entries(payload).forEach(([key, value]) => body.set(key, value ?? ''));
    const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
    });
    return readJsonResponse(response, requireAuth);
}

async function postJson(url, payload, requireAuth = true) {
    const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });
    return readJsonResponse(response, requireAuth);
}

async function readJsonResponse(response, requireAuth = true) {
    let payload = { ok: false, message: 'Unexpected server response.' };
    try {
        payload = await response.json();
    } catch {
        // Keep fallback payload.
    }
    if (response.status === 401 && requireAuth) {
        setMasterAuthenticated(false);
        payload.message ||= 'Manager login required.';
    }
    return payload;
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
    clearTimeout(masterState.toastTimer);
    masterState.toastTimer = setTimeout(() => toast.classList.remove('visible'), 2800);
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}
