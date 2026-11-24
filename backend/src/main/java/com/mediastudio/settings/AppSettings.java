package com.mediastudio.settings;

import java.nio.file.Path;

/** User-editable settings, persisted to ~/.mediastudio/settings.json. */
public class AppSettings {
    /** Where projects, assets and renders live. */
    public String workspaceDir = Path.of(System.getProperty("user.home"), "MediaStudio").toString();

    // --- TikTok (Login Kit for Desktop + Content Posting API) ---
    /** From developers.tiktok.com → your app. */
    public String tiktokClientKey = "";
    public String tiktokClientSecret = "";
    /** Request video.upload (send to TikTok inbox as draft). */
    public boolean tiktokScopeUpload = true;
    /** Request video.publish (direct post; app must be approved for this scope). */
    public boolean tiktokScopePublish = false;
    /** PKCE code_challenge encoding. TikTok's desktop doc says hex SHA-256; "base64url" is RFC 7636. */
    public String tiktokPkceEncoding = "hex";

    // --- Local tools ---
    public String ffmpegPath = "ffmpeg";
    public String ffprobePath = "ffprobe";
    public String claudePath = "claude";
    /** Optional model override for Claude CLI (empty = CLI default). */
    public String claudeModel = "";
    /** Model for image analysis + storyboard writing (quality matters most here). Empty = claudeModel. */
    public String claudeModelWriting = "opus";
    /** Model of the chat assistant that drives the app (empty = claudeModel). */
    public String claudeModelAssistant = "sonnet";
    /** Telegram bot (from @BotFather) that talks to the assistant; empty = off. Env TELEGRAM_BOT_TOKEN wins. */
    public String telegramBotToken = "";
    /** Telegram user ids allowed to use the bot, comma separated. Env TELEGRAM_ALLOWED_IDS is added. */
    public String telegramAllowedIds = "";
    /** Node.js executable that runs the assistant's app tool (MCP server). */
    public String nodePath = "node";
    /** Which CLI does the writing jobs (TikTok caption, image analysis, storyboard): "claude" or "agy". */
    public String aiWriting = "claude";
    /** Antigravity CLI executable and model (see `agy models`). */
    public String agyPath = "agy";
    public String agyModel = "gemini-3.8-flash-high";

    // --- Voice provider: "vieneu" (VieNeu-TTS, Vietnamese) or "voicebox" ---
    public String voiceProvider = "vieneu";

    // --- VieNeu-TTS 3.x (OpenAI-compatible server: python -m apps.openai_speech) ---
    public String vieneuUrl = "http://127.0.0.1:8100";
    /** python.exe of the venv where `vieneu` is installed; used to auto-start the server. */
    public String vieneuPython = "";
    public boolean vieneuAutoStart = true;
    /** Preset or enrolled voice name (empty = VieNeu default "Hải Đăng"). */
    public String vieneuVoice = "";
    /** auto, cpu, cuda */
    public String vieneuDevice = "auto";

    /** Narration tempo for every provider (0.5–2.0, 1 = unchanged); applied with ffmpeg atempo, pitch kept. */
    public double voiceSpeed = 1.0;

    // --- Voicebox (jamiepine/voicebox) ---
    public String voiceboxUrl = "http://127.0.0.1:17493";
    public String voiceProfileId = "";
    /** Voicebox languages: en, zh, ja, ko, de, fr, ru, pt, es, it, ... (no "vi" yet). */
    public String voiceLanguage = "en";
    /** Qwen model size: 1.7B, 0.6B (empty = Voicebox default). First use downloads the model. */
    public String voiceModelSize = "";
    public boolean enableVoiceboxMcp = true;

    // --- Canva (remote MCP, OAuth via Claude CLI /mcp) ---
    public boolean enableCanva = true;
    public String canvaMcpUrl = "https://mcp.canva.com/mcp";

    // --- Google Flow (browser automation through Playwright MCP) ---
    public boolean enableFlow = true;
    /**
     * Chrome extension: also attach reference images (style anchor + characters) in Flow. Off = prompt only, which is
     * much faster; the prompt then carries every character's full description so scenes still match.
     */
    public boolean flowExtUseRefs = false;
    /**
     * How the "Flow" buttons make pictures: "api" = the local image service (POST {flowApiUrl}/api/v1/generate),
     * "extension" = the Chrome extension queue.
     */
    public String flowEngine = "api";
    /** Image service: delete a project's Flow project once all its pictures are on disk (keeps Flow light). */
    public boolean flowApiDeleteWhenDone = true;
    public String flowApiUrl = "http://localhost:8080";
    /** Minimum wait for one generate call; the client waits max(this, 120, prompts × 60) s (service recommendation). */
    public int flowApiTimeoutSec = 120;
    /** Prompts sent per generate call. */
    public int flowApiBatch = 4;
    /** Chrome extension: seconds to wait between scenes (a short random extra is added). */
    public int flowExtPauseSeconds = 5;

    /**
     * PostgreSQL shared by every computer (e.g. a free Neon / Supabase database), as its connection string
     * postgresql://user:password@host/db. Holds post names and states only (no pictures) to warn about duplicate
     * topics; the schema is created by the migrations in db/migration. Empty = off. Secret.
     */
    public String syncDbUrl = "";
    /** Name shown to the other computers. Empty = this computer's host name. */
    public String machineName = "";

    /** Let phones / other devices on the same Wi-Fi open this app (needs the PIN). Off = this computer only. */
    public boolean lanAccess = false;
    /** PIN other devices must enter once; generated automatically when LAN access is switched on. */
    public String lanPin = "";
    public String flowUrl = "https://labs.google/fx/tools/flow";
    public String playwrightMcpPackage = "@playwright/mcp@latest";
    /** Browser channel passed to Playwright MCP --browser (chrome, msedge, chromium). */
    public String playwrightBrowser = "chrome";
    /** Persistent profile so the Google login survives between runs. Empty = <workspace>/browser-profile. */
    public String browserProfileDir = "";
    /** Path to chrome.exe used by "open Flow to sign in". Empty = auto-detect. */
    public String chromePath = "";

    public Path workspace() {
        return Path.of(workspaceDir);
    }

    public Path browserProfile() {
        return browserProfileDir == null || browserProfileDir.isBlank()
                ? workspace().resolve("browser-profile")
                : Path.of(browserProfileDir);
    }
}
