# Car Welcome (Android Auto)

An Android Auto media app. When your phone connects to the car it says
"Welcome Najib" through the car speakers, then plays the Quran. Surahs are
listed on the car screen in four tabs (1–30, 31–60, 61–90, 91–114). Tapping one
plays it and continues with the next surahs. Voice requests like "play Surah
Yasin in Car Welcome" also work.

## On the car screen while a surah plays
Android Auto shows the surah name, a progress bar with elapsed and remaining
time, play/pause, next/previous, and a **Stop** button. Stop ends playback and
clears the queue; pressing play afterwards starts your journey surah again.

## Phone settings screen
Open "Car Welcome" on the phone to change the greeting text, pick the
**Surah to play when you start driving** (or keep "Continue where I left off"), choose the reciter
(Alafasy, Abdul Basit, Al-Husary, Al-Sudais), test the greeting, and turn
auto-play on or off.

## Build the APK
1. Install Android Studio and open this folder.
2. Let it sync, then choose Build > Build App Bundle(s) / APK(s) > Build APK(s).
   Or run `./gradlew assembleRelease` from a terminal.
3. The APK is at `app/build/outputs/apk/release/app-release.apk`. It is signed
   with the debug key, which is fine for installing on your own phone.

## Install and set up
1. Copy the APK to the phone and open it (allow "install unknown apps").
2. Android Auto settings: tap **Version** 10 times to unlock developer mode,
   then three-dot menu > **Developer settings** > tick **Unknown sources**.
   Without this, Android Auto hides apps that didn't come from the Play Store.
3. In Android Auto settings, turn on the option to start media automatically
   when connected.
4. In the car, open the Android Auto app launcher, pick **Car Welcome** and play a
   surah once. After that Android Auto resumes it on connect.

## How the greeting works
Android Auto doesn't let any app bring itself to the front on connect. The
app greets whenever Android Auto connects to it, which happens when Android Auto
resumes it as the last media app or when you open it. It greets at most once
every 10 minutes, and holds the surah until the greeting ends.

## Notes
- Audio streams from mp3quran.net, and falls back to cdn.islamic.network
  (Alafasy) if a server fails. It needs mobile data in the car.
- YouTube is not part of this app. Google is bringing YouTube video while
  parked to Android Auto itself (Android 17+, compatible cars).

## Download a built APK
Every push to `main` builds the APK on GitHub Actions. Open the **Actions** tab,
pick the latest "Build APK" run, and download `car-welcome-apk` from Artifacts.
