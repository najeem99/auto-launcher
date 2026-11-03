# Najeem Drive (Android Auto dashboard)

A launcher-style home screen for Android Auto. When you open it on the car
screen it greets you like an assistant ("Good evening, Najeem. Welcome back.
It's 27 degrees and partly cloudy outside.") and then recites **Dua al-Safar**.
That is the only audio the app plays.

The design reference is the HTML mockup published as "Najeem Car Dashboard".

## What's on the car screen
- **Big clock** with the date, and a greeting that follows the time of day.
- **Weather card** with the outside temperature for your location (Open-Meteo, no account needed).
- **Dua al-Safar card** that follows the recitation line by line (Arabic plus transliteration).
- **Buttons** (top right, drawn by Android Auto):
  - **Dua**: recite Dua al-Safar; tap again to stop.
  - **Map**: Home, Work, Nearest mosque, Petrol station, Coffee. Opens Google Maps navigation.
  - **Claude**: opens the Claude app on your phone (Claude has no Android Auto app), for use when parked.

## How it's built
Kotlin, Android for Cars App Library 1.4. The app registers as a *navigation*
car app, which is the only app type that gets a full screen to draw on, so the
dashboard is drawn freely to match the mockup. That works when you install the
APK yourself; the Play Store would not accept it as a navigation app.

Voice uses Android's text-to-speech. For Arabic, install the Arabic voice in
your phone's text-to-speech settings; without it, the English meaning is read.

## Get the APK
Every push builds on GitHub Actions. Open the **Actions** tab, pick the latest
"Build APK" run for your branch, and download `car-welcome-apk` from Artifacts.

## Install and set up
1. Install the APK on the phone and open **Najeem Drive** once. Allow location.
2. Android Auto settings: tap **Version** 10 times to unlock developer mode,
   then three-dot menu > **Developer settings** > tick **Unknown sources**.
3. In the car, open the Android Auto app launcher and pick **Najeem Drive**.

Android Auto does not let apps open themselves when the phone connects, so
open it from the launcher. The welcome plays at most once every 10 minutes.
