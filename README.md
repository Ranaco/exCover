<div align="center">

<img src="docs/logo.png" width="112" alt="exCover logo" />

# exCover

**Animated GIF wallpapers for your Motorola Razr's cover screen.**

The Razr has a great outside screen, and Motorola only lets you put a photo on it.
exCover puts animated GIFs there instead, from your gallery, GIPHY or any link, on the cover
home screen, the cover lock screen and the main screen. On the cover always-on display, the same
lock-screen GIF stays animated behind a heavy dimming layer.

![Android](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)
![Razr](https://img.shields.io/badge/Motorola-Razr-5C2D91)
![No root](https://img.shields.io/badge/root-not%20required-0A84FF)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

<img src="docs/cover-showcase.gif" width="80%" alt="Animated GIFs on the Razr 50 Ultra cover lock screen and home screen" />

<sub>Cover lock screen and home screen on a Razr 50 Ultra. GIFs from GIPHY: "anime aesthetics" by animatr and "Game Illustration".</sub>

<img src="docs/screenshot-main.jpg" width="24%" alt="exCover with the recent GIFs carousel" />
<img src="docs/screenshot-edit.jpg" width="24%" alt="Framing a GIF in the editor" />
<img src="docs/screenshot-giphy.jpg" width="24%" alt="Searching GIPHY inside exCover" />
<img src="docs/screenshot-set.jpg" width="24%" alt="Choosing the cover or main screen" />

</div>

> [!NOTE]
> **Samsung Flip owners:** your phone already does this out of the box. Congratulations, please
> stop gloating. If you still want exCover on a Flip, I'll port it the moment I get my hands on
> one. Donated Galaxy Z Flips are accepted gratefully, for purely scientific purposes.

## Features

- GIFs stay animated on the cover home and lock screens, not a still frame.
- The cover always-on display keeps the lock-screen GIF animated, heavily dimmed behind its clock.
  This naturally uses more battery than a static or fully black AOD.
- Find GIFs in Photos, search GIPHY inside the app, or paste any GIF link.
- Frame them in a full-screen editor with pinch and drag. The cover screen and the taller main
  screen each get their own framing.
- Swipe between your last five GIFs. Favourites stay in the list for good.
- Set cover home, cover lock, main home and main lock separately, each with its own GIF.
- Save a GIF to Photos or delete it from the list. Once imported, everything works offline.
- Frames are decoded off the main thread and drawn on the GPU, and the wallpaper pauses while the
  screen is off.
- No root, no ADB, no account, no tracking.

## Install

1. Download the latest `exCover.apk` from **[Releases](../../releases)**.
2. Open it on your phone and allow installing from your browser or file manager when asked.
3. Open **exCover**.

Tested on a **Motorola Razr 50 Ultra** (Android 17). Other recent Razrs with the same cover-screen
software should work. If yours does or doesn't, [open an issue](../../issues) so the list can grow.

| Model | Cover home | Cover lock | Cover AOD | Inner AOD |
| --- | --- | --- | --- | --- |
| Razr 50 Ultra (Android 17) | Works | Works | Dimmed animated GIF | Motorola monochrome only |
| Razr 50 (Android 16) | Works (Digital design) | Not possible yet | Not tested | Motorola-controlled |

The cover lock screen depends on Motorola's lock screen app, and it changed with Android 17. On
Android 16 it only offers photos in the lock screen's Wallpaper row, for any live wallpaper,
Motorola's own included. The Android 17 version added live wallpapers there, so the Razr 50
should get cover lock support with that update.

Even on Android 17, the Wallpaper row only offers one live wallpaper, and Motorola's own come
first, so on some models exCover may not appear there.

## Set it up (one time, about a minute)

Motorola decides what its cover screen shows, so the first time you pick exCover in Motorola's
own settings. exCover opens the right screen for you.

1. In exCover, choose a GIF (**Photos**, **Search GIPHY**, or paste a link) and tap the preview to
   frame it.
2. Tap **Set Wallpaper → Cover Screen → Home & Lock**.
3. **Home screen:** if Motorola hasn't given exCover the cover yet, its wallpaper picker opens.
   Choose **exCover**.
4. **Lock screen:** Motorola's cover lock screen is drawn by a *design* (a clock style).
   Pick a design that has a **Wallpaper** row, then choose the **exCover** card and save.
   Designs with built-in artwork (the moon one, for example) cover up any wallpaper.
5. **Always-on display:** in **Settings → External display → Lock screen → Themes**, choose the
   **exCover** clock face and save. When the cover enters AOD, the lock GIF keeps playing under a
   heavy dimming layer and burn-in shift.

After that, pick a GIF in exCover and tap **Set Wallpaper**. The cover screen updates straight
away.

**Main screen:** **Set Wallpaper → Main Screen** opens Android's wallpaper preview. Tap **Set**
and choose home, lock, or both.

### About the inner always-on display

The inner lock screen can use an exCover GIF when it wakes, but the inner *always-on display*
cannot. On the Razr 50 Ultra's Android 17 firmware, Motorola routes inner AOD through a
signature-protected system renderer and forces even Motorola's colorful lock styles to a
monochrome outline while the panel is truly dozing. A normal, non-root app cannot replace that
renderer. exCover does not fake AOD by keeping the main screen awake.

## Troubleshooting

| What you see | What to do |
| --- | --- |
| Cover lock screen shows a clock design's own art | Choose a design with a **Wallpaper** row and select the exCover card. |
| exCover isn't in the lock screen's Wallpaper row, only photos | Your phone is on Android 16, where Motorola doesn't allow live wallpapers there. The cover home screen still works. |
| exCover isn't in the home screen's Wallpaper row | Update exCover. On Android 16, Motorola only lists it in the Digital design (the default) or in designs without their own wallpaper set. Tap **Themes** and switch design if you use another one. |
| Cover screen went black after an update | Android occasionally drops the lock screen after an app update. Tap **Set Wallpaper** again. exCover notices and walks you back through Motorola's editor. |
| Cover screen is black after uninstalling | Motorola keeps pointing at the removed app. Pick a normal wallpaper in **Settings → External display**. Doing that *before* uninstalling avoids it. |
| Inner AOD is still black and white | This is a Motorola firmware restriction, not a missing exCover setting. The colorful exCover AOD is for the cover display. |
| GIPHY search says a key is needed | You built the app yourself. See [Building](#building). |

## Building

Requirements: Java 17, Android SDK 37.

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

GIPHY search needs your own free API key from [developers.giphy.com](https://developers.giphy.com/)
(choose **API**, not SDK). Add it to `local.properties`, which is never committed:

```properties
giphy.apiKey=YOUR_KEY
```

Without a key, everything except GIPHY search still works.

## How it works

The Razr's cover screen has its own wallpaper slots, separate from the main screen's. A few
undocumented details let exCover use them like Motorola's own wallpapers do:

- The wallpaper service declares Motorola's `moto.cli.livewallpaper` metadata, so Motorola's cover
  wallpaper pickers list it next to their own.
- The cover lock screen is painted by the active clock-face design. Selecting a live wallpaper in
  a design's Wallpaper row switches that design into "show the live wallpaper" mode.
- A Motorola-compatible clock-face provider and session service expose exCover to the cover lock
  screen's theme picker. During real cover doze, that service keeps drawing the selected lock GIF
  beneath a heavy black scrim and uses Motorola's burn-in offsets.
- One service runs separately on each screen (cover home, cover lock, main home, main lock) and
  draws the GIF chosen for that slot, with that slot's framing.
- GIFs are decoded with `ImageDecoder` / `AnimatedImageDrawable`, so frames decode on a background
  thread and are drawn by the GPU.

<details>
<summary>Advanced: set the cover slots over ADB</summary>

`tools/SetCoverWallpaper.java` runs as Android's shell user and can bind, back up, or restore the
cover home and lock slots directly. You shouldn't need it, but it's handy for testing.

```powershell
.\tools\build-helper.ps1
adb push .\tools\set-cover.jar /data/local/tmp/set-cover.jar
adb shell "CLASSPATH=/data/local/tmp/set-cover.jar app_process /system/bin SetCoverWallpaper set home com.ranaco.razrcoverwallpaper com.ranaco.razrcoverwallpaper.GifWallpaperService"
```

</details>

## Privacy

- GIFs you import are copied into the app's private storage and never leave your phone.
- The only network requests are the ones you start: GIPHY searches and GIF downloads.
- No analytics, no ads, no account.
- Photos are opened through Android's picker, which grants access to the one file you choose.

## Contributing

Issues and pull requests are welcome, especially reports from other Razr models.

---

<div align="center">

Made with love by [Ranaco](https://github.com/Ranaco)

</div>
