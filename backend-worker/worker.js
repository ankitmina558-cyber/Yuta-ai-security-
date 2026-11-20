// Yuta AI Shared Serverless Backend (Cloudflare Worker + D1 SQLite + 24/7 Sticker Moderation Queue)
// All secrets (TELEGRAM_BOT_TOKEN, GEMINI_API_KEY, ADMIN_SECRET) remain strictly in Cloudflare Secrets.

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-Admin-Secret'
};

const MAX_RETRIES = 3;
const MAX_STICKER_BYTES = 4 * 1024 * 1024; // 4 MB free-tier Worker inline-base64 & 10ms CPU safety cap

export default {
  async fetch(request, env, ctx) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    await touchScannerMeta(env);

    // Public Telegram Webhook Endpoint (24/7 incoming group updates)
    if (url.pathname === '/telegram/webhook' && request.method === 'POST') {
      if (env.TELEGRAM_WEBHOOK_SECRET) {
        const headerSecret = request.headers.get('X-Telegram-Bot-Api-Secret-Token');
        if (headerSecret !== env.TELEGRAM_WEBHOOK_SECRET) {
          return json({ ok: false, error: 'Invalid webhook secret' }, 403);
        }
      }
      const update = await request.json().catch(() => null);
      if (update?.message) {
        await handleTelegramMessage(update.message, update.update_id || 0, env, ctx);
      }
      return json({ ok: true });
    }

    // Protected Admin API Endpoints (Shared by both Web PWA and Android APK)
    if (url.pathname.startsWith('/api/')) {
      const providedSecret = request.headers.get('X-Admin-Secret') || '';
      if (!env.ADMIN_SECRET || providedSecret !== env.ADMIN_SECRET) {
        return json({ error: 'Unauthorized: Check ADMIN_SECRET passcode.' }, 401);
      }

      if (url.pathname === '/api/status' && request.method === 'GET') {
        const tg = await callTelegram(env, 'getMe');
        const gem = await verifyGeminiKeyMetadata(env);
        const meta = await getScannerMeta(env);
        const stickerStats = await getStickerStats(env);
        const now = Date.now();
        const uptimeSeconds = meta?.startedAt ? Math.max(0, Math.floor((now - meta.startedAt) / 1000)) : 0;
        return json({
          telegram: tg.ok ? { ok: true, id: tg.result.id, username: tg.result.username } : { ok: false, error: tg.description },
          geminiConfigured: Boolean(env.GEMINI_API_KEY),
          geminiConnected: gem.ok,
          geminiError: gem.error || '',
          geminiModel: gem.model,
          databaseBound: Boolean(env.DB),
          uptimeSeconds,
          lastHeartbeatAt: meta?.lastHeartbeatAt || now,
          lastError: meta?.lastError || '',
          stickerStats,
          note: tg.ok && gem.ok
            ? 'Connected: Telegram Bot & Gemini AI verified.'
            : 'Check Telegram/Gemini secrets if any service shows Error.'
        });
      }

      if (url.pathname === '/api/connection-test' && (request.method === 'GET' || request.method === 'POST')) {
        const tg = await callTelegram(env, 'getMe');
        const webhook = tg.ok ? await callTelegram(env, 'getWebhookInfo') : { ok: false, description: tg.description };
        const gem = await testGeminiLive(env);
        let dbOk = false;
        if (env.DB) {
          try {
            await env.DB.prepare('SELECT 1 AS ok').first();
            dbOk = true;
          } catch (_err) {
            dbOk = false;
          }
        }
        return json({
          ok: Boolean(tg.ok && gem.ok),
          telegram: tg.ok
            ? { ok: true, id: tg.result.id, username: tg.result.username }
            : { ok: false, error: tg.description || 'Telegram getMe failed' },
          gemini: gem,
          webhook: webhook.ok
            ? { ok: true, url: webhook.result?.url || '', pending_update_count: webhook.result?.pending_update_count || 0 }
            : { ok: false, url: '', pending_update_count: 0, error: webhook.description || 'getWebhookInfo failed' },
          database: { ok: dbOk }
        }, tg.ok || gem.ok ? 200 : 400);
      }

      if (url.pathname === '/api/telegram/verify' && request.method === 'GET') {
        const tg = await callTelegram(env, 'getMe');
        return json(tg, tg.ok ? 200 : 400);
      }

      if (url.pathname === '/api/telegram/webhook-status' && request.method === 'GET') {
        const info = await callTelegram(env, 'getWebhookInfo');
        return json(info, info.ok ? 200 : 400);
      }

      if (url.pathname === '/api/telegram/webhook-setup' && request.method === 'POST') {
        const webhookUrl = `${url.origin}/telegram/webhook`;
        const payload = {
          url: webhookUrl,
          allowed_updates: ['message'],
          drop_pending_updates: false
        };
        if (env.TELEGRAM_WEBHOOK_SECRET) {
          payload.secret_token = env.TELEGRAM_WEBHOOK_SECRET;
        }
        const setup = await callTelegram(env, 'setWebhook', payload);
        return json({ ...setup, webhookUrl }, setup.ok ? 200 : 400);
      }

      if (url.pathname === '/api/stats' && request.method === 'GET') {
        const stats = await getStats(env);
        const stickerStats = await getStickerStats(env);
        return json({ ...stats, stickerStats });
      }

      if (url.pathname === '/api/stickers/stats' && request.method === 'GET') {
        const stickerStats = await getStickerStats(env);
        const meta = await getScannerMeta(env);
        const uptimeSeconds = meta?.startedAt ? Math.max(0, Math.floor((Date.now() - meta.startedAt) / 1000)) : 0;
        return json({ ...stickerStats, uptimeSeconds });
      }

      if (url.pathname === '/api/stickers/queue' && request.method === 'GET') {
        const queue = await getStickerQueue(env);
        return json({ queue });
      }

      if (url.pathname === '/api/stickers/process-queue' && request.method === 'POST') {
        const processed = await processStickerQueue(env, true);
        return json({ ok: true, processed, stickerStats: await getStickerStats(env) });
      }

      if (url.pathname === '/api/logs' && request.method === 'GET') {
        const logs = await getRecentLogs(env);
        return json({ logs });
      }

      if (url.pathname === '/api/settings' && request.method === 'GET') {
        return json(await getSettings(env));
      }

      if (url.pathname === '/api/settings' && request.method === 'POST') {
        const body = await request.json();
        await saveSettings(env, body);
        return json({ ok: true, settings: await getSettings(env) });
      }

      if (url.pathname === '/api/scan' && request.method === 'POST') {
        const body = await request.json();
        const settings = await getSettings(env);
        const mediaKind = body.mediaKind || 'PHOTO';
        const isSticker = mediaKind.startsWith('STICKER');
        const messageKey = `api_scan_${Date.now()}_${Math.floor(Math.random() * 1000)}`;

        // Never mark an empty or unrasterized .tgs Lottie payload as SAFE
        if (!body.base64Data || body.mimeType === 'application/x-tgsticker') {
          const isTgs = body.mimeType === 'application/x-tgsticker';
          const entry = {
            messageKey,
            chatId: 0,
            chatTitle: isSticker ? 'Sticker Scanner' : 'Admin Scanner',
            senderName: 'Admin',
            mediaKind,
            verdict: isTgs ? 'UNCERTAIN' : 'SCAN_FAILED',
            confidence: 0,
            actionTaken: isTgs ? 'FORWARDED_ADMIN' : 'FAILED_SAFE_FALLBACK',
            reason: isTgs
              ? 'Animated .tgs Lottie sticker has no preview frame; marked UNCERTAIN for admin review (never marked SAFE).'
              : 'Empty media payload; scan failed safely without marking SAFE.',
            createdAt: Date.now()
          };
          await insertLog(env, entry);
          return json(entry);
        }

        const decision = await moderateWithGemini(
          env,
          body.base64Data,
          body.mimeType || 'image/jpeg',
          mediaKind,
          settings.sensitivity
        );
        const isViolation = decision.verdict === 'VIOLATION' || decision.verdict === 'SEXUAL_CONTENT';
        const entry = {
          messageKey,
          chatId: 0,
          chatTitle: isSticker ? 'Sticker Scanner' : 'Admin Scanner',
          senderName: 'Admin',
          mediaKind,
          verdict: isViolation ? 'VIOLATION' : decision.verdict,
          confidence: decision.confidence,
          actionTaken: isViolation
            ? 'DELETED'
            : decision.verdict === 'UNCERTAIN'
              ? 'FORWARDED_ADMIN'
              : decision.verdict === 'SCAN_FAILED'
                ? 'FAILED_SAFE_FALLBACK'
                : 'ALLOWED',
          reason: decision.reason,
          createdAt: Date.now()
        };
        await insertLog(env, entry);
        return json(entry);
      }

      if (url.pathname === '/api/chat' && request.method === 'POST') {
        const body = await request.json();
        const reply = await chatWithGemini(env, body.prompt || '');
        return json({ reply });
      }
    }

    return json({ ok: true, service: 'Yuta AI Shared Backend (24/7 Sticker Moderation)' });
  },

  async scheduled(_event, env, ctx) {
    await touchScannerMeta(env);
    ctx.waitUntil(processStickerQueue(env, false));
  }
};

async function handleTelegramMessage(message, updateId, env, ctx) {
  const messageKey = `${message.chat.id}_${message.message_id}`;
  if (env.DB) {
    const existing = await env.DB.prepare('SELECT messageKey FROM processed_messages WHERE messageKey = ?')
      .bind(messageKey)
      .first();
    if (existing) return; // Strict deduplication: prevent duplicate scanning
    await env.DB.prepare('INSERT OR IGNORE INTO processed_messages (messageKey, updateId, processedAt) VALUES (?, ?, ?)')
      .bind(messageKey, updateId, Date.now())
      .run();
  }

  const settings = await getSettings(env);
  const text = (message.text || '').trim();

  if (text.startsWith('/')) {
    const cmd = text.split(/\s+/)[0].split('@')[0].toLowerCase();
    const arg = text.includes(' ') ? text.slice(text.indexOf(' ') + 1).trim() : '';
    let reply = '';
    if (cmd === '/start') {
      reply = '🤖 Yuta AI 24/7 Active! Use /help for commands.';
    } else if (cmd === '/help') {
      reply = '/start\n/help\n/status\n/scan\n/settings\n/chat <question>\n/about';
    } else if (cmd === '/status') {
      const st = await getStickerStats(env);
      reply = `📡 Yuta AI Live\n• Gemini: ${env.GEMINI_MODEL || 'gemini-2.5-flash'}\n• Auto-Delete: ${settings.enableAutoDelete ? 'ON' : 'OFF'}\n• Sticker Queue Pending: ${st.pendingScans} | Failed: ${st.failedScans}`;
    } else if (cmd === '/scan') {
      const s = await getStats(env);
      const st = await getStickerStats(env);
      reply = `📊 Stickers Received: ${st.totalStickersReceived} | Scanned: ${st.totalStickersScanned} | Violations: ${st.violationsDetected} | Deleted: ${st.messagesDeleted} | Pending: ${st.pendingScans} | Failed: ${st.failedScans} | Photos: ${s.totalImagesScanned}`;
    } else if (cmd === '/chat') {
      if (!settings.enableAiChat) {
        reply = '🚫 AI Chat disabled by admin.';
      } else if (!arg) {
        reply = '💬 Please ask a question after /chat (Hindi or English).';
      } else {
        reply = await chatWithGemini(env, arg);
      }
    } else if (cmd === '/about') {
      reply = '🛡 Yuta AI — Zero-budget 24/7 Telegram Sticker & Media Moderation Bot.';
    } else if (cmd === '/settings') {
      const authorized = await isAuthorizedTelegramAdmin(env, message.from?.id, message.chat.id, message.chat.type, settings);
      if (!authorized) {
        reply = '🔒 Unauthorized: Only authorized group admins can view moderation settings.';
      } else {
        reply = `⚙️ Sensitivity: ${settings.sensitivity} | Sticker Mod: ${settings.enableStickerModeration ? 'ON' : 'OFF'} | Image Mod: ${settings.enableImageModeration ? 'ON' : 'OFF'} | Auto-Delete: ${settings.enableAutoDelete ? 'ON' : 'OFF'}`;
      }
    }
    if (reply) {
      await callTelegram(env, 'sendMessage', {
        chat_id: message.chat.id,
        text: reply.slice(0, 4000),
        reply_to_message_id: message.message_id
      });
    }
    return;
  }

  // Primary Feature: Enqueue Telegram Stickers asynchronously to prevent webhook delays
  if (message.sticker && settings.enableStickerModeration) {
    const sticker = message.sticker;
    const stickerFormat = sticker.is_video
      ? 'WEBM_VIDEO'
      : sticker.is_animated
        ? 'TGS_ANIMATED'
        : 'WEBP_STATIC';

    const chatTitle = message.chat.title || message.chat.username || `Chat ${message.chat.id}`;
    const senderName = message.from?.first_name || 'User';
    const now = Date.now();

    if (env.DB) {
      await env.DB.prepare(
        `INSERT OR IGNORE INTO sticker_queue
        (messageKey, chatId, chatTitle, senderName, messageId, stickerFormat, fileId, thumbnailFileId, stickerSetName, stickerEmoji, status, retryCount, maxRetries, lastError, nextAttemptAt, createdAt, updatedAt)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, '', ?, ?, ?)`
      ).bind(
        messageKey,
        message.chat.id,
        chatTitle,
        senderName,
        message.message_id,
        stickerFormat,
        sticker.file_id || '',
        sticker.thumbnail?.file_id || '',
        sticker.set_name || '',
        sticker.emoji || '',
        MAX_RETRIES,
        now,
        now,
        now
      ).run();

      const runner = processStickerQueue(env, false);
      if (ctx?.waitUntil) {
        ctx.waitUntil(runner);
      } else {
        await runner;
      }
    } else {
      await processSingleStickerJob(env, {
        messageKey,
        chatId: message.chat.id,
        chatTitle,
        senderName,
        messageId: message.message_id,
        stickerFormat,
        fileId: sticker.file_id || '',
        thumbnailFileId: sticker.thumbnail?.file_id || '',
        stickerSetName: sticker.set_name || '',
        stickerEmoji: sticker.emoji || '',
        retryCount: 0
      }, settings);
    }
    return;
  }

  // Non-sticker media (Photos & GIF thumbnails)
  let fileId = null;
  let mediaKind = null;
  let mimeType = 'image/jpeg';

  if (message.photo?.length && settings.enableImageModeration) {
    fileId = message.photo[message.photo.length - 1].file_id;
    mediaKind = 'PHOTO';
  } else if (message.animation?.thumbnail?.file_id && settings.enableGifModeration) {
    fileId = message.animation.thumbnail.file_id;
    mediaKind = 'GIF';
  }

  if (!fileId) return;

  const job = async () => {
    const downloaded = await downloadTelegramFile(env, fileId);
    if (!downloaded.ok) {
      await recordScanFailure(env, messageKey, message.chat.id, message.chat.title || 'Group', message.from?.first_name || 'User', mediaKind, downloaded.error, settings);
      return;
    }
    const decision = await moderateWithGemini(env, downloaded.base64Data, downloaded.mimeType || mimeType, mediaKind, settings.sensitivity);
    await finalizeModerationAction(env, {
      messageKey,
      chatId: message.chat.id,
      chatTitle: message.chat.title || 'Group',
      senderName: message.from?.first_name || 'User',
      messageId: message.message_id,
      mediaKind,
      decision,
      settings
    });
  };

  if (ctx?.waitUntil) {
    ctx.waitUntil(job());
  } else {
    await job();
  }
}

async function processStickerQueue(env, includeFailedRetry = false) {
  if (!env.DB) return 0;
  const now = Date.now();
  const settings = await getSettings(env);

  // Batch up to 5 stickers per invocation so we stay safely within Cloudflare Free 50-subrequest limit
  const query = includeFailedRetry
    ? `SELECT * FROM sticker_queue WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAt ASC LIMIT 5`
    : `SELECT * FROM sticker_queue WHERE status = 'PENDING' AND nextAttemptAt <= ? ORDER BY createdAt ASC LIMIT 5`;

  const res = includeFailedRetry
    ? await env.DB.prepare(query).all()
    : await env.DB.prepare(query).bind(now).all();

  const items = res.results || [];
  let handled = 0;

  for (const item of items) {
    await env.DB.prepare(`UPDATE sticker_queue SET status = 'PROCESSING', updatedAt = ? WHERE messageKey = ?`)
      .bind(Date.now(), item.messageKey)
      .run();

    const outcome = await processSingleStickerJob(env, item, settings);
    if (outcome.ok) {
      await env.DB.prepare(`UPDATE sticker_queue SET status = 'DONE', lastError = '', updatedAt = ? WHERE messageKey = ?`)
        .bind(Date.now(), item.messageKey)
        .run();
      handled++;
    } else if (outcome.retryable && (item.retryCount + 1) < (item.maxRetries || MAX_RETRIES)) {
      const nextRetry = item.retryCount + 1;
      const backoffMs = Math.pow(3, nextRetry) * 5000;
      await env.DB.prepare(
        `UPDATE sticker_queue SET status = 'PENDING', retryCount = ?, lastError = ?, nextAttemptAt = ?, updatedAt = ? WHERE messageKey = ?`
      ).bind(nextRetry, outcome.error, Date.now() + backoffMs, Date.now(), item.messageKey).run();
      await recordScannerError(env, outcome.error);
    } else {
      await env.DB.prepare(
        `UPDATE sticker_queue SET status = 'FAILED', retryCount = ?, lastError = ?, updatedAt = ? WHERE messageKey = ?`
      ).bind(item.retryCount + 1, outcome.error, Date.now(), item.messageKey).run();
      await recordScannerError(env, outcome.error);

      await recordScanFailure(
        env,
        item.messageKey,
        item.chatId,
        item.chatTitle,
        item.senderName,
        formatToMediaKind(item.stickerFormat),
        `Sticker scan failed after retries: ${outcome.error}`,
        settings
      );
    }
  }
  return handled;
}

async function processSingleStickerJob(env, item, settings) {
  const mediaKind = formatToMediaKind(item.stickerFormat);

  let targetFileId = '';
  let mimeType = 'image/webp';
  let formatNote = '';

  if (item.stickerFormat === 'WEBP_STATIC') {
    targetFileId = item.fileId;
    mimeType = 'image/webp';
  } else if (item.stickerFormat === 'WEBM_VIDEO') {
    if (item.thumbnailFileId) {
      targetFileId = item.thumbnailFileId;
      mimeType = 'image/jpeg';
      formatNote = 'WEBM video sticker thumbnail frame';
    } else {
      await finalizeModerationAction(env, {
        messageKey: item.messageKey,
        chatId: item.chatId,
        chatTitle: item.chatTitle,
        senderName: item.senderName,
        messageId: item.messageId,
        mediaKind,
        decision: {
          verdict: 'UNCERTAIN',
          confidence: 0.4,
          reason: 'WEBM video sticker has no Telegram preview thumbnail; free Worker cannot decode raw WEBM frames. Forwarded for admin review.'
        },
        settings
      });
      return { ok: true };
    }
  } else if (item.stickerFormat === 'TGS_ANIMATED') {
    if (item.thumbnailFileId) {
      targetFileId = item.thumbnailFileId;
      mimeType = 'image/jpeg';
      formatNote = 'TGS animated sticker preview frame';
    } else {
      await finalizeModerationAction(env, {
        messageKey: item.messageKey,
        chatId: item.chatId,
        chatTitle: item.chatTitle,
        senderName: item.senderName,
        messageId: item.messageId,
        mediaKind,
        decision: {
          verdict: 'UNCERTAIN',
          confidence: 0.4,
          reason: 'Animated .tgs Lottie sticker has no Telegram preview thumbnail; forwarded for admin review (never marked SAFE).'
        },
        settings
      });
      return { ok: true };
    }
  }

  if (!targetFileId) {
    return { ok: false, retryable: false, error: 'Missing sticker file_id.' };
  }

  const downloaded = await downloadTelegramFile(env, targetFileId);
  if (!downloaded.ok) {
    return { ok: false, retryable: downloaded.retryable, error: downloaded.error };
  }

  const decision = await moderateWithGemini(
    env,
    downloaded.base64Data,
    downloaded.mimeType || mimeType,
    mediaKind,
    settings.sensitivity,
    item.stickerEmoji,
    item.stickerSetName
  );

  if (decision.verdict === 'SCAN_FAILED') {
    return { ok: false, retryable: decision.retryable !== false, error: decision.reason };
  }

  if (formatNote) {
    decision.reason = `${formatNote} | ${decision.reason}`;
  }

  await finalizeModerationAction(env, {
    messageKey: item.messageKey,
    chatId: item.chatId,
    chatTitle: item.chatTitle,
    senderName: item.senderName,
    messageId: item.messageId,
    mediaKind,
    decision,
    settings
  });

  return { ok: true };
}

async function finalizeModerationAction(env, { messageKey, chatId, chatTitle, senderName, messageId, mediaKind, decision, settings }) {
  const isViolation = decision.verdict === 'VIOLATION' || decision.verdict === 'SEXUAL_CONTENT';
  let actionTaken = decision.verdict === 'SAFE'
    ? 'ALLOWED'
    : decision.verdict === 'SCAN_FAILED'
      ? 'FAILED_SAFE_FALLBACK'
      : 'FORWARDED_ADMIN';

  if (isViolation && settings.enableAutoDelete) {
    if (chatId > 0) {
      actionTaken = 'FORWARDED_ADMIN';
    } else {
      const canDelete = await checkBotCanDelete(env, chatId);
      if (!canDelete) {
        actionTaken = 'PERMISSION_DENIED';
        await callTelegram(env, 'sendMessage', {
          chat_id: chatId,
          text: '⚠️ Yuta AI detected an explicit sticker/media violation, but lacks Admin "Delete Messages" permission in this group.',
          reply_to_message_id: messageId
        });
        await notifyAdmins(env, settings, `⚠️ Delete Permission Missing in ${chatTitle} for violating ${mediaKind} from ${senderName}.`);
      } else {
        const del = await callTelegram(env, 'deleteMessage', { chat_id: chatId, message_id: messageId });
        if (del.ok) {
          actionTaken = 'DELETED';
          await notifyAdmins(env, settings, `🗑 Deleted violating ${mediaKind} in ${chatTitle} from ${senderName}.\nReason: ${decision.reason}`);
        } else {
          actionTaken = 'PERMISSION_DENIED';
          await notifyAdmins(env, settings, `⚠️ Failed to delete violation in ${chatTitle}: ${del.description}`);
        }
      }
    }
  } else if (decision.verdict === 'UNCERTAIN') {
    await forwardUncertainToAdmin(env, settings, chatId, chatTitle, senderName, messageId, mediaKind, decision.reason);
  }

  await insertLog(env, {
    messageKey,
    chatId,
    chatTitle,
    senderName,
    mediaKind,
    verdict: isViolation ? 'VIOLATION' : decision.verdict,
    confidence: decision.confidence,
    actionTaken,
    reason: decision.reason,
    createdAt: Date.now()
  });
}

async function recordScanFailure(env, messageKey, chatId, chatTitle, senderName, mediaKind, errorReason, settings) {
  await insertLog(env, {
    messageKey,
    chatId,
    chatTitle,
    senderName,
    mediaKind,
    verdict: 'SCAN_FAILED',
    confidence: 0,
    actionTaken: 'FAILED_SAFE_FALLBACK',
    reason: errorReason,
    createdAt: Date.now()
  });
  await notifyAdmins(env, settings, `⚠️ Scan Failed in ${chatTitle} (${mediaKind}). Never marked safe.\nError: ${errorReason}`);
}

async function forwardUncertainToAdmin(env, settings, chatId, chatTitle, senderName, messageId, mediaKind, reason) {
  if (!settings.enableAdminNotifications) return;
  const alertTarget = getAlertChatTarget(env, settings);
  if (!alertTarget) return;
  if (chatId && messageId) {
    await callTelegram(env, 'forwardMessage', {
      chat_id: alertTarget,
      from_chat_id: chatId,
      message_id: messageId
    });
  }
  await callTelegram(env, 'sendMessage', {
    chat_id: alertTarget,
    text: `🧐 Admin Review Requested\n• Group: ${chatTitle}\n• Sender: ${senderName}\n• Media: ${mediaKind}\n• Reason: ${reason}`
  });
}

async function notifyAdmins(env, settings, text) {
  if (!settings.enableAdminNotifications) return;
  const alertTarget = getAlertChatTarget(env, settings);
  if (!alertTarget) return;
  await callTelegram(env, 'sendMessage', { chat_id: alertTarget, text: text.slice(0, 4000) });
}

function getAlertChatTarget(env, settings) {
  if (settings.adminAlertChatId && String(settings.adminAlertChatId).trim()) {
    return String(settings.adminAlertChatId).trim();
  }
  if (env.ADMIN_ALERT_CHAT_ID) return env.ADMIN_ALERT_CHAT_ID;
  const firstId = String(settings.adminUserIds || '').split(/[\s,;]+/)[0]?.trim();
  return firstId || null;
}

async function isAuthorizedTelegramAdmin(env, senderId, chatId, chatType, settings) {
  if (!senderId) return false;
  const allowList = String(settings.adminUserIds || '').split(/[\s,;]+/).filter(Boolean);
  if (allowList.includes(String(senderId))) return true;
  if (chatType === 'private' && allowList.length === 0) return true;
  if (chatType === 'group' || chatType === 'supergroup') {
    const member = await callTelegram(env, 'getChatMember', { chat_id: chatId, user_id: senderId });
    const status = member?.result?.status;
    return status === 'creator' || status === 'administrator';
  }
  return false;
}

async function checkBotCanDelete(env, chatId) {
  const me = await callTelegram(env, 'getMe');
  if (!me.ok || !me.result?.id) return false;
  const member = await callTelegram(env, 'getChatMember', { chat_id: chatId, user_id: me.result.id });
  const res = member?.result;
  if (!res) return false;
  return res.status === 'creator' || (res.status === 'administrator' && res.can_delete_messages === true);
}

async function downloadTelegramFile(env, fileId) {
  try {
    const fileMeta = await callTelegram(env, 'getFile', { file_id: fileId });
    const filePath = fileMeta?.result?.file_path;
    if (!filePath) {
      return { ok: false, retryable: true, error: fileMeta?.description || 'Telegram getFile failed' };
    }
    if ((fileMeta.result.file_size || 0) > MAX_STICKER_BYTES) {
      return { ok: false, retryable: false, error: 'Sticker exceeds 4MB free-Worker safety cap.' };
    }
    const fileRes = await fetch(`https://api.telegram.org/file/bot${env.TELEGRAM_BOT_TOKEN}/${filePath}`);
    if (!fileRes.ok) {
      return { ok: false, retryable: fileRes.status >= 500, error: `File download HTTP ${fileRes.status}` };
    }
    const buffer = await fileRes.arrayBuffer();
    if (buffer.byteLength > MAX_STICKER_BYTES) {
      return { ok: false, retryable: false, error: 'Downloaded file exceeds 4MB Worker cap.' };
    }
    const mimeType = filePath.endsWith('.webp') ? 'image/webp' : filePath.endsWith('.png') ? 'image/png' : 'image/jpeg';
    return { ok: true, base64Data: arrayBufferToBase64(buffer), mimeType };
  } catch (err) {
    return { ok: false, retryable: true, error: err?.message || 'Network download error' };
  }
}

async function moderateWithGemini(env, base64Data, mimeType, mediaKind, sensitivity, stickerEmoji = '', stickerSet = '') {
  if (!env.GEMINI_API_KEY) {
    return {
      verdict: 'SCAN_FAILED',
      retryable: false,
      confidence: 0,
      reason: 'GEMINI_API_KEY secret missing on Cloudflare Worker. Media never marked safe.'
    };
  }
  const model = env.GEMINI_MODEL || 'gemini-2.5-flash';
  const contextHint = stickerEmoji || stickerSet ? ` Sticker metadata: emoji="${stickerEmoji}" set="${stickerSet}".` : '';
  const prompt = `Analyze this Telegram ${mediaKind} frame for sexually explicit, pornographic, or NSFW adult content.${contextHint} Sensitivity: ${sensitivity}. Do NOT classify ordinary educational, medical, anime/cartoon non-sexual, or harmless meme content as a violation. Return strict JSON only: {"verdict":"SAFE|VIOLATION|UNCERTAIN","confidence":0.0,"reason":"short explanation"}.`;

  try {
    const resp = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${env.GEMINI_API_KEY}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{
          parts: [
            { text: prompt },
            { inlineData: { mimeType, data: base64Data } }
          ]
        }],
        safetySettings: [
          { category: 'HARM_CATEGORY_SEXUALLY_EXPLICIT', threshold: 'BLOCK_NONE' },
          { category: 'HARM_CATEGORY_HATE_SPEECH', threshold: 'BLOCK_ONLY_HIGH' },
          { category: 'HARM_CATEGORY_HARASSMENT', threshold: 'BLOCK_ONLY_HIGH' },
          { category: 'HARM_CATEGORY_DANGEROUS_CONTENT', threshold: 'BLOCK_ONLY_HIGH' }
        ],
        generationConfig: {
          responseMimeType: 'application/json',
          thinkingConfig: { thinkingBudget: 0 }
        }
      })
    });

    if (!resp.ok) {
      const retryable = resp.status === 429 || resp.status >= 500;
      return { verdict: 'SCAN_FAILED', retryable, confidence: 0, reason: `Gemini HTTP ${resp.status}` };
    }

    const data = await resp.json();
    const blockReason = data?.promptFeedback?.blockReason;
    const candidate = data?.candidates?.[0];
    if (blockReason || candidate?.finishReason === 'SAFETY') {
      return {
        verdict: 'UNCERTAIN',
        confidence: 0.7,
        reason: `Gemini safety filter triggered (${blockReason || 'SAFETY'}); routed to admin review instead of marking SAFE.`
      };
    }

    const rawParts = candidate?.content?.parts || [];
    const textPart = rawParts.map((p) => p.text || '').join('').trim();
    const cleanedJson = textPart.replace(/^```json\s*/i, '').replace(/```$/i, '').trim();
    const parsed = JSON.parse(cleanedJson || '{}');
    const normalized = parsed.verdict === 'SEXUAL_CONTENT' ? 'VIOLATION' : parsed.verdict;
    return {
      verdict: ['SAFE', 'VIOLATION', 'UNCERTAIN'].includes(normalized) ? normalized : 'UNCERTAIN',
      confidence: Number(parsed.confidence || 0.5),
      reason: String(parsed.reason || 'Analyzed by Gemini.')
    };
  } catch (err) {
    return { verdict: 'SCAN_FAILED', retryable: true, confidence: 0, reason: err?.message || 'Gemini request failed' };
  }
}

async function verifyGeminiKeyMetadata(env) {
  const model = env.GEMINI_MODEL || 'gemini-2.5-flash';
  if (!env.GEMINI_API_KEY) {
    return { ok: false, model, error: 'GEMINI_API_KEY secret missing on Cloudflare Worker.' };
  }
  try {
    const resp = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}?key=${env.GEMINI_API_KEY}`);
    if (resp.ok) {
      return { ok: true, model, error: '' };
    }
    const errJson = await resp.json().catch(() => ({}));
    return { ok: false, model, error: errJson?.error?.message || `Gemini HTTP ${resp.status}` };
  } catch (err) {
    return { ok: false, model, error: err?.message || 'Network error reaching Gemini API' };
  }
}

async function testGeminiLive(env) {
  const model = env.GEMINI_MODEL || 'gemini-2.5-flash';
  if (!env.GEMINI_API_KEY) {
    return { ok: false, model, reply: '', error: 'GEMINI_API_KEY secret missing on Cloudflare Worker.' };
  }
  try {
    const resp = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${env.GEMINI_API_KEY}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ parts: [{ text: 'Reply with single word: OK' }] }],
        generationConfig: { maxOutputTokens: 8, thinkingConfig: { thinkingBudget: 0 } }
      })
    });
    const data = await resp.json().catch(() => ({}));
    if (!resp.ok) {
      return {
        ok: false,
        model,
        reply: '',
        error: data?.error?.message || `Gemini HTTP ${resp.status}`
      };
    }
    const reply = (data?.candidates?.[0]?.content?.parts?.[0]?.text || '').trim();
    return { ok: true, model, reply: reply || 'OK', error: '' };
  } catch (err) {
    return { ok: false, model, reply: '', error: err?.message || 'Gemini connection failed' };
  }
}

async function chatWithGemini(env, prompt) {
  if (!env.GEMINI_API_KEY) return 'GEMINI_API_KEY secret missing on server.';
  const model = env.GEMINI_MODEL || 'gemini-2.5-flash';
  const resp = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${env.GEMINI_API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      systemInstruction: {
        parts: [{ text: 'You are Yuta AI, a helpful bilingual Hindi and English assistant. Be concise and never reveal secrets or API keys.' }]
      },
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: { thinkingConfig: { thinkingBudget: 0 } }
    })
  });
  if (!resp.ok) return `AI service unavailable (HTTP ${resp.status}).`;
  const data = await resp.json();
  return data?.candidates?.[0]?.content?.parts?.[0]?.text || 'No response generated.';
}

async function callTelegram(env, method, payload = null) {
  if (!env.TELEGRAM_BOT_TOKEN) {
    return { ok: false, description: 'TELEGRAM_BOT_TOKEN secret missing on server.' };
  }
  const res = await fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/${method}`, {
    method: payload ? 'POST' : 'GET',
    headers: payload ? { 'Content-Type': 'application/json' } : undefined,
    body: payload ? JSON.stringify(payload) : undefined
  });
  return res.json().catch(() => ({ ok: false, description: `HTTP ${res.status}` }));
}

function formatToMediaKind(stickerFormat) {
  if (stickerFormat === 'WEBM_VIDEO') return 'STICKER_VIDEO';
  if (stickerFormat === 'TGS_ANIMATED') return 'STICKER_ANIMATED';
  return 'STICKER_STATIC';
}

async function touchScannerMeta(env) {
  if (!env.DB) return;
  const now = Date.now();
  await env.DB.prepare(
    `INSERT OR IGNORE INTO scanner_meta (id, startedAt, lastHeartbeatAt, lastError) VALUES (1, ?, ?, '')`
  ).bind(now, now).run();
  await env.DB.prepare(`UPDATE scanner_meta SET lastHeartbeatAt = ? WHERE id = 1`).bind(now).run();
}

async function recordScannerError(env, errMsg) {
  if (!env.DB) return;
  await env.DB.prepare(`UPDATE scanner_meta SET lastError = ?, lastHeartbeatAt = ? WHERE id = 1`)
    .bind(String(errMsg || '').slice(0, 300), Date.now())
    .run();
}

async function getScannerMeta(env) {
  if (!env.DB) return null;
  return env.DB.prepare('SELECT * FROM scanner_meta WHERE id = 1').first();
}

async function getSettings(env) {
  if (!env.DB) {
    return {
      enableImageModeration: true,
      enableStickerModeration: true,
      enableGifModeration: true,
      enableAiChat: true,
      enableAutoDelete: true,
      enableAdminNotifications: true,
      disableMediaRetention: true,
      sensitivity: 'BALANCED',
      adminUserIds: '',
      adminAlertChatId: ''
    };
  }
  const row = await env.DB.prepare('SELECT * FROM bot_settings WHERE id = 1').first();
  if (!row) return {};
  return {
    enableImageModeration: Boolean(row.enableImageModeration),
    enableStickerModeration: Boolean(row.enableStickerModeration),
    enableGifModeration: Boolean(row.enableGifModeration),
    enableAiChat: Boolean(row.enableAiChat),
    enableAutoDelete: Boolean(row.enableAutoDelete),
    enableAdminNotifications: Boolean(row.enableAdminNotifications),
    disableMediaRetention: Boolean(row.disableMediaRetention),
    sensitivity: row.sensitivity || 'BALANCED',
    adminUserIds: row.adminUserIds || '',
    adminAlertChatId: row.adminAlertChatId || ''
  };
}

async function saveSettings(env, s) {
  if (!env.DB) return;
  const existing = await getSettings(env);
  await env.DB.prepare(
    `UPDATE bot_settings SET
      enableImageModeration = ?, enableStickerModeration = ?, enableGifModeration = ?,
      enableAiChat = ?, enableAutoDelete = ?, enableAdminNotifications = ?,
      disableMediaRetention = ?, sensitivity = ?, adminUserIds = ?, adminAlertChatId = ?
     WHERE id = 1`
  ).bind(
    (s.enableImageModeration ?? existing.enableImageModeration) ? 1 : 0,
    (s.enableStickerModeration ?? existing.enableStickerModeration) ? 1 : 0,
    (s.enableGifModeration ?? existing.enableGifModeration) ? 1 : 0,
    (s.enableAiChat ?? existing.enableAiChat) ? 1 : 0,
    (s.enableAutoDelete ?? existing.enableAutoDelete) ? 1 : 0,
    (s.enableAdminNotifications ?? existing.enableAdminNotifications) ? 1 : 0,
    (s.disableMediaRetention ?? existing.disableMediaRetention) ? 1 : 0,
    s.sensitivity || existing.sensitivity || 'BALANCED',
    s.adminUserIds !== undefined ? String(s.adminUserIds) : (existing.adminUserIds || ''),
    s.adminAlertChatId !== undefined ? String(s.adminAlertChatId) : (existing.adminAlertChatId || '')
  ).run();
}

async function getStats(env) {
  if (!env.DB) {
    return {
      totalImagesScanned: 0,
      totalStickersScanned: 0,
      totalVideosAndGifsScanned: 0,
      totalViolationsDetected: 0,
      totalMessagesDeleted: 0,
      totalUncertainOrFailed: 0
    };
  }
  const row = await env.DB.prepare(
    `SELECT
      COALESCE(SUM(CASE WHEN mediaKind = 'PHOTO' THEN 1 ELSE 0 END), 0) AS totalImagesScanned,
      COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND verdict != 'SCAN_FAILED' THEN 1 ELSE 0 END), 0) AS totalStickersScanned,
      COALESCE(SUM(CASE WHEN mediaKind IN ('GIF', 'VIDEO') THEN 1 ELSE 0 END), 0) AS totalVideosAndGifsScanned,
      COALESCE(SUM(CASE WHEN verdict IN ('VIOLATION', 'SEXUAL_CONTENT') THEN 1 ELSE 0 END), 0) AS totalViolationsDetected,
      COALESCE(SUM(CASE WHEN actionTaken = 'DELETED' THEN 1 ELSE 0 END), 0) AS totalMessagesDeleted,
      COALESCE(SUM(CASE WHEN verdict IN ('UNCERTAIN', 'SCAN_FAILED') THEN 1 ELSE 0 END), 0) AS totalUncertainOrFailed
    FROM moderation_logs`
  ).first();
  return row || {};
}

async function getStickerStats(env) {
  if (!env.DB) {
    return {
      totalStickersReceived: 0,
      totalStickersScanned: 0,
      violationsDetected: 0,
      messagesDeleted: 0,
      pendingScans: 0,
      failedScans: 0
    };
  }

  const receivedRow = await env.DB.prepare(
    `SELECT COUNT(*) AS totalStickersReceived FROM (
      SELECT messageKey FROM sticker_queue
      UNION
      SELECT messageKey FROM moderation_logs WHERE mediaKind LIKE 'STICKER%'
    )`
  ).first();

  const queueRow = await env.DB.prepare(
    `SELECT
      COALESCE(SUM(CASE WHEN status IN ('PENDING', 'PROCESSING') THEN 1 ELSE 0 END), 0) AS pendingScans,
      COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedFromQueue
    FROM sticker_queue`
  ).first();

  const logRow = await env.DB.prepare(
    `SELECT
      COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND verdict != 'SCAN_FAILED' THEN 1 ELSE 0 END), 0) AS scannedStickers,
      COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND verdict IN ('VIOLATION', 'SEXUAL_CONTENT') THEN 1 ELSE 0 END), 0) AS violationsDetected,
      COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND actionTaken = 'DELETED' THEN 1 ELSE 0 END), 0) AS messagesDeleted,
      COALESCE(SUM(CASE WHEN mediaKind LIKE 'STICKER%' AND verdict = 'SCAN_FAILED' THEN 1 ELSE 0 END), 0) AS failedLogScans
    FROM moderation_logs`
  ).first();

  return {
    totalStickersReceived: receivedRow?.totalStickersReceived || 0,
    totalStickersScanned: logRow?.scannedStickers || 0,
    violationsDetected: logRow?.violationsDetected || 0,
    messagesDeleted: logRow?.messagesDeleted || 0,
    pendingScans: queueRow?.pendingScans || 0,
    failedScans: Math.max(queueRow?.failedFromQueue || 0, logRow?.failedLogScans || 0)
  };
}

async function getStickerQueue(env) {
  if (!env.DB) return [];
  const res = await env.DB.prepare('SELECT * FROM sticker_queue ORDER BY createdAt DESC LIMIT 50').all();
  return res.results || [];
}

async function getRecentLogs(env) {
  if (!env.DB) return [];
  const res = await env.DB.prepare('SELECT * FROM moderation_logs ORDER BY createdAt DESC LIMIT 100').all();
  return res.results || [];
}

async function insertLog(env, log) {
  if (!env.DB) return;
  await env.DB.prepare(
    `INSERT INTO moderation_logs (messageKey, chatId, chatTitle, senderName, mediaKind, verdict, confidence, actionTaken, reason, createdAt)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(
    log.messageKey, log.chatId, log.chatTitle, log.senderName, log.mediaKind,
    log.verdict, log.confidence, log.actionTaken, log.reason, log.createdAt
  ).run();
}

// Chunked base64 encoder stays well inside Cloudflare Workers Free 10ms CPU time limit
function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  const chunkSize = 0x8000;
  let binary = '';
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...CORS_HEADERS }
  });
}
