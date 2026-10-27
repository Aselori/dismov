# HANDOFF: dismov course project

## Objective
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

## Next steps (Validate phase, on the realme GT 6 over USB)
1. User:
   - Turns on Developer options and USB debugging.
   - Connects the phone.
   - Accepts the RSA prompt.
   - Checks it shows up in `~/Android/Sdk/platform-tools/adb devices`.
2. Install: `./gradlew installDebug`. Then watch the log with `adb logcat -s ProtectionService PhraseTester VoiceModel AndroidRuntime`.
3. Checklist:
   - Permissions flow.
   - Save the phrase and test it.
   - Protection from the app and from the widget.
   - The trigger with the screen off and the phone in a pocket.
   - Noise false positives: about 10 minutes of normal talk or TV.
   - Stopping from the widget, from the notification (lock screen asks to unlock) and from the app.
   - Back to listening after stopping.
   - Manual record.
   - Force-stop during a recording, then play the file.
   - Mic busy (during a call).
   - Battery after 1 hour of protection.
   - Share and delete a recording.
