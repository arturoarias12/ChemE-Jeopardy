/*
 * File: theme.js
 * Description: Local and game-controlled color theme helpers.
 * Author: Arturo Arias
 * Last updated: 2026-05-16
 */

const ChemETheme = (() => {
    const allowedColors = new Set(['blue', 'black', 'red', 'green']);
    const allowedModes = new Set(['system', 'dark', 'light']);

    function normalize(theme = {}, defaults = {}) {
        const fallback = {
            color: defaults.color || 'blue',
            mode: defaults.mode || 'dark',
        };
        const color = allowedColors.has(theme.color) ? theme.color : fallback.color;
        const mode = allowedModes.has(theme.mode) ? theme.mode : fallback.mode;
        return { color, mode };
    }

    function resolvedMode(mode) {
        if (mode !== 'system') {
            return mode;
        }
        if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) {
            return 'light';
        }
        return 'dark';
    }

    function apply(theme = {}, defaults = {}) {
        const normalized = normalize(theme, defaults);
        document.body.dataset.themeColor = normalized.color;
        document.body.dataset.themeMode = resolvedMode(normalized.mode);
        document.body.dataset.themePreference = normalized.mode;
        document.documentElement.style.colorScheme = resolvedMode(normalized.mode);
        return normalized;
    }

    function load(storageKey, defaults = {}) {
        try {
            const raw = localStorage.getItem(storageKey);
            return normalize(raw ? JSON.parse(raw) : {}, defaults);
        } catch {
            return normalize({}, defaults);
        }
    }

    function save(storageKey, theme, defaults = {}) {
        const normalized = normalize(theme, defaults);
        localStorage.setItem(storageKey, JSON.stringify(normalized));
        return normalized;
    }

    function setControls(colorId, modeId, theme) {
        const color = document.getElementById(colorId);
        const mode = document.getElementById(modeId);
        if (color) {
            color.value = theme.color;
        }
        if (mode) {
            mode.value = theme.mode;
        }
    }

    function readControls(colorId, modeId, defaults = {}) {
        return normalize({
            color: document.getElementById(colorId)?.value,
            mode: document.getElementById(modeId)?.value,
        }, defaults);
    }

    function bindLocalControls({ colorId, modeId, storageKey, defaults }) {
        const saved = load(storageKey, defaults);
        setControls(colorId, modeId, saved);
        apply(saved, defaults);

        const update = () => {
            const next = save(storageKey, readControls(colorId, modeId, defaults), defaults);
            apply(next, defaults);
        };
        document.getElementById(colorId)?.addEventListener('change', update);
        document.getElementById(modeId)?.addEventListener('change', update);
        if (window.matchMedia) {
            const systemPreference = window.matchMedia('(prefers-color-scheme: light)');
            const handleSystemChange = () => {
                apply(load(storageKey, defaults), defaults);
            };
            if (systemPreference.addEventListener) {
                systemPreference.addEventListener('change', handleSystemChange);
            } else if (systemPreference.addListener) {
                systemPreference.addListener(handleSystemChange);
            }
        }
    }

    function imageHtml(image, basePath, className = 'clue-image') {
        const normalized = normalizeImage(image);
        if (!normalized) {
            return '';
        }
        const src = resolveImageSrc(normalized.src, basePath);
        if (!src) {
            return '';
        }
        return `
            <figure class="${escapeAttribute(className)}">
                <img src="${escapeAttribute(src)}" alt="${escapeAttribute(normalized.alt)}" loading="lazy">
            </figure>
        `;
    }

    function normalizeImage(image) {
        if (!image) {
            return null;
        }
        if (typeof image === 'string') {
            return image.trim() ? { src: image.trim(), alt: '' } : null;
        }
        const src = String(image.src || image.url || '').trim();
        if (!src) {
            return null;
        }
        return { src, alt: String(image.alt || '') };
    }

    function resolveImageSrc(src, basePath) {
        const cleanSrc = String(src || '').trim();
        if (!cleanSrc || cleanSrc.startsWith('javascript:')) {
            return '';
        }
        if (cleanSrc.startsWith('http://') || cleanSrc.startsWith('https://') || cleanSrc.startsWith('/')) {
            return cleanSrc;
        }
        const parts = cleanSrc.split('/').filter(Boolean);
        if (parts.some(part => part === '.' || part === '..')) {
            return '';
        }
        return `${basePath || ''}/package-assets/${parts.map(encodeURIComponent).join('/')}`;
    }

    function escapeAttribute(value) {
        return String(value ?? '')
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }

    return {
        apply,
        bindLocalControls,
        imageHtml,
        load,
        normalize,
        readControls,
        save,
        setControls,
    };
})();

window.ChemETheme = ChemETheme;
