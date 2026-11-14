# IGI Mobile

A native Android shell for the browser port of **Project I.G.I.** hosted at
[playigi.com](https://playigi.com). The app opens the web build in a fullscreen
landscape WebView and keeps a local copy of the game shell and all 14 missions,
so it installs, launches and plays **with no connection and no download step**.

> **This build bundles game assets.** The APK contains a copy of the web port and
> its mission data. Project I.G.I. and all game content belong to their
> respective rights holders. The bundle exists for personal offline use only;
> **do not publish or redistribute this APK** without written permission. See
> [Publishing and legal](#publishing-and-legal).

## Download

The signed APK is attached to the [latest release](https://github.com/Alamshowkatdar/igi-mobile/releases/latest)
(`IGI-Mobile-1.0.1-bundled.apk`, ~117 MB). Sideload it on Android 8.0+ and let
the app expand the bundled data on first launch. The source tree does **not**
contain the game data; GitHub's 100 MB file limit is why it is a release asset.
Build it yourself with `./gradlew :app:fetchGameBundle` when you have permission
to use the content.

---

## What it is / isn't

| | |
|---|---|
| ✅ Native launcher, mission browser, mobile-friendly shell | ❌ Not a from-scratch native 3D rewrite of the game |
| ✅ Fully offline: shell + data extracted from the APK on first run | ❌ Not an official release of the game |
| ✅ Optional HD textures / FLAC audio can be added by download | ❌ Not licensed for distribution |
| ✅ 6.5 MB of app code; the APK's size is the bundled game content | |

---

## Requirements

**On the phone**

- Android 8.0 (API 26) or newer with OpenGL ES 2.0
- ~250 MB free storage (the APK plus its expanded copy)
- No internet needed to play. Wi-Fi is only used if you enable the optional HD
  texture pack or FLAC soundtrack, or ask the app to re-check for updates.

**On the build machine**

- JDK 17
- Android SDK Platform 35 and Build-Tools 35.0.0
- Gradle is provided by the wrapper (it downloads Gradle 8.9 on first run)

---

## Building

```bash
# Point Gradle at your SDK. `local.properties` is machine-specific; create it
# locally or export ANDROID_HOME instead.
echo "sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk" > local.properties

./gradlew :app:assembleDebug     # debug APK
./gradlew :app:assembleRelease   # signed release APK
```

Output:

- `app/build/outputs/apk/release/app-release.apk` (package `com.igi.mobile`)
- `app/build/outputs/apk/debug/app-debug.apk` (package `com.igi.mobile.debug`)

The release APK is roughly **115 MB**, because `assets/bundle/` carries the game.

Install over USB:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Refreshing the bundled content

The bundle is generated, not hand-edited:

```bash
./gradlew :app:fetchGameBundle          # shell + all 14 missions, SD + touch
java tools/FetchBundle.java app/src/main/assets/bundle --hd --flac
```

`tools/FetchBundle.java` reads the site's `shell-files.json` and
`offline-index.json`, downloads the files into `tools/.bundle-cache/`, and writes:

- `app/src/main/assets/bundle/shell.zip` — page, JS/WASM, fonts, radio audio
- `app/src/main/assets/bundle/data.zip` — mission data (store-only, ~105 MB)
- `app/src/main/assets/bundle/version.txt` — content hash and entry counts
- `app/src/main/assets/offline-index.json` — manifest matching the bundle

The version hash is what tells the app to re-extract after an update. If you
change the bundle, bump `versionCode` in `app/build.gradle.kts` before shipping.

> The data host refuses requests without `Referer: https://playigi.com/game/`.
> Both the fetch tool and the in-app downloader send it. If downloads start
> failing with HTTP 403, check that first.

### Asset-free build

Delete (or don't create) `app/src/main/assets/bundle/`. The app detects the
absence, hides the "bundle" status, and falls back to its original behaviour:
download the shell and the missions you select from playigi.com on first use.
This is the configuration that is safe to distribute.

### Release signing

`app/build.gradle.kts` reads signing details from `keystore.properties` at the
project root, if present:

```properties
storeFile=keystore/igi-release.jks
storePassword=change-me
keyAlias=igi
keyPassword=change-me
```

Create your own key rather than reusing one someone sent you:

```bash
keytool -genkeypair -v -keystore keystore/igi-release.jks -storetype PKCS12 \
  -alias igi -keyalg RSA -keysize 4096 -validity 10000
```

`keystore.properties` and `keystore/` are in `.gitignore`. **Back them up** —
losing the key means you can never update the app under the same package.

---

## How it works

```
MainActivity (Compose)                GameActivity (fullscreen WebView)
  Home / Missions / Settings  ──────▶   https://playigi.com/game/?touch=1
  About / first-run disclaimer          │
        │                               ├─ GameWebViewClient intercepts requests
        ▼                               │    playigi.com/game/*   →  files/igi/shell/
  GameRepository (StateFlow)            │    data.playigi.com/*   →  files/igi/data/
        │                               │    anything else        →  network
        ▼                               ▼
  BundleInstaller (first run)      LocalAssets serves bytes with
  unzips assets/bundle/*.zip       CORS + Range + correct MIME
```

- **First launch** — `BundleInstaller` streams `shell.zip` and `data.zip` from
  the APK into `getExternalFilesDir()/igi/`, showing progress on Home. A marker
  file records the bundle hash so this happens once per bundle version, not on
  every launch. The 104 MB copy takes a few seconds on modern storage.
- **Offline by construction** — `shouldInterceptRequest` answers every
  `playigi.com/game/*` and `data.playigi.com/*` request from disk, so the game
  never needs the network. Files not present locally fall through to the network
  and fail cleanly when offline.
- **Service worker disabled** — the site registers a service worker that would
  otherwise fight the app's cache. A boot script injected at document start
  (`__IGI_APP_BOOT__` in `ShellCache.kt`) overrides
  `navigator.serviceWorker.register` and unregisters existing registrations.
  `BundleInstaller` runs the same patch after expanding the shell, since the
  bundled `index.html` comes straight from the site.
- **Co-op QR pairing** — the web build scans the host's QR code with
  `getUserMedia` + `BarcodeDetector`. WebView routes that through
  `WebChromeClient.onPermissionRequest`; `GameActivity` asks for the Android
  CAMERA permission on demand and grants only `RESOURCE_VIDEO_CAPTURE` to
  `playigi.com`. The scan button is hidden by the site when `BarcodeDetector` is
  unavailable (it is present in current Android WebView builds).
- **Local co-op (no internet)** — the site pairs two phones with a code and then
  expects host-only WebRTC (`iceServers: []`) to connect. `assets/lan-webrtc.js`
  is injected after page load and wraps `RTCPeerConnection`: when Chromium hides
  the phone's host candidates behind mDNS names (`<uuid>.local`, which Android
  cannot resolve), the shim rewrites them to the phone's real Wi-Fi address —
  supplied by `LanBridge` from `NetworkInterface`, best Wi-Fi address first —
  before the game serialises the local description into the pairing code.
  Chromium's own description is untouched, so ICE keeps working locally. If the
  link still does not open, the shim reports the stall 14 s after the code was
  accepted and `GameActivity` probes the other phone on port 9, so the player is
  told whether the router is isolating clients or pairing itself failed instead
  of staring at "Connecting...".
- **Raw gzip** — many data files end in `.gz` and the game decompresses them in
  JavaScript with `DecompressionStream('gzip')`. They are served verbatim with
  **no** `Content-Encoding`, and with `Access-Control-Allow-Origin: *`.
- **Optional packs** — enabling HD textures or FLAC audio in Settings downloads
  only the extra files from `data.playigi.com` into the same directory tree.
  SD data stays on disk as a fallback.
- **Storage** — everything lives under
  `getExternalFilesDir()/igi/` (app-specific, removed on uninstall, invisible to
  other apps). Clearing cache deletes it, then re-expands the bundled copy.

### Download sizes

| Item | Size |
|---|---|
| Bundled in APK: shell + common + sd + touch | ≈ 107 MB |
| Expanded on device | ≈ 107 MB |
| Optional HD textures | + 69 MB |
| Optional FLAC soundtrack | + 223 MB |

---

## Project layout

```
app/src/main/
  assets/
    bundle/{shell.zip,data.zip,version.txt}   generated by tools/FetchBundle.java
    offline-index.json                        manifest matching the bundle
    lan-webrtc.js                             local co-op WebRTC shim (injected)
  java/com/igi/mobile/
    MainActivity.kt            Compose host, navigation, back handling
    GameActivity.kt            Fullscreen landscape WebView, audio focus, back → Esc
    IgiApplication.kt          Notification channel, repository warm-up
    data/
      GamePaths.kt             URLs and cache directories
      Net.kt                   OkHttp helpers (single GET, resumable download)
      OfflineIndex.kt          offline-index.json parser, per-mission file sets
      BundleInstaller.kt       First-run expansion of assets/bundle/*.zip
      GameRepository.kt        StateFlow state, installed scan, download orchestration
      DownloadService.kt       Foreground dataSync service + progress notification
      ShellCache.kt            Network shell download + service-worker-disable script
    web/
      GameWebViewClient.kt     Request interception and routing
      LocalAssets.kt           MIME types, CORS, Range responses from disk
      LanBridge.kt             window.IgiLan: local IPv4 addresses + stall reports
      LanWebRtc.kt             Injects assets/lan-webrtc.js into the game page
    ui/
      Screens.kt               Home, Missions, Settings, About, Disclaimer
      Components.kt            Panels, buttons, size formatting
      Theme.kt                 Colors and typography
tools/
  FetchBundle.java             Downloads and packages the game content
```

---

## Using the app

1. Read and accept the disclaimer on first launch.
2. Home shows **EXPANDING BUNDLED GAME DATA** the first time. Wait for the bar
   to finish (a few seconds), then tap **PLAY**.
3. **Missions** lists all 14 levels; they are already installed. Optional HD and
   FLAC packs can be added from Settings, which needs a connection.
4. In the game, the system Back gesture sends `Escape` (menus/pause). Use the
   in-game exit option to leave a mission, then Back again to return to the
   launcher.
5. **Co-op** — the host shows a QR code; the other player taps **Scan** and
   allows the camera prompt. Pasting the code works without camera access.
6. **Settings** — HD pack, FLAC audio, on-screen touch controls, Wi-Fi-only
   downloads, re-check for updates, clear cache (re-expands the bundle).

---

## Troubleshooting

**Stuck on "EXPANDING BUNDLED GAME DATA"**
Storage may be full. The bundle needs ~110 MB free on top of the APK. Free space
and reopen the app; extraction restarts from the beginning if it was interrupted.

**Black screen after Play**
The shell may be mid-extraction or corrupt. Settings → *Clear cache* re-expands
the bundle. If it persists, check `adb logcat | grep -i chromium` for the failing
URL and confirm `app/src/main/assets/bundle/version.txt` exists.

**Downloads return HTTP 403**
`data.playigi.com` requires `Referer: https://playigi.com/game/`. The app sends
it; if you changed the downloader, keep that header.

**Game starts but missions are missing**
The bundle contains SD + touch data. Check the status pill on Home: if it says
**MISSING** instead of **READY**, extraction failed — clear cache to retry.

**The Scan button is missing or the camera does not open**
The site only renders **Scan** when `BarcodeDetector` exists; current Android
WebView builds expose it (the app logs `barcodeDetector|getUserMedia` at page
load). If the button is missing, update Android System WebView from the Play
Store. If tapping it does nothing, allow the camera prompt — a denial is
remembered until you grant it in Android Settings → Apps → IGI Mobile →
Permissions → Camera. Pasting the host's pairing code always works.

**Local co-op stays on "Connecting..."**
Both codes were exchanged, so the pairing screen works; WebRTC never found a
route between the phones. The app wraps `RTCPeerConnection`
(`assets/lan-webrtc.js`) and, 14 s after a code is accepted, shows a **Local co-op
problem** dialog with the addresses each phone advertised and whether the other
phone answered on the LAN. Read that first:

- **Router is isolating clients** — "AP isolation", "client isolation" or a guest
  network. Turn it off in the router, or start a hotspot on one phone and connect
  the other to it (local co-op works over a hotspot).
- **Only `.local` addresses** — the other phone advertised only mDNS names.
  Android cannot resolve those, so both phones must run this build, which rewrites
  them to real Wi-Fi addresses.
- **Different networks** — one phone on a 5 GHz guest SSID and the other on the
  main one, or one on mobile data, cannot see each other.

`adb logcat -s IgiGame` prints the whole pairing trace: `[igi-lan]` lines cover the
ICE candidates, mDNS rewrites, signaling/connection state and the remote SDP.

**Audio keeps playing after leaving**
The activity abandons audio focus in `onPause`. If a vendor ROM ignores it, swipe
the app away from recents.

---

## Publishing and legal

The APK built from this tree **contains copyrighted game content**: the playigi.com
web build and the Project I.G.I. mission data. This is the "bundle everything"
configuration you asked for. Treat it as a personal build.

Before you distribute or publish anything based on this:

1. **Get permission.** The web port and the original game are not yours to
   redistribute. Ask the port's author and the rights holders (the original
   developer/publisher and the current IP owner) for written permission covering
   mobile redistribution. Without it, sharing this APK is copyright infringement
   in most jurisdictions.
2. **Prefer the asset-free build for sharing.** Remove `assets/bundle/` and the
   app reverts to downloading content on the user's device from the public site.
   That still needs the site's blessing, but it does not make you a redistributor.
3. **Expect store friction.** Google Play restricts WebView-wrapper apps and
   apps that reproduce copyrighted content without authorization. A listing that
   names Project I.G.I. and ships its data without a licence will likely be
   rejected or removed. Do not present the app as official.
4. **Keep the disclaimer.** The first-run dialog and About screen state that this
   is an unofficial client, that content is bundled for personal use, and that
   redistribution is not allowed. Do not remove them.
5. **Respect the site.** The fetcher only pulls files a normal browser session
   would, with the same referrer. Do not add scrapers, ad stripping, or anything
   that bypasses the port's access controls.
6. **Privacy.** No analytics, accounts or ads. Runtime network use is limited to
   `playigi.com` / `data.playigi.com` for optional packs and updates.

If you cannot obtain permission, keep the APK on your own devices.

---

## License

App code in this repository is provided as-is for personal and educational use.
Third-party components keep their own licences: AndroidX and Jetpack Compose
(Apache 2.0), OkHttp (Apache 2.0), Kotlin and coroutines (Apache 2.0). The game
content in `assets/bundle/` is **not** covered by any licence granted here.
