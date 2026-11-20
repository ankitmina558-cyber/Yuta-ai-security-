// Yuta AI Progressive Web App Controller (24/7 Sticker Moderation + Shared Backend)
// Strictly stores ONLY the user's backend URL & passcode locally; Telegram Tokens and Gemini Keys stay on the server.

const STORAGE_URL = 'yuta_backend_url';
const STORAGE_SECRET = 'yuta_admin_secret';

let deferredInstallPrompt = null;
let serverUptimeBaseSeconds = 0;
let uptimeTickHandle = null;

window.addEventListener('load', () => {
  const splash = document.getElementById('splash-screen');
  setTimeout(() => {
    splash.classList.add('fade-out');
    setTimeout(() => splash.classList.add('hidden'), 420);
  }, 650);

  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('./sw.js').catch(() => {});
  }

  loadSavedBackendConfig();
  syncAllFromBackend();
});

window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  deferredInstallPrompt = event;
  document.getElementById('install-banner').classList.remove('hidden');
});

document.getElementById('install-btn').addEventListener('click', async () => {
  if (!deferredInstallPrompt) return;
  deferredInstallPrompt.prompt();
  await deferredInstallPrompt.userChoice;
  deferredInstallPrompt = null;
  document.getElementById('install-banner').classList.add('hidden');
});

// Tab navigation (both Rail and Bottom Bar)
function switchTab(tabName) {
  document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
  document.getElementById(`tab-${tabName}`).classList.add('active');
  document.querySelectorAll('.nav-btn').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.tab === tabName);
  });
}

document.querySelectorAll('.nav-btn').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.tab));
});

document.querySelectorAll('[data-jump]').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(btn.dataset.jump));
});

function getBackendConfig() {
  const url = (localStorage.getItem(STORAGE_URL) || '').trim().replace(/\/+$/, '');
  const secret = (localStorage.getItem(STORAGE_SECRET) || '').trim();
  return { url, secret };
}

function loadSavedBackendConfig() {
  const { url, secret } = getBackendConfig();
  document.getElementById('backend-url-input').value = url;
  document.getElementById('admin-secret-input').value = secret;
}

async function apiFetch(path, options = {}) {
  const { url, secret } = getBackendConfig();
  if (!url) {
    throw new Error('Shared Backend URL not configured in Settings.');
  }
  if (!url.startsWith('https://') && !url.startsWith('http://localhost')) {
    throw new Error('Secure HTTPS URL required.');
  }
  const headers = {
    ...(options.headers || {}),
    'X-Admin-Secret': secret
  };
  const response = await fetch(`${url}${path}`, { ...options, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || `HTTP ${response.status}`);
  }
  return data;
}

function formatDuration(totalSeconds) {
  const sec = Math.max(0, Math.floor(totalSeconds || 0));
  const hrs = Math.floor(sec / 3600);
  const mins = Math.floor((sec % 3600) / 60);
  const rem = sec % 60;
  return [hrs, mins, rem].map((v) => String(v).padStart(2, '0')).join(':');
}

function startUptimeTicker(initialSeconds) {
  serverUptimeBaseSeconds = Number(initialSeconds || 0);
  document.getElementById('stk-uptime').textContent = formatDuration(serverUptimeBaseSeconds);
  if (uptimeTickHandle) clearInterval(uptimeTickHandle);
  uptimeTickHandle = setInterval(() => {
    serverUptimeBaseSeconds += 1;
    document.getElementById('stk-uptime').textContent = formatDuration(serverUptimeBaseSeconds);
  }, 1000);
}

async function syncAllFromBackend() {
  const tgPill = document.getElementById('tg-pill');
  const aiPill = document.getElementById('ai-pill');
  const statusNote = document.getElementById('status-note');
  const stkAiStatus = document.getElementById('stk-ai-status');
  const stkAiSub = document.getElementById('stk-ai-sub');
  const { url } = getBackendConfig();

  if (!url) {
    tgPill.textContent = 'TG: Disconnected';
    tgPill.className = 'pill pill-muted';
    aiPill.textContent = 'AI: Disconnected';
    aiPill.className = 'pill pill-muted';
    stkAiStatus.textContent = 'Disconnected';
    stkAiSub.textContent = 'Connect Cloudflare Worker';
    return;
  }

  try {
    const status = await apiFetch('/api/status');
    tgPill.textContent = status.telegram?.ok ? `TG: Connected (@${status.telegram.username})` : 'TG: Error';
    tgPill.className = `pill ${status.telegram?.ok ? 'pill-ok' : 'pill-err'}`;

    const gemOk = !!status.geminiConnected;
    const gemErr = !!status.geminiConfigured && !gemOk;
    aiPill.textContent = gemOk
      ? `AI: Connected (${status.geminiModel})`
      : (gemErr ? 'AI: Error' : 'AI: Disconnected (Key Missing)');
    aiPill.className = `pill ${gemOk ? 'pill-ok' : (gemErr ? 'pill-err' : 'pill-warn')}`;

    stkAiStatus.textContent = gemOk ? 'Connected' : (gemErr ? 'Error' : 'Disconnected');
    stkAiSub.textContent = gemOk
      ? (status.geminiModel || 'gemini-2.5-flash')
      : (status.geminiError || 'Set GEMINI_API_KEY');
    startUptimeTicker(status.uptimeSeconds || 0);

    document.getElementById('bot-identity-label').textContent =
      status.telegram?.ok ? `@${status.telegram.username} • 24/7 Sticker Moderation` : 'Telegram Secret Error';
    const tgErrTxt = status.telegram?.ok ? '' : ` | TG Error: ${status.telegram?.error || 'Check TELEGRAM_BOT_TOKEN'}`;
    statusNote.textContent = status.lastError
      ? `${status.note || 'Synced.'}${tgErrTxt} | Last Worker Error: ${status.lastError}`
      : `${status.note || 'Synced with Cloudflare Worker.'}${tgErrTxt}`;

    const [statsData, stickerData, logsData, settingsData] = await Promise.all([
      apiFetch('/api/stats'),
      apiFetch('/api/stickers/stats'),
      apiFetch('/api/logs'),
      apiFetch('/api/settings')
    ]);

    renderStats(statsData);
    renderStickerStats(stickerData);
    renderLogs(logsData.logs || []);
    applySettingsForm(settingsData);
  } catch (err) {
    tgPill.textContent = 'TG: Error';
    tgPill.className = 'pill pill-err';
    aiPill.textContent = 'AI: Error';
    aiPill.className = 'pill pill-err';
    stkAiStatus.textContent = 'Error';
    stkAiSub.textContent = err.message;
    statusNote.textContent = `Backend Error: ${err.message}`;
  }
}

function renderStats(stats) {
  document.getElementById('stat-images').textContent = stats.totalImagesScanned ?? 0;
  document.getElementById('stat-stickers').textContent = stats.totalStickersScanned ?? 0;
  document.getElementById('stat-videos').textContent = stats.totalVideosAndGifsScanned ?? 0;
  document.getElementById('stat-violations').textContent = stats.totalViolationsDetected ?? 0;
  document.getElementById('stat-deleted').textContent = stats.totalMessagesDeleted ?? 0;
  document.getElementById('stat-uncertain').textContent = stats.totalUncertainOrFailed ?? 0;
}

function renderStickerStats(s) {
  document.getElementById('stk-received').textContent = s.totalStickersReceived ?? 0;
  document.getElementById('stk-scanned').textContent = s.totalStickersScanned ?? 0;
  document.getElementById('stk-violations').textContent = s.violationsDetected ?? 0;
  document.getElementById('stk-deleted').textContent = s.messagesDeleted ?? 0;
  document.getElementById('stk-pending').textContent = s.pendingScans ?? 0;
  document.getElementById('stk-failed').textContent = s.failedScans ?? 0;
}

function renderLogs(logs) {
  const recentContainer = document.getElementById('recent-logs');
  const allContainer = document.getElementById('all-logs');
  if (!logs.length) {
    const emptyHtml = '<div class="log-item muted-note">No moderation logs recorded on shared backend yet.</div>';
    recentContainer.innerHTML = emptyHtml;
    allContainer.innerHTML = emptyHtml;
    return;
  }

  const buildItem = (log) => {
    const isViolation = log.verdict === 'VIOLATION' || log.verdict === 'SEXUAL_CONTENT';
    const badgeClass =
      isViolation ? 'pill-err' :
      (log.verdict === 'UNCERTAIN' || log.verdict === 'SCAN_FAILED') ? 'pill-warn' : 'pill-ok';
    return `
      <div class="log-item">
        <div class="log-head">
          <strong>${escapeHtml(log.mediaKind)} • ${escapeHtml(log.chatTitle || 'Group')}</strong>
          <span class="pill ${badgeClass}">${escapeHtml(log.verdict)}</span>
        </div>
        <div class="muted-note">Action: ${escapeHtml(log.actionTaken)} • ${escapeHtml(log.reason)}</div>
      </div>
    `;
  };

  recentContainer.innerHTML = logs.slice(0, 5).map(buildItem).join('');
  allContainer.innerHTML = logs.map(buildItem).join('');
}

function applySettingsForm(s) {
  document.getElementById('tog-image').checked = !!s.enableImageModeration;
  document.getElementById('tog-sticker').checked = !!s.enableStickerModeration;
  document.getElementById('tog-gif').checked = !!s.enableGifModeration;
  document.getElementById('tog-chat').checked = !!s.enableAiChat;
  document.getElementById('tog-autodelete').checked = !!s.enableAutoDelete;
  document.getElementById('tog-notify').checked = !!s.enableAdminNotifications;
  document.getElementById('tog-retention').checked = !!s.disableMediaRetention;
  if (s.sensitivity) {
    document.getElementById('sensitivity-select').value = s.sensitivity;
  }
  document.getElementById('admin-user-ids-input').value = s.adminUserIds || '';
  document.getElementById('admin-alert-chat-input').value = s.adminAlertChatId || '';
}

document.getElementById('refresh-status-btn').addEventListener('click', syncAllFromBackend);

document.getElementById('process-queue-btn').addEventListener('click', async () => {
  try {
    const res = await apiFetch('/api/stickers/process-queue', { method: 'POST' });
    if (res.stickerStats) renderStickerStats(res.stickerStats);
    await syncAllFromBackend();
  } catch (err) {
    alert(`Queue drain failed: ${err.message}`);
  }
});

document.getElementById('save-backend-btn').addEventListener('click', async () => {
  const url = document.getElementById('backend-url-input').value.trim().replace(/\/+$/, '');
  const secret = document.getElementById('admin-secret-input').value.trim();
  const box = document.getElementById('backend-verify-output');
  box.classList.remove('hidden');

  if (!url.startsWith('https://')) {
    box.textContent = 'Error: Please enter a valid HTTPS backend URL.';
    return;
  }
  localStorage.setItem(STORAGE_URL, url);
  localStorage.setItem(STORAGE_SECRET, secret);
  box.textContent = 'Verifying Telegram getMe and Gemini status on server...';

  try {
    const res = await apiFetch('/api/telegram/verify');
    box.textContent = JSON.stringify(res, null, 2);
    await syncAllFromBackend();
  } catch (err) {
    box.textContent = `Verification failed: ${err.message}`;
  }
});

document.getElementById('test-connection-btn').addEventListener('click', async () => {
  const box = document.getElementById('backend-verify-output');
  box.classList.remove('hidden');
  box.textContent = 'Running live Telegram getMe + Gemini AI + D1 + Webhook test...';
  try {
    const res = await apiFetch('/api/connection-test', { method: 'POST' });
    box.textContent = JSON.stringify(res, null, 2);
    await syncAllFromBackend();
  } catch (err) {
    box.textContent = `Connection test Error: ${err.message}`;
  }
});

document.getElementById('setup-webhook-btn').addEventListener('click', async () => {
  const box = document.getElementById('backend-verify-output');
  box.classList.remove('hidden');
  box.textContent = 'Registering 24/7 Telegram Webhook with Cloudflare Worker...';
  try {
    const res = await apiFetch('/api/telegram/webhook-setup', { method: 'POST' });
    box.textContent = JSON.stringify(res, null, 2);
    await syncAllFromBackend();
  } catch (err) {
    box.textContent = `Webhook registration failed: ${err.message}`;
  }
});

document.getElementById('clear-backend-btn').addEventListener('click', () => {
  localStorage.removeItem(STORAGE_URL);
  localStorage.removeItem(STORAGE_SECRET);
  loadSavedBackendConfig();
  syncAllFromBackend();
});

document.getElementById('save-settings-btn').addEventListener('click', async () => {
  try {
    await apiFetch('/api/settings', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        enableImageModeration: document.getElementById('tog-image').checked,
        enableStickerModeration: document.getElementById('tog-sticker').checked,
        enableGifModeration: document.getElementById('tog-gif').checked,
        enableAiChat: document.getElementById('tog-chat').checked,
        enableAutoDelete: document.getElementById('tog-autodelete').checked,
        enableAdminNotifications: document.getElementById('tog-notify').checked,
        disableMediaRetention: document.getElementById('tog-retention').checked,
        sensitivity: document.getElementById('sensitivity-select').value,
        adminUserIds: document.getElementById('admin-user-ids-input').value.trim(),
        adminAlertChatId: document.getElementById('admin-alert-chat-input').value.trim()
      })
    });
    alert('Shared settings saved successfully!');
  } catch (err) {
    alert(`Failed to save settings: ${err.message}`);
  }
});

// Live Sticker & Media Scan via Backend (handles WEBP, WEBM frame extraction, and safe TGS handling)
document.getElementById('run-scan-btn').addEventListener('click', async () => {
  const fileInput = document.getElementById('scan-file-input');
  const box = document.getElementById('scan-result-box');
  const file = fileInput.files[0];
  if (!file) {
    alert('Please select a sticker or image file first.');
    return;
  }
  if (file.size > 10 * 1024 * 1024) {
    alert('File exceeds 10MB limit.');
    return;
  }

  box.classList.remove('hidden');
  box.textContent = 'Preparing frame & sending to secure backend for Gemini moderation...';

  try {
    const mediaKind = document.getElementById('scan-media-kind').value;
    const lowerName = (file.name || '').toLowerCase();
    let mimeType = file.type || 'image/jpeg';
    let base64 = '';

    if (lowerName.endsWith('.tgs') || mediaKind === 'STICKER_ANIMATED') {
      mimeType = 'application/x-tgsticker';
      base64 = await fileToBase64(file);
    } else if (lowerName.endsWith('.webm') || file.type === 'video/webm') {
      base64 = await extractWebmFirstFrameBase64(file);
      mimeType = 'image/jpeg';
    } else {
      base64 = await fileToBase64(file);
      if (lowerName.endsWith('.webp')) mimeType = 'image/webp';
    }

    const res = await apiFetch('/api/scan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mediaKind,
        mimeType,
        base64Data: base64
      })
    });
    box.textContent = JSON.stringify(res, null, 2);
    await syncAllFromBackend();
  } catch (err) {
    box.textContent = `Scan failed (not marked safe): ${err.message}`;
  }
});

// Live Bilingual AI Chat via Backend
document.getElementById('chat-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const input = document.getElementById('chat-input');
  const text = input.value.trim();
  if (!text) return;

  input.value = '';
  appendChatBubble('user', text);

  try {
    const res = await apiFetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: text })
    });
    appendChatBubble('model', res.reply || 'No reply.');
  } catch (err) {
    appendChatBubble('model', `Error: ${err.message}`);
  }
});

function appendChatBubble(role, text) {
  const container = document.getElementById('chat-messages');
  const div = document.createElement('div');
  div.className = `bubble ${role === 'user' ? 'bubble-user' : 'bubble-model'}`;
  div.textContent = text;
  container.appendChild(div);
  container.scrollTop = container.scrollHeight;
}

function extractWebmFirstFrameBase64(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.src = url;

    const cleanup = () => URL.revokeObjectURL(url);

    video.onloadeddata = () => {
      video.currentTime = Math.min(0.1, (video.duration || 0) / 2);
    };
    video.onseeked = () => {
      try {
        const canvas = document.createElement('canvas');
        canvas.width = Math.min(1024, video.videoWidth || 512);
        canvas.height = Math.min(1024, video.videoHeight || 512);
        const ctx = canvas.getContext('2d');
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const dataUrl = canvas.toDataURL('image/jpeg', 0.85);
        cleanup();
        resolve(dataUrl.split(',')[1]);
      } catch (err) {
        cleanup();
        reject(err);
      }
    };
    video.onerror = () => {
      cleanup();
      reject(new Error('Could not decode WEBM video sticker frame in browser.'));
    };
  });
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = String(reader.result || '');
      resolve(result.includes(',') ? result.split(',')[1] : result);
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}
