# ZAMN Dual Screen Mod

A **dual-display SNES emulator** for Android: gameplay runs on the primary display
while a second screen shows a live, memory-driven HUD — minimap, health, ammo,
lives — plus save/load state. The included game profile is
**Zombies Ate My Neighbors**, but the runtime is profile-driven and not tied to
that game.

No `androidx`. Kotlin + a small C++ libretro frontend around the Snes9x core.

---
![ZAMN dual screen preview](https://raw.githubusercontent.com/crazylobstergod/zamn-dual-screen/main/docs/preview.png)

## Features

- **Primary display** — full libretro/Snes9x gameplay (arm64, OpenGL surface)
- **Secondary display** — a generic `Presentation` window hosts a
  `SecondScreenModule` on the first non-default `Display`
- **Live HUD** (`ZamnSecondScreen`) — health, ammo, and spare-lives read
  directly from SNES WRAM at profile-declared offsets; read-only by design
- **Minimap** — 48 in-game level maps, level detected from memory
- **Save / Load state** — libretro save states, triggered from the second screen
- **ROM gate** — the app ships *without* a ROM; you pick your own `.sfc`
  (SAF) and it is verified by SHA-256 before the core starts. No bundled
  fallback, no guess-and-hope booting
- **Boot menu** — PLAY / SELECT ROM with touch **and** gamepad navigation
  (D-pad up/down, A confirm, B/Back cancel)
- **Standby screen** — while the menu is up, the dash display shows a blurred
  level-map backdrop instead of a blank window
- **Adaptive launcher icon** — correct on launchers that would otherwise wrap
  legacy icons in a white plate

## Architecture

```
primary display                     secondary display
┌────────────────────────┐          ┌────────────────────────┐
│ MainActivity           │          │ Presentation window    │
│  └─ GLSurface (core)   │          │  └─ SecondScreenModule │
│     frontend.cpp       │          │     ZamnSecondScreen   │
│      Snes9x core (C)   │          │      MinimapView       │
└────────────────────────┘          └────────────────────────┘
          │  JNI: read memory / set buttons / save-state
          └──────────►  runtime/memory/GameMemory (profile-driven)
```

- `runtime/` — the generic, game-agnostic layer: display hosting, memory API,
  emulator state. **Don't break this when adding game UI**.
- `game/` — ZAMN-specific: profile, HUD, minimap, ROM gate, menu, standby.
  Replace this whole package for a different game.
- `cpp/frontend.cpp` — the libretro frontend: core init, input, memory reads,
  save states.
- `third_party/snes9x-libretro` — pinned upstream Snes9x (build as-is).

## Project layout

```
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── game/zamn_profile.json   # memory fields (offset/type/access)
│       │   └── map/level_1..48.png      # minimap art
│       ├── cpp/                         # CMakeLists.txt, frontend.cpp
│       ├── java/com/example/snestemplate/
│       │   ├── MainActivity.kt
│       │   ├── runtime/{display,memory,state}/   # generic layer
│       │   └── game/{menu,profile,rom,second_screen}/  # game layer
│       └── res/                         # widgets, maps, adaptive icon
├── third_party/snes9x-libretro/         # upstream core (do not edit)
└── gradlew / gradle/                    # wrapper (Gradle 8.7)
```

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 35 (API 35) |
| Android NDK | 27.2.12479018 |
| CMake | 3.22.1 (bundled with SDK) |
| Device | Android 8.0+ (minSdk 26), **arm64-v8a**, with a second display |

> arm64 only: the APK contains a single `arm64-v8a` native library.

## Build

```bash
# point gradle at your SDK — either way works:
export ANDROID_HOME=/path/to/android-sdk      # 1) environment
# ... or create local.properties:  sdk.dir=/path/to/android-sdk   # 2) file

./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/ZAMN.apk
```

Windows: `gradlew.bat :app:assembleDebug` (JDK 17 on `PATH` or `JAVA_HOME`).

## Install & use

A prebuilt APK is attached to the [releases](../../releases) page —
requires Android 8+ on **arm64** with a second display. The ROM is *not*
included (and can't be), so the app still asks you for your own copy.

Build + install from source:

```bash
adb install -r app/build/outputs/apk/debug/ZAMN.apk
adb shell am start -n com.example.snestemplate/.MainActivity
```

1. On the boot menu, **SELECT ROM** and pick your own ZAMN ROM (`.sfc`;
   you need a legally owned copy; its SHA-256 must match the expected hash in
   `RomGate.kt` — change it there if your ROM source differs).
2. **PLAY** starts the core on the primary display and brings up the HUD on
   the second.
3. In-game: the dash screen shows minimap + health + ammo + lives; the
   bottom-right has **Save** / **Load** state.
4. Gamepad: D-pad up/down switches menu selection, A confirms, B/Back closes.

## Credits

- **Game sprite art** — the minimap level maps, weapon/item icons, character
  sprites, and the launcher face were obtained from
  [Spriters Resource](https://www.spriters-resource.com/), a fan-run archive of
  game sprite data. The artwork itself remains © its original rights holders
  (for ZAMN: Nintendo); Spriters Resource is the source the sprite data was
  taken from. Used here for a personal, non-commercial project.
- **Snes9x core** — [Snes9x](https://snes9x.com/) by its developers (see
  `third_party/snes9x-libretro/LICENSE`).

## Legal

- **No game ROM is included in this repository, and none should ever be
  committed.** Bring your own legally acquired copy. The game is © its
  respective rights holders (for ZAMN: Nintendo).
- **Snes9x core** (`third_party/snes9x-libretro`): freeware, **personal /
  non-commercial use only**, with the license notice retained — see
  `third_party/snes9x-libretro/LICENSE`. Bundled components (JMA, snes_ntsc,
  xBRZ) carry their own licenses in the same tree.
- **Everything else in this repo** (the `app/` module, frontend, docs):
  [MIT](LICENSE), see `LICENSE`.
