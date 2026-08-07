# Jarvis — an AI assistant for Android

A native Android app (Kotlin + Jetpack Compose) that powers a voice/text
assistant able to control the phone (open apps, set alarms/timers, search
the web, draft texts, dial numbers, toggle the flashlight, add calendar
events, navigate). The AI backend is pluggable — pick one in Settings:

- **On-device (offline)** — runs an LLM entirely on the phone via
  [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). No server, no
  API key, no internet required once a model is imported. Supports
  device-control tool calling.
- **Ollama server** — sends chat requests to an [Ollama](https://ollama.com)
  instance running elsewhere on your network (a PC, a homelab box, …).
  Faster/better models than a phone can run locally, at the cost of needing
  a reachable server; no device-control tool calling on this path.

## How it works

| Piece | Implementation |
|---|---|
| On-device LLM | LiteRT-LM Kotlin API, `com.google.ai.edge.litertlm:litertlm-android` — `ai/LiteRtChatBackend.kt` |
| Ollama backend | OkHttp streaming client against `/api/chat` — `ai/OllamaChatBackend.kt` |
| Chat UI | Jetpack Compose, streaming responses |
| Voice in | `android.speech.SpeechRecognizer`, on-device recognition preferred |
| Voice out | `android.speech.tts.TextToSpeech` |
| Wake word ("Jarvis, …") | Best-effort background listener, `voice/WakeWordService.kt` |
| Device control | Kotlin functions annotated `@Tool`/`@ToolParam`, called automatically by the on-device model — `tools/JarvisTools.kt` |

Source layout:

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt          # permissions, file picker, hosts ChatScreen/SettingsScreen
├── JarvisApplication.kt     # notification channel setup
├── ai/
│   ├── ChatBackend.kt          # common interface the ViewModel talks to
│   ├── LiteRtChatBackend.kt    # on-device LiteRT-LM implementation
│   └── OllamaChatBackend.kt    # remote Ollama server implementation
├── tools/JarvisTools.kt     # device-control @Tool functions (on-device backend only)
├── voice/                   # SpeechToText, TextToSpeechManager, WakeWordService
├── model/                   # ChatMessage, ModelRepository, BackendSettings
└── ui/                      # ChatViewModel, ChatScreen, SettingsScreen, theme
```

## Get a model

Model weights are large (hundreds of MB–a few GB) so they are **not**
committed to this repo (see `.gitignore`) or bundled in the APK. Download a
`.litertlm` file from [huggingface.co/litert-community](https://huggingface.co/litert-community)
and get it onto the device one of two ways:

1. **In-app import (recommended):** launch the app, tap **Choose model
   file**, and pick the `.litertlm` file (e.g. from your Downloads folder).
   It's copied into app-private storage.
2. **adb push:**
   ```
   adb shell mkdir -p /data/local/tmp/jarvis
   adb push Gemma3-1B-IT.litertlm /data/local/tmp/jarvis/model.litertlm
   ```

Recommended starting model: **Gemma3-1B-IT** (fast, good general chat on a
phone). For the best device-control behavior, a tool-calling-tuned model
such as **FunctionGemma** works well since JarvisTools relies on the model
reliably emitting tool calls.

## Using Ollama instead

If you'd rather point Jarvis at a model running on a real computer:

1. On that machine, install [Ollama](https://ollama.com/download), pull a
   model (`ollama pull llama3.2`), and make sure it's reachable from your
   phone — by default Ollama only listens on localhost, so start it with
   `OLLAMA_HOST=0.0.0.0 ollama serve` (or set that env var permanently) to
   accept connections from other devices on your LAN, and check your
   firewall allows port 11434.
2. In Jarvis, open **Settings** (gear icon), choose **Ollama server**, enter
   the server's address (`http://<lan-ip>:11434`) and a model name, and tap
   **Fetch models from server** to confirm it's reachable and pick from what
   you've pulled.
3. Tap **Save**.

Ollama traffic is plain HTTP by default (no TLS), which is allowed via
`res/xml/network_security_config.xml` — only use this over a network you
trust (home LAN, VPN), not the open internet.

## Build & run

Requires Android Studio (Koala+) or the command line with Android SDK 35 +
JDK 17 installed.

```
git clone <this repo>
cd Jarvis
./gradlew installDebug   # or open in Android Studio and hit Run
```

Minimum SDK 26 (Android 8.0). For the on-device backend, a physical device
with a modern Snapdragon/Tensor/Exynos chip is strongly recommended — LLM
inference is slow or won't fit in memory on low-end devices/emulators. GPU
acceleration is used when available (`Backend.GPU()` in
`LiteRtChatBackend`), falling back to CPU. The Ollama backend has no such
requirement since inference runs on the server.

CI builds a debug APK automatically on every push — see
`.github/workflows/build-apk.yml` — and publishes it both as a workflow
artifact and as a GitHub Release asset tagged `apk-build-<run number>`.

## Permissions

- `RECORD_AUDIO` — voice input (requested on first launch)
- `POST_NOTIFICATIONS` — required for the wake-word foreground service (Android 13+)
- `INTERNET` — used to let you import a model file you're downloading, and/or to talk to an Ollama server if you choose that backend; the on-device backend itself does no networking
- No `SEND_SMS` / `CALL_PHONE` — texting and calling tools open the SMS composer / dialer pre-filled and wait for the user to tap send/call, so the model can never message or ring someone unattended

## Wake word

Tap the mic-with-waves icon in the top bar to start a background service
that repeatedly runs short speech-recognition passes and checks each
transcript for "Jarvis". This is a simple restart-loop built on the stock
`SpeechRecognizer`, not a low-power hotword engine — it costs battery while
enabled, so it's opt-in.

## Extending device control

Add a new capability by adding a method to `JarvisTools`:

```kotlin
@Tool(description = "What this does, in plain language for the model.")
fun myAction(
    @ToolParam(description = "What this argument means.") arg: String
): String {
    // do the thing, e.g. via an Intent
    return "Confirmation message spoken/shown back to the user."
}
```

The model decides when to call it based on the conversation — no extra
wiring needed.
