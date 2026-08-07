# Jarvis — an offline AI assistant for Android

A native Android app (Kotlin + Jetpack Compose) that runs an LLM **entirely
on-device** — no server, no API key, no internet required once set up — and
uses it to power a voice/text assistant that can also control the phone
(open apps, set alarms/timers, search the web, draft texts, dial numbers,
toggle the flashlight, add calendar events, navigate).

## How it works

| Piece | Implementation |
|---|---|
| On-device LLM | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (Google AI Edge) Kotlin API, `com.google.ai.edge.litertlm:litertlm-android` |
| Chat UI | Jetpack Compose, streaming responses |
| Voice in | `android.speech.SpeechRecognizer`, on-device recognition preferred |
| Voice out | `android.speech.tts.TextToSpeech` |
| Wake word ("Jarvis, …") | Best-effort background listener, `voice/WakeWordService.kt` |
| Device control | Kotlin functions annotated `@Tool`/`@ToolParam`, called automatically by the model — `tools/JarvisTools.kt` |

Source layout:

```
app/src/main/java/com/jarvis/assistant/
├── MainActivity.kt          # permissions, file picker, hosts ChatScreen
├── JarvisApplication.kt     # notification channel setup
├── ai/JarvisEngine.kt       # Engine/Conversation lifecycle wrapper
├── tools/JarvisTools.kt     # device-control @Tool functions
├── voice/                   # SpeechToText, TextToSpeechManager, WakeWordService
├── model/                   # ChatMessage, ModelRepository (model file management)
└── ui/                      # ChatViewModel, ChatScreen, theme
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

## Build & run

Requires Android Studio (Koala+) or the command line with Android SDK 35 +
JDK 17 installed.

```
git clone <this repo>
cd Jarvis
./gradlew installDebug   # or open in Android Studio and hit Run
```

> This repo's Gradle wrapper JAR isn't checked in (binary file). Opening the
> project in Android Studio will fetch it automatically on first sync; from
> the command line, run `gradle wrapper --gradle-version 8.9` once with a
> local Gradle install to generate `gradle/wrapper/gradle-wrapper.jar`.

Minimum SDK 26 (Android 8.0). A physical device with a modern Snapdragon/
Tensor/Exynos chip is strongly recommended — on-device LLM inference is slow
or won't fit in memory on low-end devices/emulators. GPU acceleration is
used when available (`Backend.GPU()` in `JarvisEngine`), falling back to
CPU.

## Permissions

- `RECORD_AUDIO` — voice input (requested on first launch)
- `POST_NOTIFICATIONS` — required for the wake-word foreground service (Android 13+)
- `INTERNET` — only used to let you import a model file you're downloading; inference itself is fully offline
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
