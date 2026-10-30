# Loop Player

Turn an old Android screen into a looping video sign. Loop Player is a lightweight app that plays the videos in one
folder full screen, forever: no playback controls, no status bar, no ads, no accounts, no cloud. It suits cheap
tabletop signage players, old tablets, digital photo frames and Android TV boxes running **Android 5.0 or newer**.

It started as the replacement player for the [FYD-835S tabletop sign](https://github.com/robbiemueller/fyd-835s-tabletop-signage),
but nothing in it is specific to that device.

## What it does

- **Plays** every `.mp4`, `.mkv` and `.webm` in **`Movies/loop/`** on the device's storage (`/sdcard/Movies/loop/`),
  in name order, forever. A single file loops **seamlessly** inside the media player.
- **Swipe up** to choose what loops. A panel of thumbnails slides up:
  - **Tap** a video to add it to the loop or take it out. The number on each tile is its place in the order, so
    tapping three videos plays them back to back in that order.
  - **Hold** a video to loop just that one (the panel closes and it starts at once).
  - **All** loops every video in the folder, including ones added later.
  - Swipe down, tap above the panel or press Back to close it; it also closes by itself after 30 seconds. Unchosen
    videos are greyed out, and the one playing now is marked. The choice is remembered across restarts.
- **Double-tap:** sound off/on. It's remembered across restarts.
- **Hold a finger down for 3 seconds:** opens the menu.
  - **Choose videos** (the same panel as swiping up)
  - **Phone remote** on/off (see below)
  - **Sound** on/off
  - **Picture:** *fit* the whole video (letterbox, the default) or *fill* the screen (crop)
  - **Home screen:** makes Loop Player the launcher, so it opens at boot and whenever Home is pressed (kiosk mode)
  - **Sleep:** the screen goes black at its lowest backlight and playback pauses; tap the screen to wake it
  - **Reload videos**, **Android settings**, **Exit player**
- **Starts at boot.** On Android 9 and older, it does this on its own. On Android 10+, turn on **Home screen** in the
  menu, because newer Android won't let apps open a screen at boot any other way.
- **Rotation** follows the device (and its rotation lock).

## Phone remote and schedule

Turn on **Phone remote** in the hold menu. The screen shows an address such as `http://192.168.1.50:8080` and a
6-digit PIN. Open the address in your phone's browser on the same Wi-Fi, enter the PIN once, and you can:

- pick what loops (tap to build the loop, ▶ to loop one, **All**), with the same rules as the swipe-up panel
- turn the sound on or off and set the volume
- set the brightness, fit or fill, and put the screen to **sleep** or wake it
- **add videos** from your phone, or delete them
- open another app on the device, or bring Loop Player back
- set a **schedule**: a list of times of day, each of which can turn the screen on or off, set the brightness, turn
  the sound on or off and change what loops. For example: 17:30 on at full brightness with every video, 21:00 dim
  to 40% and loop just two, 23:30 off. Each time takes effect once a day; anything you change by hand in between
  stays until the next time.

Good to know:
- The remote is **off until you turn it on**, and only the hold menu can turn it on. It answers only devices on your
  local network, over plain HTTP. Your phone signs in once with the PIN and then stays signed in. Five wrong PINs
  lock that phone or computer out for a minute, and 20 from anywhere in ten minutes pause all PIN tries; phones already signed in keep working. Anyone on your Wi-Fi who sees
  the PIN can use it.
- To see the address and PIN again, hold 3 s: they're on the **Phone remote** line of the menu. **New PIN** (just
  below it) makes a fresh PIN and signs out every phone.
- The schedule and the remote work while Loop Player is open. Sleep dims the backlight to its minimum, since apps
  can't switch the panel fully off.
- Adding and deleting videos needs storage access on Android 6–10 (Loop Player asks when you turn the remote on).
- Opening other apps from the remote works on Android 9 and older; Android 10+ may block it while Loop Player isn't
  on screen.

## Install

1. Download `LoopPlayer.apk` from [Releases](../../releases) and install it: open it on the device (allow installs
   from unknown sources), or from a computer run `adb install -r LoopPlayer.apk`.
2. Open **Loop Player** and allow access to videos. On Android 13+, choose **Allow all**; with "selected only" it
   can't see new files.
3. Put videos in `Movies/loop/` with USB file transfer, a file manager, or:
   ```sh
   adb shell mkdir -p /sdcard/Movies/loop
   adb push my-loop.mp4 /sdcard/Movies/loop/
   ```
4. Hold 3 s → **Reload videos** (or restart the app).

## Remote control over ADB

```sh
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez reload true   # rescan the folder
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --es play intro.mp4        # loop just this one
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --es playlist "a.mp4,b.mp4"  # these, in order
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez all true      # every video
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez mute true     # or false
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez fill true     # or false (fit)
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez home true     # or false
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez remote false  # phone remote off
adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez sleep true    # screen to sleep, or false
```

The phone remote's JSON API works from scripts too: send the PIN as an `X-Pin` header, and an `X-Remote: 1` header
on anything that changes something, e.g.
`curl -H "X-Pin: 123456" http://192.168.1.50:8080/api/state`. The endpoints are listed at the top of
[`RemoteServer.java`](src/io/github/robbiemueller/loopplayer/RemoteServer.java).

## Tips for the video

- **Encode it for the screen.** Match the panel's resolution and use plain H.264 (Main profile) in an MP4. Old
  hardware decoders choke on HEVC, 10-bit and very high bitrates:
  ```sh
  ffmpeg -i in.mp4 -vf scale=1280:-2 -c:v libx264 -profile:v main -crf 20 -pix_fmt yuv420p \
    -c:a aac -b:a 160k -movflags +faststart out.mp4
  ```
- **Seamless loops:** start and end on the same frame (black is easiest) with the sound faded out.
- **Tiny speakers:** master loud (about -13 LUFS) and roll off everything below about 140 Hz.

## Tested

On an FYD-835S (Rockchip RK3126, Android 6.0.1), we tested:
- the permission prompt
- playback and seamless looping
- the swipe-up picker (tap to add or remove, hold to loop one, All, swipe down to close)
- the phone remote: PIN, sessions and lockout, picking videos, sound, brightness, sleep and wake, uploading and
  deleting
- the schedule (sleep, wake, brightness and loop changes firing at their times)
- double-tap mute
- fit and fill
- the hold menu
- the home-screen chooser

It builds for Android 15 (target SDK 35), but **hasn't been run on Android 10+ hardware yet**. Reports welcome.

Known quirk: on that device's old Launcher3, uninstalling Loop Player right after offering it as a home screen made
Launcher3 crash once ("Launcher3 has stopped" → OK). It recovered by itself.

## Build

No Gradle or Android Studio project: just the SDK command-line tools (`javac`, `d8`, `aapt2`, `zipalign`,
`apksigner`) from an Android Studio install.

```sh
./build.sh --install     # macOS / Linux (untested so far)
.\build.ps1 -Install     # Windows (tested)
```

The first build creates `local.keystore` here (git-ignored). Releases are signed with a different key, so to switch
from a release to your own build, uninstall first.

## License

MIT
