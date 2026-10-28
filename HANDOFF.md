# HANDOFF: dismov course project

## Objective
**Update 2026-10-28:** the professor asked the user to present Testigo at the symposium, around Nov 9-10, 2026. The date comes from a Google search and must be confirmed with the professor. The goal now is not to grow the app but to make sure it reliably does what it says, within the agreed scope: more testing, plus a review of the interface and the architecture.

Professor's only instruction: "app y widget que empiece a grabar con comando de voz".
He doesn't expect extras. One alternative to voice (a button) is fine. It must work flawlessly in a live demo.

## Constraints
- Stack from the course book: *El gran libro de Android* (Tomás Gironés, 8th ed., 2020, Android 11).
  Native Android, Android Studio, XML layouts (Views, no Compose).
- APIs outside the book are allowed if they're standard Android (AppWidget, SpeechRecognizer).
- UI copy and docs in Spanish. Identifiers in English.

## Decisions (user, 2026-10-27)
- Language: **Java**.
- What it records: **audio** (`MediaRecorder`, book 6.7).
- Device: **realme GT 6, Android 16**. realme UI kills background apps aggressively, so battery restrictions must be lifted.
- Trigger phrase: **the user chooses it in the app**. Each word must exist in the Vosk vocabulary.
- After a recording stops: **protection mode keeps listening**.
- Solo project.

## Purpose (user, 2026-10-27)
This is not "a quick way to record". The goal is to **start audio recording for safety reasons**: the user may be in danger and unable to touch the phone. So hands-free listening is the core feature, not an extra.

## Structure v2 (approved 2026-10-27, implemented)
- **Protection mode:**
  - A foreground service (type `microphone`) listens all the time for one trigger phrase.
  - Recognition is offline, using Vosk (`vosk-model-small-es-0.42`, 39 MB) restricted to a grammar with just that phrase.
  - Works with the screen off and the phone locked. No audio leaves the phone.
  - It is turned on from the widget or the app.
- **Trigger:**
  - Hearing the phrase stops listening, starts `MediaRecorder` and gives one short vibration.
  - The notification and the widget switch to "Grabando".
- **Manual alternative:** one tap on the widget's "Grabar" button.
- **Stop:** from the notification (needs the phone unlocked), the widget or the app. No stopping by voice, on purpose.
- **Crash safety:** the recording is saved as it goes, so audio survives if the phone dies (format to verify).
- **App:**
  - Requests permissions.
  - Toggles protection mode and has a record button.
  - Lists recordings with playback.
- **Dropped from v1:** the tap-then-speak window and `SpeechRecognizer`, because they need hands and depend on Google.

## Verified facts (developer.android.com, alphacephei.com)
- A user tap on a widget is an allowed way to start a foreground service from the background, and the service keeps microphone access afterwards.
- Since Android 14, the phone can't start a microphone service by itself after a reboot, so protection mode must be turned on again after rebooting.
- vosk-model-small-es-0.42 (39 MB) is marked by its authors as suitable for Android.

## Build environment
- `project/` is the Android Studio project: Java, XML layouts, AGP 9.4.1, Gradle 9.8.2.
- Build with Android Studio's JDK: `JAVA_HOME=/opt/android-studio/jbr ./gradlew ...`. The system JDK is 26, which is too new for this Gradle.
- SDK: compileSdk 37, targetSdk 36, minSdk 30 (Android 11, the book's version).
- Libraries: Vosk `com.alphacephei:vosk-android:0.3.75`.
- Voice model: `scripts/download-model.sh` puts it in `app/src/main/assets/model-es/`. It's gitignored, and the script checks its SHA-256.
- Only arm64-v8a and x86_64 binaries are included. Debug APK is about 77 MB.

## Status
- Branch `feat/voice-recorder`.
- Implement phase **done**.
- Verified on this machine:
  - It compiles.
  - 11 unit tests pass, including one that reads the real model's vocabulary (100,005 words).
  - `lintDebug` reports 0 errors. 9 harmless warnings: targetSdk 36 vs 37, the "momento" spellcheck, plurals, the monochrome icon, and similar.
- **NOT verified (nothing has run on a device yet):**
  - Anything at runtime: listening, recording, the widget, notifications, vibration, the screen-off behavior on realme, battery use.
- Things to tune or check in the Validate phase:
  - `Hypothesis.MIN_CONFIDENCE`: starts at 0.5. The device log line "Escuchado: ... confianza=" gives real values to tune it.
  - Vibration with the screen off. It uses USAGE_ALARM so it isn't ignored in the background.
  - Whether realme kills the service, with and without the battery exemption.
  - Whether ADTS files can still be played after the app is force-stopped mid-recording.
  - The first launch copies the model (58 MB) to storage. How long does it take?

## Symposium hardening (2026-10-28): Research done, Plan waiting for approval
- Decisions (2026-10-28):
  - Plan approved.
  - Workstream B starts now, offline, on `refactor/protection-controller`.
  - The emulator waits for a good connection (laptop or home PC).
  - **Private GitHub repo** created: https://github.com/Aselori/dismov. main and feat/voice-recorder are pushed.
  - **Hide the secret phrase** in the status line and the text field (reveal button while editing).
  - Presentation format: still unknown.
- **Progress (2026-10-28, branch `refactor/protection-controller`, not pushed):**
  - B done (`c75a4de`):
    - `ProtectionController` (plain Java) holds all the listen/record/retry logic.
    - The service is now a thin adapter: `VoskEars` wraps Vosk, `AudioRecorder` wraps MediaRecorder.
    - 28 controller tests; 39 tests in total, all passing.
    - Two deliberate breaks of the controller (mutants) were caught: 4 failures and 1 failure.
    - A notification "Protección detenida, toca para reactivar" now appears when the system restarts the service.
  - Phrase hidden:
    - The status no longer shows it.
    - The field uses dots with a reveal toggle.
    - A successful test doesn't repeat it.
  - Lint: 0 errors.
  - **NOT verified on a device or emulator:** neither change has run yet. Both need to be checked at runtime in A/F.
- Start-on-boot option: **dropped** (2026-10-28). The professor agrees it doesn't make sense. The stash was deleted.
- Emulator: KVM works, emulator 37.1.11 is installed. The SDK command-line tools and an Android 16 image (`system-images;android-36;google_apis;x86_64`) are missing. Installing them needs user approval.
- Test audio: a PipeWire null sink plus `PULSE_SOURCE=<sink>.monitor` when launching the emulator feeds WAV files into the emulator's microphone without changing the system's default input.
- The native libraries (libvosk, libjnidispatch) are 16 KB page aligned (0x4000). OK.
- Host tools: no espeak-ng or piper; ffmpeg is available. Positive samples must be the user's own recordings.
- Workstreams:
  - A. Emulator plus a WAV-to-microphone route.
  - B. `ProtectionController` (plain Java) with tests for every transition.
  - C. Recognition evaluation: hit rate, delay, false triggers per hour, threshold tuning.
  - D. Interface review: every state, light/dark, large text, TalkBack, first-time setup order, hidden phrase.
  - E. Test protocol and results, in Spanish, in `docs/`.
  - F. Validation on the phone.
- Order: A, then B, then C and D, then E. F when the phone is available.

## Next steps (phone validation, later)
1. Over USB: `./gradlew installDebug`, then `adb logcat -s ProtectionService PhraseTester VoiceModel AndroidRuntime`.
2. Checklist: screen off, in a pocket, noise false positives, stop paths, back to listening, force-stop then play the file, mic busy during a call, battery after 1 h, share and delete.
