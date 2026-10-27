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
- Device: **realme GT 6**, on the latest Android version it receives. realme UI kills background apps aggressively, so battery restrictions must be lifted.
- Trigger phrase: **the user chooses it in the app**. Each word must exist in the Vosk vocabulary.
- After a recording stops: **protection mode keeps listening**.
- Solo project.

## Purpose (user, 2026-10-27)
This is not "a quick way to record". The goal is to **start audio recording for safety reasons**: the user may be in danger and unable to touch the phone. So hands-free listening is the core feature, not an extra.

## Structure v2 (decisions taken, waiting for the go-ahead to implement)
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

## Folder plan
- Move `book/` to `materials/`.
- Put the Android Studio project in `project/`.
- Create a Git repo at `dismov/`. Use `.gitignore` to keep the PDF out, since it's 150 MB and copyrighted.

## Status
- Plan phase, v2 after the change of purpose. Not yet done:
  - Structure approval.
  - Book move and Git setup.
- Nothing implemented.

## Next steps
1. User approves or adjusts the structure and says which phone and Android version they have.
2. Implement phase:
   - Move the book.
   - Set up Git.
   - Create the Android project (Java, XML).
   - Build the service, then the listening activity, then the widget, then the main screen.
3. Validate phase: test on the real phone over USB debugging, using the checklist in the plan.
