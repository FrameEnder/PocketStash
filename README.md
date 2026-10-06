# PocketStash

A native Android client for [Stash](https://github.com/stashapp/stash), built with Kotlin and Jetpack Compose. It talks straight to your server's GraphQL API, so there's nothing to install on the Stash side.

Requires **Stash v0.27 or newer**, the version where groups replaced movies. Android 8.0+.

## Phase 1 (this drop)

| Area | What works |
|---|---|
| **Connect** | URL + API key, connection test that shows the server version, plain HTTP on LAN/Tailscale, user-installed CAs for self-signed HTTPS |
| **Home** | Continue watching, recently added, newest releases, random picks, favorite performers, top rated, recent galleries, recent markers, favorite studios, each with "See all" |
| **Browse** | Scenes, Performers, Studios, Tags, Groups, Galleries, Images, Markers. Infinite scroll, search, every useful sort key, asc/desc, stable random with reshuffle, quick filters (unwatched, in progress, rated, organized, favorites), pull to refresh |
| **Search** | One box that searches all 8 entity types at once |
| **Scene page** | Play/resume, play from start, star rating, O counter (+/-), organized toggle, play count and watch time, performers, tags, markers (tap to jump), linked galleries and groups, details, URLs, full file info |
| **Performer / Studio / Tag / Group / Gallery pages** | Full metadata, rating, favorite, parent/child studios and tags, plus tabbed related grids (scenes, galleries, images, performers, groups, markers) in a single scroll |
| **Player** | ExoPlayer that tries the direct stream first and falls back through Stash's transcodes (HLS/DASH/MP4/WebM) automatically on codec errors. Manual stream picker, marker chapters, speed control, picture-in-picture, auto orientation from video shape, rotate lock. Resume point, play count and watch time are sent back to Stash |
| **Image viewer** | Swipe through any image list (a gallery, a performer's images, search results), pinch and double-tap zoom, rating, O counter, video clips play in the player |
| **Settings** | Library stats, resume toggle, activity tracking toggle and threshold, card size, media URL host rewrite (fixes reverse-proxy and Docker hostnames), change server or disconnect |

Every GraphQL document in `data/Queries.kt` has been validated against the schema in `stashapp/stash` (graphql/schema) as of 2026-10-01.

## Planned phases

- **Phase 2: editing.** Scene, performer, studio, tag, group, gallery and image edit forms; create, delete and merge; marker create/edit; bulk select with multi-edit.
- **Phase 3: scraping & tagger.** Scrape by URL/fragment, StashDB / stash-box lookup, scene tagger flow, identify.
- **Phase 4: server admin.** Scan, generate, auto-tag, clean, identify tasks; job queue with live progress; logs; plugins and their tasks; scrapers; saved filters; config.
- **Phase 5: extras.** Full filter builder (all criteria), saved/default filters from Stash, scene and marker walls, duplicate checker, offline downloads, and a Phase 1 polish pass based on your testing.

## Building with GitHub Actions

Every push to `main` builds a release APK. Pushing a tag like `v0.1.0` also publishes it as a GitHub Release.

To keep updates installable over each other, sign every build with your own key. Create it once:

```bash
keytool -genkeypair -v -keystore ~/keys/pocketstash.jks -alias pocketstash -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 ~/keys/pocketstash.jks | gh secret set PS_KEYSTORE_B64
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

### Local

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/
```

Needs JDK 17 and the Android SDK (platform 35). The debug build installs as `com.frameender.pocketstash.debug`, alongside the release build.

## Layout

```
app/src/main/java/com/frameender/pocketstash/
  PocketStashApp.kt        app container, Coil image loader (shares the auth'd OkHttp client)
  MainActivity.kt
  data/                    settings, GraphQL client, queries, repository, browse specs, models
  player/PlayerActivity.kt
  ui/
    nav/                   routes, navigator, NavHost + bottom bar
    browse/                generic paged grid + view model (used everywhere)
    home/ search/ library/ settings/
    detail/                scene, performer, studio, tag, group, gallery pages
    images/                full-screen image pager
    components/ theme/ common/
```
