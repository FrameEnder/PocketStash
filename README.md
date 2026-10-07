<div align="center">

![](meta/main.svg)

*Your whole Stash library in your pocket... browse, play, rate, and edit it all from one Android app.*

</div>

---

<!--
## Screenshots

<div align="center">

| | |
|:-:|:-:|
| ![](meta/preview1.png) | ![](meta/preview2.png) |
| ![](meta/preview3.png) | ![](meta/preview4.png) |

</div>

---
-->

## What it does

PocketStash is a native Android client for a self-hosted [Stash](https://github.com/stashapp/stash).
It talks straight to Stash's GraphQL API, so there's nothing extra to run on your server. It covers
browsing, playback, and editing: scenes, performers, studios, tags, groups, galleries, images, and
markers, in one app made for a phone. It works over plain HTTP, so a Tailscale address is all you need.

## Features

| | |
|---|---|
| 🏠 **Custom Home** | Keep the default Home (continue watching, recently added, newest, random, favorites, top rated, galleries, markers) or build your own. Add carousels of any entity type with their own sort, filters, search text, item count, and card size, plus stats and shortcut sections. Reorder, hide, duplicate, and copy a layout to another device. |
| 📚 **Browse** | Scenes, performers, studios, tags, groups, galleries, images, and markers. Infinite scroll, search, every useful sort, ascending or descending, a stable random sort with reshuffle, quick filters (unwatched, in progress, rated, organized, favorites), and pull to refresh. |
| 🔍 **Search** | One box that searches all eight types at once, with "See all" for each. |
| 🎞️ **Scene page** | Play or resume, play from the start, star rating, O counter, organized toggle, play count and watch time, performers, tags, markers (tap to jump), linked galleries and groups, details, URLs, and full file info. |
| 🎬 **Player** | Double-tap left to skip back 5s or right to skip ahead 15s, and keep tapping to keep skipping. Swipe up or down on the left for brightness, on the right for volume, with a slider on that side. Mute, play/pause, skip, speed, rotate, and picture-in-picture. The seek bar shows your markers and the name of the one you're in. |
| 📡 **Streams** | Tries the direct file first and falls back through Stash's transcodes (HLS, DASH, MP4, WebM) when the phone can't decode it. You can also pick a stream yourself. |
| ⏱️ **Play tracking** | Resume points, play counts, and watch time are sent back to Stash, so the web UI and the app stay in step. |
| 🔖 **Markers** | Add a marker at the current spot without leaving the player, or from the scene page. Edit or delete any marker. |
| 👤 **Detail pages** | Performers, studios, tags, groups, and galleries with full metadata, ratings, favorites, parent and child studios and tags, and tabbed grids of everything related, all in one scroll. |
| 🖼️ **Image viewer** | Swipe through any image list (a gallery, a performer's images, search results) with pinch and double-tap zoom, ratings, and O counter. Video clips open in the player. |
| ✏️ **Editing** | Everything the web UI can edit: scenes, performers, studios, tags, groups, galleries, images, and markers. Search-as-you-type pickers create missing tags, performers, studios, and groups on the fly. Upload covers and images from the phone or a URL. |
| ➕ **Create & delete** | New performers, studios, tags, groups, and galleries from their lists. Deleting scenes, images, and galleries can also remove the file and generated previews. |
| 📐 **Any aspect ratio** | Portrait (9:16), 4:3, and ultrawide videos show whole, letterboxed on a dark backdrop instead of cropped. |
| ⬇️ **In-app updates** | Checks GitHub Releases on launch and every 6 hours, can notify you, and downloads and installs new builds in place. Choose the **Stable** channel (tagged releases) or **Nightly** (every push). |
| 🎨 **Themes** | Dark ink theme with 16 accent colors. |
| 🌐 **Networking** | Plain HTTP on your LAN or Tailscale, user-installed CAs for self-signed HTTPS, and an option to rewrite media URLs when Stash reports a different host (reverse proxies, Docker). |

---

## Install

Grab the APK from the latest [Release](../../releases) or from the newest **Build APK** run under
[Actions](../../actions). Open it on your phone and allow installs from that app when asked.

Or build it yourself:

```bash
git clone https://github.com/FrameEnder/PocketStash.git
cd PocketStash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/
```

Needs JDK 17 and the Android SDK (platform 35). Android Studio sets up both. The debug build installs
as a separate app (`com.frameender.pocketstash.debug`), so it can sit next to the release build.

**Requirements:** Android 8.0 or newer, and **Stash v0.27 or newer** (the version where groups
replaced movies). Performer editing uses Stash's newer `career_start` / `career_end` fields, so keep
Stash up to date.

---

## First run

1. Enter your server (e.g. `http://100.x.y.z:9999`) and your API key, then tap **Connect**.
   The key is under **Stash → Settings → Security → API Key**. Leave it blank if authentication is off.
2. Browse from the bottom bar: **Home**, **Scenes**, **Performers**, **Search**, and **Library**.
3. Pick a highlight color under **Settings**, and lay out Home with the layout button at the top of Home.

---

## Player gestures

| Gesture | Does |
|---|---|
| Tap | Show or hide the controls |
| Double-tap left / right | Skip back 5s / ahead 15s. Each extra tap within a moment skips again. |
| Swipe up or down on the left | Brightness (only inside the player) |
| Swipe up or down on the right | Volume. Turning it up while muted unmutes. |
| Drag the seek bar | Scrub; white ticks are markers |

---

## Building with GitHub Actions

Every push to `main` builds a release APK. Pushing a tag like `v0.2.0` also publishes it as a GitHub Release.

To keep updates installable over each other, sign every build with your own key. Create it once:

```bash
keytool -genkeypair -v -keystore pocketstash.jks -alias pocketstash -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 pocketstash.jks | gh secret set PS_KEYSTORE_B64
gh secret set PS_KEY_ALIAS --body pocketstash
gh secret set PS_KEYSTORE_PASSWORD
gh secret set PS_KEY_PASSWORD
```

| Secret | What it is |
|---|---|
| `PS_KEYSTORE_B64` | the keystore, base64-encoded |
| `PS_KEYSTORE_PASSWORD` | keystore password |
| `PS_KEY_ALIAS` | key alias (`pocketstash`) |
| `PS_KEY_PASSWORD` | key password (same as the keystore's unless you set one) |

Without these, builds still work but are signed with a throwaway key, so you'd have to uninstall
before each update. Keep `pocketstash.jks` backed up; `.gitignore` already excludes it.

---

## How it's built

| | |
|---|---|
| **UI** | Kotlin, Jetpack Compose, Material 3. Space Grotesk and JetBrains Mono. |
| **Network** | OkHttp and kotlinx.serialization, with hand-written GraphQL documents ([`Queries.kt`](app/src/main/java/com/frameender/pocketstash/data/Queries.kt), [`Edit.kt`](app/src/main/java/com/frameender/pocketstash/data/Edit.kt)) validated against Stash's own schema. |
| **Media** | Coil 3 for images, Media3 ExoPlayer (HLS and DASH) for video, sharing one authenticated OkHttp client. |
| **Editing** | One form engine renders every entity from a field list that mirrors Stash's `*UpdateInput` types. |
| **State** | A small app container plus a ViewModel per screen. Settings and the Home layout live in DataStore. Update checks run in WorkManager. |

```
app/src/main/java/com/frameender/pocketstash/
  data/     GraphQL client, queries, models, settings, edit specs, Home layouts, updater
  player/   the player activity and its gesture controls
  ui/       navigation, theme, shared components, one folder per screen
meta/       README banner and the script that draws it
```

The animated banner comes from [`meta/tools/banner.py`](meta/tools/banner.py)
(`cd meta/tools && python3 banner.py ../main.svg`).

---

## Updates & releases

The app updates itself from this repo's GitHub Releases (**Settings → Updates**).

| Channel | Comes from | Made by |
|---|---|---|
| **Stable** | the latest release | pushing a tag: `git tag v0.2.0 && git push origin v0.2.0` |
| **Nightly** | the rolling `nightly` pre-release | every push to `main` (the workflow replaces it automatically) |

Each build's APK is named `PocketStash-<build>.apk`, where the build number is the Actions run number
and also the app's `versionCode`. The app offers an update when a release's APK has a higher number
than the installed one. Updates only install over each other when every build is signed with the same
key (see the signing secrets above).

While the repo is **private**, GitHub won't serve releases anonymously. Either make the repo public or
paste a fine-grained token (read-only **Contents** access to this repo) under **Updates → Source**.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "Can't reach …" when connecting | The phone can't reach the server. Check that Tailscale (or your VPN) is connected, and that the address opens in the phone's browser. Stash's default port is `9999`. |
| "Unauthorized (401)" | The API key is wrong or missing. Copy it again from **Stash → Settings → Security**. |
| Lists load but thumbnails or videos don't | Stash is handing out URLs with a different host (reverse proxy, Docker). Make sure **Settings → Network → Rewrite media URLs** is on (it is by default). |
| A video won't play | The app already falls back through Stash's transcodes. If none work, pick another stream from the player's quality button, or check that ffmpeg works in Stash. |
| Videos have no sound | Tap the speaker button in the player, or swipe up on the right side. |
| Saving a performer fails | Update Stash; older versions don't know the `career_start` / `career_end` fields. |
| "No release found" in Updates | The repo is private. Add a GitHub token under **Updates → Source**, or make the repo public. |
| A new APK won't install over the old one | The builds were signed with different keys. Set up the signing secrets, then uninstall once. |

---

<div align="center">

**PocketStash** · your stash, wherever you are.

</div>
