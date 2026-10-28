# My Xiaomi TV Box S (2nd gen) setup

Personal fork branch `mybox`. Box: MiTV-AFKR0, Google TV 11, firmware RTT0.211222.001.767.

| Path | What |
|---|---|
| `lists/step1-debloat.txt` | Ads, telemetry, preloads (Prime Video, Play Movies and the update service kept) |
| `lists/step2-setupwraith.txt` | Setup wizard, so Home stays on the new launcher |
| `lists/step3-extras.txt` | Duplicates and unused Xiaomi extras (YouTube, USB media apps, Miracast) |
| `lists/xiaomi-box-s-2nd-gen.txt` | Steps 1 and 2 in one list |
| `theme/` | Nature calm with a 6-app dock, library button, no caption, faded photos, app-colour rim focus |

Launcher change on this branch: an inner shadow inside the `rim` / `halo` focus border (`GlassView.java`).
Dock and remote keys (Netflix, YouTube → SmartTube, Prime) are in `launcher/app/src/main/res/values/config.xml`.

```bash
export TV_HOST=<box ip>
tools/disable.sh my/lists/step1-debloat.txt          # undo: tools/restore.sh <same list>
JAVA_HOME=/opt/homebrew/opt/openjdk@17 INPUT_KEY_SERVICE=1 launcher/build-and-install.sh
tools/disable.sh my/lists/step2-setupwraith.txt
tools/disable.sh my/lists/step3-extras.txt
tools/theme.sh my/theme
```

Update from upstream: `git fetch upstream && git rebase upstream/main`, then rebuild.
