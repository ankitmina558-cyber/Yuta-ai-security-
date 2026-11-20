-- Cloudflare D1 (SQLite) Schema for Yuta AI Shared Backend & 24/7 Sticker Moderation Queue
CREATE TABLE IF NOT EXISTS moderation_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  messageKey TEXT NOT NULL,
  chatId INTEGER NOT NULL,
  chatTitle TEXT NOT NULL,
  senderName TEXT NOT NULL,
  mediaKind TEXT NOT NULL,
  verdict TEXT NOT NULL,
  confidence REAL NOT NULL,
  actionTaken TEXT NOT NULL,
  reason TEXT NOT NULL,
  createdAt INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS processed_messages (
  messageKey TEXT PRIMARY KEY,
  updateId INTEGER NOT NULL,
  processedAt INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sticker_queue (
  messageKey TEXT PRIMARY KEY,
  chatId INTEGER NOT NULL,
  chatTitle TEXT NOT NULL,
  senderName TEXT NOT NULL,
  messageId INTEGER NOT NULL,
  stickerFormat TEXT NOT NULL, -- WEBP_STATIC | TGS_ANIMATED | WEBM_VIDEO
  fileId TEXT NOT NULL,
  thumbnailFileId TEXT NOT NULL DEFAULT '',
  stickerSetName TEXT NOT NULL DEFAULT '',
  stickerEmoji TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING | PROCESSING | DONE | FAILED
  retryCount INTEGER NOT NULL DEFAULT 0,
  maxRetries INTEGER NOT NULL DEFAULT 3,
  lastError TEXT NOT NULL DEFAULT '',
  nextAttemptAt INTEGER NOT NULL DEFAULT 0,
  createdAt INTEGER NOT NULL,
  updatedAt INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sticker_queue_status ON sticker_queue(status, nextAttemptAt);

CREATE TABLE IF NOT EXISTS scanner_meta (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  startedAt INTEGER NOT NULL,
  lastHeartbeatAt INTEGER NOT NULL,
  lastError TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS bot_settings (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  enableImageModeration INTEGER NOT NULL DEFAULT 1,
  enableStickerModeration INTEGER NOT NULL DEFAULT 1,
  enableGifModeration INTEGER NOT NULL DEFAULT 1,
  enableAiChat INTEGER NOT NULL DEFAULT 1,
  enableAutoDelete INTEGER NOT NULL DEFAULT 1,
  enableAdminNotifications INTEGER NOT NULL DEFAULT 1,
  disableMediaRetention INTEGER NOT NULL DEFAULT 1,
  sensitivity TEXT NOT NULL DEFAULT 'BALANCED',
  adminUserIds TEXT NOT NULL DEFAULT '',
  adminAlertChatId TEXT NOT NULL DEFAULT ''
);

INSERT OR IGNORE INTO bot_settings (id) VALUES (1);
