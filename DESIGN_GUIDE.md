# Game-specific second-screen handoff

## Working foundation — do not break

Do not casually modify `app/src/main/cpp/frontend.cpp`, `MainActivity.kt`'s emulator loop/renderer/input code, or `runtime/display`. They contain the working Snes9x/libretro lifecycle, deadline-based frame pacing, direct RGB565 OpenGL upload, audio, Player-1 routing, dual-display selection, non-focusable Presentation behavior, and JNI WRAM bounds checks.

## Safe area for game work

Normally edit only:

- `app/src/main/assets/game/zamn_profile.json` — verified fields exported by memory discovery.
- `app/src/main/java/com/example/snestemplate/game/second_screen/ZamnSecondScreen.kt` — replace its Android layout and interaction logic freely.
- `app/src/main/java/com/example/snestemplate/game/` — game-only assets and logic.
- `app/src/main/java/com/example/snestemplate/game/rom/RomGate.kt` — ROM hash gate + one-time ROM selection persistence.
- `app/src/main/java/com/example/snestemplate/game/menu/BootMenu.kt` — pre-game menu (PLAY / SELECT ROM) + controller navigation (UP/DOWN select, A confirm, B/Y/BACK dialog close).
- `app/src/main/java/com/example/snestemplate/game/menu/StandbyScreen.kt` — second-display standby screen shown while the boot menu is up (just the blurred Level 22 map, no text); replaced automatically by ZamnSecondScreen on PLAY.

The Presentation is plumbing: it attaches a `SecondScreenModule`. A game module creates any Android `View` it needs; there is intentionally no fixed HUD/widget framework.

## Second display during the boot menu

Before PLAY, the core (and therefore the live memory profile) does not exist, so the dash display shows `StandbyScreen` — just the bundled `res/drawable-nodpi/level22_blur.png` (pre-blurred offline from `assets/map/level_22.png`, no runtime blur cost, no text). `MainActivity.showStandby()` raises it in `onCreate`; `configureSecondaryDisplay()` dismisses it and raises `ZamnSecondScreen` when the core starts. Gamepad keys before start are routed to `BootMenu.handleKey()` in `MainActivity.dispatchKeyEvent`; after start they go to the core as before.

## ROM gate (external ROM, PLAY gated on a SHA-256 match)

The app bundles **no ROM** — it is always loaded from a file on the user's device. `zamn_profile.json` only fits the exact ZAMN build it was discovered on, so `RomGate` accepts a ROM only if its SHA-256 equals the hardcoded `REFERENCE_SHA256` (the hash of the original dev ROM; the ROM itself is not in the repo to derive it from). The user's one-time selection (SAF content URI or absolute path) persists at `filesDir/rom/selection.json` and is re-verified at every launch. Play stages the ROM to `filesDir/roms/active.sfc` while re-hashing the staged bytes (a file that changes between selection and Play is still refused). There is **no bundled fallback**: if no selection verifies, PLAY stays disabled until a correct ROM is selected. `MainActivity` was touched for this feature (menu overlay, SAF browse result, `nativeStop` only when the core actually started) — keep that wiring intact.

## Verified memory access

Game UI receives `GameMemory` and uses logical names:

```kotlin
memory.readU8("health")
memory.writeU8("health", 10)
```

Fields are defined in the profile JSON with `offset`, `type`, and `access`. Only `u8` is implemented now. `GameMemory` checks field existence, type, permissions, and RAM bounds before calling JNI; native code retains its own bounds checks. Add new entries only from verified memory-discovery data — never guess addresses.

`ZamnSecondScreen` is only a proof module. It may be entirely replaced for maps, inventory, custom graphics, and touch interactions without changing emulator internals.

## Launcher icon

The icon is an **adaptive icon** (minSdk 26, so it applies on every supported device):

- `res/mipmap-anydpi-v26/ic_launcher.xml` — declares the two layers
- `res/drawable/ic_launcher_bg.xml` — slate fill + acid-green ring at the full canvas edge; the launcher's circular mask clips it into a perfect rim
- `res/drawable/ic_launcher_fg.png` — sprite at native 4× pixel scale on a 432×432 (108dp) canvas, centered

**Why:** some launcher3 builds treat plain bitmaps as legacy icons — white circular plate + square bitmap, which looks wrong. Adaptive icons let the launcher composite our layers and apply its own mask; the white plate never appears. Do not "fix" the legacy `mipmap-*/ic_launcher.png` bitmaps for visual problems on those launchers — they are fallbacks only; edit the two adaptive layers instead.

Current foreground: Zeke (`app/src/main/res/drawable/ic_launcher_fg.png`).


