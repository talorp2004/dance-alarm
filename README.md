# 💃 Dance Alarm

An Android morning alarm that won't shut up until you dance.

- Plays a **random song from your playlist** (pick songs or a whole folder) at full alarm volume.
- Takes over the screen, even on the lock screen; back does nothing, and leaving brings it straight back.
- Uses the camera + on-device pose tracking (Google ML Kit) to check that **both your arms and legs** are moving. Fill the meter to turn the alarm off.

## Install
Download `DanceAlarm.apk` from the [latest release](../../releases/tag/latest) on your phone, open it, and allow "install unknown apps" when asked.

## Build
Every push to `main` is built by GitHub Actions and published to the `latest` release.
Locally: open in Android Studio, or run `./gradlew assembleRelease`.
