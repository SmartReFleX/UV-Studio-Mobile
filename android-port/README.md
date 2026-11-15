# UV Studio Mobile — source

Native Android client for the Quansheng **UV-K1** and **UV-K5 V3** running the
**F4HWN firmware**. The phone is the USB host and the radio enumerates as a CDC-ACM
device, so every screen talks to the hardware directly: no WebView, no website, no
server, no JavaScript.

Features, requirements and install steps are in the [top-level README](../README.md).
This file only covers the source tree.

## Layout

| Path | Contents |
| --- | --- |
| `AndroidManifest.xml` | package, permissions, activity, USB host declaration |
| `src/ro/reflex/uvstudio/` | all Java sources, 27 files |
| `res/` | launcher icon and card icons |
| `assets/` | bundled `LICENSE.txt`, `NOTICE.txt`, `app-screens.txt` |
| `tools/` | `get-toolchain.ps1`, icon generation and checks |
| `test/` | static HTML previews for the icon work |
| `build.ps1` | builds, aligns and signs the APK |
| `LICENSE`, `NOTICE` | Apache-2.0 and attribution |

Key classes:

- `UvProtocol`, `UvFrames` — the framed wire format, `AB CD … DC BA` and `AA 55`
- `UsbSession`, `RadioLink` — CDC-ACM session, endpoint handling, framing
- `Radio` — commands and responses, calibration, flashing, slots
- `MainActivity` — home screen and navigation, one screen per tool

## Build

No Gradle. The script drives the Android SDK command line tools directly
(`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -Clean
```

Run it from inside `android-port/`. Output: `apk/UV-Studio-Mobile-1.0.0.apk`.

First run needs the toolchain (JDK 17, platform 35, build-tools):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\get-toolchain.ps1
```

It downloads everything into `%LOCALAPPDATA%\uvk-build\android`. No installer, no
system changes.

## Signing

The build looks for `keystore.jks` next to `build.ps1`. That file is **not** in the
repository and must never be committed: it is the key that authenticates updates over
the published APK.

Overrides, read from the environment:

| Variable | Meaning |
| --- | --- |
| `UVSTUDIO_KS_FILE` | path to the keystore |
| `UVSTUDIO_KS_ALIAS` | key alias, default `uvstudio` |
| `UVSTUDIO_KS_PASS` | store and key password |

If no keystore is found, the script generates a throwaway one so the build still works.
An APK signed that way installs fine, but it cannot update a copy signed with the real key.

## Tests and tools

```powershell
python .\tools\check_icons.py     # verify the icon set
python .\tools\gen_icon.py        # regenerate the launcher icon
```

`test/icons.html` and `test/preview.html` are static previews, opened in a browser.

## Licence

Apache-2.0, © 2026 Claudiu Panga. See [`LICENSE`](../LICENSE) and [`NOTICE`](../NOTICE).
Independent native Android client compatible with UV Studio by Armel FAUVEAU (F4HWN).
Unofficial community project, not tested or supported by F4HWN.
