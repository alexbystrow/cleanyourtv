# My Xiaomi TV Box S (2nd gen) setup

Personal fork branch `mybox`. Box: MiTV-AFKR0, Google TV 11, firmware RTT0.211222.001.767.

| Path | What |
|---|---|
| `lists/step1-debloat.txt` | Ads, telemetry, preloads (Prime Video, Play Movies and the update service kept) |
| `lists/step2-setupwraith.txt` | Setup wizard, so Home stays on the new launcher |
| `lists/step3-extras.txt` | Duplicates and unused Xiaomi extras (YouTube, USB media apps, Miracast) |
| `lists/xiaomi-box-s-2nd-gen.txt` | Steps 1 and 2 in one list |
| `theme/` | Nature calm with a 6-app dock, library button, no caption, faded photos, app-colour rim focus |

Launcher changes on this branch: an inner shadow inside the `rim` / `halo` focus border (`GlassView.java`), dark
top-bar buttons (`pill_bg.xml`), `NNN.plain.jpg` support for the screensaver (unused now that the frost is live),
motion in `WallpaperPlayer.java` (Ken Burns, crossfade, parallax, live dock frost from a 1/32 blurred copy),
and a dock highlight that ignores advanced_effects' lift (dock tiles never lift).

Theme extras: greeting "Alex", the clock screensaver over the wallpaper, the Apps button (keycode 284) opens the
quick settings panel. Add-on: Sleep Timer.
Dock and remote keys (Netflix, YouTube → SmartTube, Prime) are in `launcher/app/src/main/res/values/config.xml`.

```bash
export TV_HOST=<box ip>
tools/disable.sh my/lists/step1-debloat.txt          # undo: tools/restore.sh <same list>
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
HOME_KEY_SERVICE=1 launcher/build-and-install.sh      # one key helper only: it also handles app_keys
addons/sleep-timer/build-and-install.sh
tools/disable.sh my/lists/step2-setupwraith.txt
tools/disable.sh my/lists/step3-extras.txt
tools/theme.sh my/theme                              # ~3.5 min: 92 photos
adb shell settings put secure screensaver_components com.gathin.tvhome/.ClockDream   # via tools/tv.py

# Photos: python my/tools/fade.py <originals 16:9> my/theme/wallpapers 0.45 0.6 (the frost is live, nothing else)
```

Update from upstream: `git fetch upstream && git rebase upstream/main`, then rebuild.
