package com.example.snestemplate.game.second_screen

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.snestemplate.R
import com.example.snestemplate.runtime.memory.GameMemory
import com.example.snestemplate.runtime.state.EmulatorState
import java.io.File

/**
 * ZAMN (Zombies Ate My Neighbors) second-screen UI — bold redesign.
 *
 * Screen: ~538 x 468 dp (1240 x 1080 px, density ~2.31).
 *
 * LAYOUT (top to bottom):
 *   BACKGROUND: castle wall tile (repeating) with a light scrim; no header
 *   DEBUG    : header button opens a POPUP WINDOW (scrim + card) that overlays
 *              the UI without pushing it down. Card: "LEVEL SELECT" reveals
 *              the 48-level grid (tap N writes "current_level", 0x1E7C,
 *              verified); "TAP GAIN WEAPONS" toggle: tapping any weapon cell
 *              grants that weapon + ammo and selects it; "TAP GAIN ITEMS"
 *              toggle: tapping any item cell grants one. Scrim tap / CLOSE
 *              hides the window.
 *   (The old WEAPON/HP/ammo HUD rows are gone — the header stays slim.)

 *   ITEMS    : horizontally scrollable strip of item cells, auto-sorted with
 *              owned items first (above the main row)
 *   MAIN     : MINIMAP (left, LIVES chip top-left, NEIGHBORS chip top-right,
 *              DEBUG button bottom-left, SAVE STATE / LOAD STATE buttons
 *              bottom-right)  |  WEAPON list (right, scrolling)
 *
 * SAFETY MODEL (no guessing, per AGENTS.md) — buttons reflect ONLY verified
 * memory, nothing else:
 *   WEAPONS (12 real released weapons — every id AND count slot verified,
 *   human-identified; "weapon_selected" slot 0x1CBC two-way write confirmed):
 *     0 squirt gun    count 0x1CCC (u16)   7 tomatos   count 0x1CDA
 *     1 fire ext      count 0x1CCE        8 popsicle  count 0x1CDC
 *     2 ray gun       count 0x1CD0       10 plates    count 0x1CE0
 *     3 weed whacker  count 0x1CD2       11 fork      count 0x1CE2
 *     4 holy cross    count 0x1CD4       12 football  count 0x1CE4
 *     5 cannon        count 0x1CD6        9 red peppers  count 0x1CDE (UNRELEASED)
 *     6 soda cans     count 0x1CD8       13 flamethrower count 0x1CE6 (UNRELEASED,
 *                                               pinned bottom)
 *   Ownership model (verified on soda + fire ext.): a weapon is selectable iff
 *   its count slot > 0; the game ignores selecting a weapon the player doesn't
 *   own — so unowned rows render dimmed and non-tappable.
 *   Excluded: IDs 14-20 (occupied slots that are NOT real weapons).
 *   RED PEPPERS (ID 9) + FLAMETHROWER (ID 13) — both UNRELEASED in retail —
 *   are included per user request; the flamethrower is pinned to the BOTTOM
 *   of the weapon list (never floats up with the owned weapons).
 *   ITEMS (11 VERIFIED slots — the full stride-2 inventory block at
 *   0x1D0C..0x1D20, low byte = even offset; every slot causally verified via
 *   Mesen-bridge write tests, human-confirmed identities):
 *       keys       0x1D0C  -> GRANT one (tap, increment n -> n+1)  [COUNT slot]
 *       speed shoes 0x1D0E -> GRANT one (tap)
 *       red potion  0x1D10  -> GRANT one (tap)
 *       blue potion 0x1D12  -> GRANT one (tap)
 *       random potion 0x1D14 -> GRANT one (tap)
 *       silver potion 0x1D16 -> GRANT one (tap)   [not in retail item list]
 *       gold potion 0x1D18  -> GRANT one (tap)    [not in retail item list]
 *       medkit     0x1D1A  -> USE: consume one (0x1D1A <- n-1) + heal (0x1CB8 <- 10)
 *       pandora    0x1D1C  -> GRANT one (tap)
 *       skull key  0x1D1E  -> GRANT one (tap)
 *       clown      0x1D20  -> GRANT one (tap)
 *   GATING: item granting is behind the "TAP GAIN ITEMS" toggle; when OFF,
 *   MEDKIT keeps consume+heal and other item taps are inert. "TAP GAIN
 *   WEAPONS" makes any weapon tap grant the weapon + per-weapon ammo
 *   (GRANT_WEAPON_AMMO; squirt 999).
 *   EXCLUDED: 0x1D22 is the walkie-talkie/RADIO — writing it grants a HUD sprite
 *   but the user confirmed it is NOT a real item; nothing past 0x1D22 is
 *   referenced by the UI.
 *   Sprites come from "ZAMN Resources/" (4x NN copies in drawable-nodpi).
 *
 * Live data comes from verified profile fields via GameMemory:
 *   "health" (u8, 0-10), "weapon_selected" (u8, 0x1CBC),
 *   per-weapon count slots 0x1CCC..0x1CE4 (squirt u16 via
 *   "ammo_low"/"ammo_high", all other weapons u8: "fire_ext_count",
 *   "ray_gun_count".."cannon_count", "soda_cans", "tomato_count",
 *   "popsicle_count", "plate_count", "fork_count", "football_count"),
 *   "neighbors_remaining" (u8, 0x1D52, read_only),
 *   "lives" (u8, 0x1D4C, read_only — Zeke spare lives),
 *   "medkit_count" (u8, 0x1D1A), "pandora_box" (0x1D1C),
 *   "clown_decoy" (0x1D20), "skeleton_key" (0x1D1E),
 *   "current_level" (u8, 0x1E7C — minimap map selection + DEBUG level-jump).
 *
 * SAVE/LOAD STATE (minimap bottom-right): full libretro core snapshots via
 * EmulatorState (frontend.cpp nativeSaveState/nativeLoadState, core-locked
 * like the RAM accessors). Snes9x's unfreeze validates magic + version and
 * commits only on full success, so a corrupt/incompatible file is rejected
 * without touching the running game. The snapshot is persisted in app
 * private storage at filesDir/state/default.lbs, so SAVE survives app
 * restarts and LOAD can recover a previous session.
 */
class ZamnSecondScreen(
  private val memory: GameMemory,
  private val state: EmulatorState,
) : SecondScreenModule {

  private val handler = Handler(Looper.getMainLooper())

  private lateinit var neighborsValue: TextView
  private lateinit var livesValue: TextView

  // Weapons panel: scrolling list, auto-sorted (owned first).
  private lateinit var weaponStack: LinearLayout
  private lateinit var weaponViews: List<View>
  private var weaponOrderKey = ""

  // Items strip: horizontally scrollable, auto-sorted (owned first).
  private var itemStack: LinearLayout? = null
  private var itemViews: List<View> = emptyList()
  private var itemOrderKey = ""

  /** Item cell size, dp (bigger than the old 44dp row — HP/ammo rows are gone). */
  private val ITEM_CELL_DP = 56f
  // In-game HUD "neighbors to save" goal (most levels). 0x1D52 holds the
  // number of neighbors still PRESENT in the level, which can exceed the
  // goal (e.g. 16) - the HUD caps at the goal, so we mirror it:
  // displayed = min(raw, goal). Below the goal both agree 1:1.
  private val NEIGHBOR_GOAL = 10

  // Interaction modes — a cell's behavior is fixed by mode, never by guessing.
  private enum class Mode {
    WEAPON,     // verified weapon id -> selectable iff owned (count slot > 0)
    ITEM_USE,   // verified item, gated use action (medkit: consume+heal)
    ITEM_GRANT, // verified item, always tappable (tap grants one)
    DISABLED    // unverified placeholder -> dimmed, never tappable, reads/writes nothing
  }

  /** Every icon/text cell on the interactive area, with its state metadata. */
  private class Cell(
    val view: View,
    val bg: GradientDrawable,
    val label: TextView?,    // text-cell label (unused when a sprite is present)
    val badge: TextView?,    // live count badge
    val weaponId: Int?,      // verified selectable weapon id, or null
    val countField: String?, // profile field backing the live count badge, or null
    val mode: Mode,
  )
  private val cells = mutableListOf<Cell>()

  // DEBUG popup window (header button toggles; overlays the UI, no layout push).
  private lateinit var debugOverlay: FrameLayout
  private var tapGainWeapons = false  // ON: tapping any weapon cell grants it + ammo
  private var tapGainItems = false    // ON: tapping any item cell grants one
  private var showDebugGear = false   // ON: debug gear (RED PEPPERS / FLAMETHROWER /
                                       // SILVER + GOLD POTION) visible in the lists.
                                       // OFF (default) = hidden. NEVER grants them.
                                       // - visibility only.
  private var showCoords = false      // ON: show the live P1 X/Y readout in the
                                       // minimap corner. OFF (default) = hidden,
                                       // so the map stays clean.

  /** Tiny live P1 X/Y readout at the minimap's bottom-right corner. */
  private var coordText: TextView? = null
  private var minimapView: MinimapView? = null

  /** Persistent save-state blob: filesDir/state/default.lbs (set in createView). */
  private var stateFile: File? = null

  private data class WeaponSpec(
    val sprite: Int?, val label: String, val weaponId: Int?, val countField: String?,
    val pinned: Boolean = false, // pinned = always kept at the bottom of the list
  )
  private data class ItemSpec(
    val sprite: Int?,
    val label: String,
    val countField: String?,
    val badge: Boolean,
    val mode: Mode,
    val increment: Boolean = false, // true = tap adds one (n -> n+1); false = tap sets to GRANT_VALUE
  )

  /** 12 real released weapons — every id and count slot verified
   *  (human-identified, causal write-tested) — plus the two UNRELEASED
   *  weapons the user asked for: RED PEPPERS (9) and FLAMETHROWER (13,
   *  pinned to the bottom). Excluded: IDs 14-20 (not real weapons). */
  private val WEAPONS = listOf(
    WeaponSpec(R.drawable.squirt_gun, "SQUIRT GUN", 0, "squirt"),
    WeaponSpec(R.drawable.fire_ext, "FIRE EXTINGUISHER", 1, "fire_ext_count"),
    WeaponSpec(R.drawable.ray_gun, "RAY GUN", 2, "ray_gun_count"),
    WeaponSpec(R.drawable.weed_wacker, "WEED WHACKER", 3, "weed_wacker_count"),
    WeaponSpec(R.drawable.holy_cross, "HOLY CROSS", 4, "holy_cross_count"),
    WeaponSpec(R.drawable.cannon, "CANNON", 5, "cannon_count"),
    WeaponSpec(R.drawable.soda_cans, "SODA CANS", 6, "soda_cans"),
    WeaponSpec(R.drawable.tomato, "TOMATOS", 7, "tomato_count"),
    WeaponSpec(R.drawable.popsicle, "POPSICLE", 8, "popsicle_count"),
    WeaponSpec(R.drawable.dishes, "PLATES", 10, "plate_count"),
    WeaponSpec(R.drawable.silverware, "FORK", 11, "fork_count"),
    WeaponSpec(R.drawable.football, "FOOTBALL", 12, "football_count"),
    WeaponSpec(R.drawable.red_peppers, "RED PEPPERS", 9, "red_pepper_count"),
    WeaponSpec(R.drawable.flame_thrower, "FLAMETHROWER", 13, "flamethrower_count", pinned = true),
  )

  /** Item row — the 11 VERIFIED inventory slots (stride-2 block 0x1D0C..0x1D20;
   *  every offset causally write-verified via the Mesen bridge, human-identified:
   *  keys [COUNT slot — tap increments], speed shoes, red/blue/random/silver/gold
   *  potions, medkit [USE: consume+heal], pandora, skull key, clown).
   *  0x1D22 (walkie-talkie) stays EXCLUDED — not a real item. */
  private val ITEMS = listOf(
    ItemSpec(R.drawable.medkit, "MEDKIT", "medkit_count", true, Mode.ITEM_USE),
    ItemSpec(R.drawable.gold_chest, "PANDORA", "pandora_box", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.clown, "CLOWNS", "clown_decoy", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.skull_key, "SKULL KEY", "skeleton_key", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.blue_shoes, "SPEED SHOES", "speed_shoes", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.red_potion, "RED POTION", "red_potion", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.blue_potion, "BLUE POTION", "blue_potion", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.random_potion, "RANDOM POTION", "random_potion", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.silver_potion, "SILVER POTION", "silver_potion", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.gold_potion, "GOLD POTION", "gold_potion", true, Mode.ITEM_GRANT),
    ItemSpec(R.drawable.key, "KEYS", "key_count", true, Mode.ITEM_GRANT, increment = true),
  )

  /** DEBUG gear - UNRELEASED / non-retail entries, hidden by default.
   *  The "SHOW DEBUG WEAPONS/ITEMS" debug toggle (default OFF) reveals
   *  exactly these in their lists; revealing NEVER grants them (it only
   *  controls list visibility). */
  private val DEBUG_WEAPON_IDS = setOf(9, 13)          // RED PEPPERS, FLAMETHROWER
  private val DEBUG_ITEM_FIELDS = setOf("silver_potion", "gold_potion")

  /** A weapon row is shown iff it is not debug gear, or the debug toggle is ON. */
  private fun weaponVisible(w: WeaponSpec): Boolean =
    (w.weaponId == null || w.weaponId !in DEBUG_WEAPON_IDS) || showDebugGear

  /** An item cell is shown iff it is not debug gear, or the debug toggle is ON. */
  private fun itemVisible(i: ItemSpec): Boolean =
    (i.countField == null || i.countField !in DEBUG_ITEM_FIELDS) || showDebugGear

  /** Value written by an item-grant tap (one unit). */
  private val GRANT_VALUE = 1

  private val poller = object : Runnable {
    override fun run() {
      tick()
      handler.postDelayed(this, 200)
    }
  }

  // Palette — high contrast so cells clearly stand off the dark background.
  private val acid = Color.rgb(112, 255, 100)
  private val water = Color.rgb(86, 205, 255)
  private val ink = Color.rgb(242, 245, 244)
  private val dim = Color.rgb(150, 165, 180)
  private val bgDeep = Color.rgb(13, 16, 15)
  private val cellOwned = Color.rgb(48, 74, 104)    // slate-blue: clearly visible
  private val cellSelected = Color.rgb(30, 66, 120)
  private val cellUnowned = Color.rgb(28, 34, 42)   // recedes, but still a distinct block

  override fun createView(context: Context): View {
    stateFile = File(context.filesDir, "state/default.lbs")
    val root = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 10f))
      // Castle wall tile (64px sprite) repeated across the whole screen via a
      // REPEAT BitmapShader (this SDK's jar has no TileDrawable class);
      // a light dark scrim keeps the bright stone from shouting and lets the
      // dark panels read cleanly on top.
      val brickBmp = BitmapFactory.decodeResource(resources, R.drawable.brick_wall)
      val brickShader = BitmapShader(brickBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
      val brickPaint = Paint()
      brickPaint.shader = brickShader
      val brickTile = object : Drawable() {
        override fun draw(canvas: Canvas) {
          canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), brickPaint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.OPAQUE
      }
      val scrim = ColorDrawable(Color.argb(56, 0, 0, 0))
      background = LayerDrawable(arrayOf(brickTile, scrim))
    }
    root.addView(bottomSection(context), LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    // Frame the content in a FrameLayout so the DEBUG window can OVERLAY it
    // (scrim + card) instead of pushing the layout down.
    val frame = FrameLayout(context)
    frame.addView(root, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    frame.addView(buildDebugOverlay(context))
    frame.post { Log.i(TAG, "ZAMN UI bounds: ${root.width}x${root.height}px") }
    handler.post(poller)
    return frame
  }

  override fun onStop() {
    handler.removeCallbacks(poller)
  }

  // -- live updates ---------------------------------------------------------

  private fun tick() {
    try {
      val selected = memory.readU8("weapon_selected")
      val health = memory.readU8("health")
      val medkits = memory.readU8("medkit_count")

      // Verified per-weapon count snapshot (all HUD counts render HEX).
      val counts = mapOf(
        "squirt" to (((memory.readU8("ammo_high") and 0xFF) shl 8) or
            (memory.readU8("ammo_low") and 0xFF)),
        "fire_ext_count" to memory.readField("fire_ext_count"),
        "ray_gun_count" to memory.readField("ray_gun_count"),
        "weed_wacker_count" to memory.readField("weed_wacker_count"),
        "holy_cross_count" to memory.readField("holy_cross_count"),
        "cannon_count" to memory.readField("cannon_count"),
        "soda_cans" to memory.readField("soda_cans"),
        "tomato_count" to memory.readField("tomato_count"),
        "popsicle_count" to memory.readField("popsicle_count"),
        "plate_count" to memory.readField("plate_count"),
        "fork_count" to memory.readField("fork_count"),
        "football_count" to memory.readField("football_count"),
        "red_pepper_count" to memory.readField("red_pepper_count"),
        "flamethrower_count" to memory.readField("flamethrower_count"),
        "medkit_count" to medkits,
        "pandora_box" to memory.readField("pandora_box"),
        "clown_decoy" to memory.readField("clown_decoy"),
        "skeleton_key" to memory.readField("skeleton_key"),
        "key_count" to memory.readField("key_count"),
        "speed_shoes" to memory.readField("speed_shoes"),
        "red_potion" to memory.readField("red_potion"),
        "blue_potion" to memory.readField("blue_potion"),
        "random_potion" to memory.readField("random_potion"),
        "silver_potion" to memory.readField("silver_potion"),
        "gold_potion" to memory.readField("gold_potion"),
      )

      // NEIGHBORS counter (HUD shows this decimal).
      // LIVES counter (Zeke spare lives; 0 = next death is game over).
      val lives = memory.readU8("lives")
      if (lives in 0..255) {
        livesValue.text = lives.toString()
        // Amber = danger warning (next death ends the run); normal = ink.
        livesValue.setTextColor(if (lives == 0) Color.rgb(255, 202, 54) else ink)
      }

      // NEIGHBORS counter (HUD shows this decimal).
      val n = memory.readU8("neighbors_remaining")
      // Mirror the in-game HUD: it shows "still to save" (capped at the
      // level goal, 10 on most levels), while 0x1D52 is "still present"
      // (can be higher, e.g. 16). min(raw, goal) matches the HUD exactly:
      // 16 -> 10 at start, then 9, 8, ... tracking 1:1 once below the goal.
      if (n in 0..255) neighborsValue.text = minOf(n, NEIGHBOR_GOAL).toString()

      // MINIMAP: live P1 WORLD X/Y — 16-bit little-endian
      // (worldX = 0x0130 | 0x0131<<8, worldY = 0x0132 | 0x0133<<8; verified
      // zamn-p1x-4). The map image is a 1:1 render of world space.
      val px = (memory.readU8("p1_x_hi") shl 8) or memory.readU8("p1_x")
      val py = (memory.readU8("p1_y_hi") shl 8) or memory.readU8("p1_y")
      coordText?.let { ct ->
        val t = "X:$px Y:$py"
        if (ct.text.toString() != t) ct.text = t
      }
      val level = memory.readU8("current_level")
      minimapView?.setContext(level)
      if (level in MinimapView.MAP_LEVELS) {
        minimapView?.setPlayer(px, py)
      }

      // AUTO-SORT: owned weapons (verified count slot > 0) float to the top of
      // the scroll list; relative order is preserved within each group.
      reorderWeaponStack(WEAPONS.map { w ->
        !w.pinned && (w.countField?.let { counts[it] }?.let { it > 0 } ?: false)
      })

      // AUTO-SORT: owned items (verified count slot > 0) float to the front of
      // the item strip; relative order preserved within each group.
      reorderItemRow(ITEMS.map { it.countField?.let { f -> (counts[f] ?: 0) > 0 } ?: false })

      // PER-CELL STATE (color + tappable), driven purely by verified mode.
      cells.forEach { cell ->
        val selectedWeapon = cell.weaponId != null && cell.weaponId == selected
        val owned = cell.ownedCount(counts) > 0
        val selectable = when (cell.mode) {
          Mode.WEAPON -> cell.weaponId != null &&
            (owned || tapGainWeapons)  // owned iff count slot > 0; grant mode: any
          Mode.ITEM_GRANT -> tapGainItems   // granting is gated by the toggle
          Mode.ITEM_USE -> {               // medkit: grant when toggled, else USE
            if (tapGainItems) true
            else cell.countField == "medkit_count" && medkits > 0 && health < 10
          }
          Mode.DISABLED -> false
        }
        cell.badge?.let { b ->
          val t = cell.badgeText(counts)
          if (b.text.toString() != t) b.text = t
        }
        applyCellState(cell, selectedWeapon, owned, selectable)
      }
    } catch (e: Exception) {
      // Core not running yet or field missing; keep last known values on screen.
    }
  }

  /** Live count for a cell (from the per-tick verified snapshot), or -1 if none. */
  private fun Cell.ownedCount(counts: Map<String, Int>): Int =
    countField?.let { counts[it] } ?: -1

  /** Badge text for a cell from the per-tick verified snapshot ("" = hidden).
   *  Weapon counts render HEX (as the in-game HUD does); item counts decimal. */
  private fun Cell.badgeText(counts: Map<String, Int>): String {
    val v = countField?.let { counts[it] } ?: return ""
    if (v < 0) return ""
    return if (mode == Mode.WEAPON) {
      if (v > 0) v.toString(16).uppercase() else ""
    } else {
      v.toString()
    }
  }

  private fun applyCellState(cell: Cell, selected: Boolean, owned: Boolean, selectable: Boolean) {
    cell.bg.setColor(
      when {
        selected -> cellSelected
        owned -> cellOwned
        else -> cellUnowned
      }
    )
    cell.bg.setStroke(
      dp(cell.view.context, 1f),
      when {
        selected -> water
        owned -> Color.rgb(90, 140, 190)
        else -> Color.rgb(70, 85, 100)
      }
    )
    // TAPPABILITY is fixed by verified mode (see tick()), never by guessing.
    cell.view.isEnabled = selectable
    cell.view.isClickable = selectable
    cell.view.alpha = when {
      selected -> 1f
      selectable -> 1f
      cell.mode == Mode.DISABLED -> 0.4f   // clearly a placeholder
      else -> 0.6f
    }
    cell.label?.setTextColor(if (selected || owned || selectable) ink else dim)
  }

  // -- top rows ------------------------------------------------------------

  /**
   * DEBUG popup window: a dimming scrim over the whole UI with a centered,
   * scrollable card (so the 48-level grid never overflows the display).
   * Card contents, top to bottom:
   *   LEVEL SELECT  button -> reveals/hides the 48-level grid (tap N writes
   *                          "current_level", 0x1E7C, verified: at the MAIN
   *                          MENU, then start a new game -> starts on N)
   *   TAP GAIN WEAPONS toggle: ON = tapping any weapon cell grants that
   *                          weapon (weapon-specific ammo, GRANT_WEAPON_AMMO;
   *                          squirt ammo u16 <- 999) and selects it
   *   TAP GAIN ITEMS   toggle: ON = tapping any item cell grants one (KEYS
   *                          increments n -> n+1); OFF = item taps are inert
   *                          except MEDKIT, which keeps consume+heal USE
   *   SHOW DEBUG WEAPONS/ITEMS toggle (default OFF): reveals the UNRELEASED
   *                          RED PEPPERS + FLAMETHROWER (weapons) and SILVER
   *                          + GOLD POTION (items) in their lists. Never
   *                          grants them - visibility only.
   * Starts GONE; the header DEBUG button toggles it; scrim tap / CLOSE hides.
   */

  private fun buildDebugOverlay(context: Context): View {
    val amber = Color.rgb(255, 202, 54)
    val overlay = FrameLayout(context).apply { visibility = View.GONE }

    // Scrim: dims the UI behind; tapping it closes the window.
    val scrim = View(context).apply {
      setBackgroundColor(Color.argb(165, 4, 6, 5))
      setOnClickListener {
        overlay.visibility = View.GONE
        Log.i(TAG, "DEBUG window CLOSED (scrim tap)")
      }
    }
    overlay.addView(scrim, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    // Card: rounded, amber-rimmed, scrollable, absorbs taps (no fall-through).
    val cardScroll = ScrollView(context).apply {
      background = GradientDrawable().apply {
        setColor(Color.rgb(20, 25, 26))
        cornerRadius = dp(context, 14f).toFloat()
        setStroke(dp(context, 2f), Color.argb(190, 255, 202, 54))
      }
      isVerticalScrollBarEnabled = true
      isClickable = true
      setPadding(dp(context, 16f), dp(context, 14f), dp(context, 16f), dp(context, 14f))
    }
    val card = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      clipChildren = false
      clipToPadding = false
    }

    card.addView(TextView(context).apply {
      text = "DEBUG"
      textSize = 15f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      paint.letterSpacing = 0.4f
      setTextColor(amber)
      setPadding(0, 0, 0, dp(context, 12f))
    })

    // LEVEL SELECT button -> reveals the level grid right below it.
    val gridSection = levelGridSection(context, amber)
    gridSection.visibility = View.GONE
    var gridShown = false
    val levelBtn = TextView(context).apply {
      text = "LEVEL SELECT"
      textSize = 12f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      paint.letterSpacing = 0.2f
      gravity = Gravity.CENTER
      setTextColor(Color.rgb(14, 14, 8))
      background = GradientDrawable().apply {
        setColor(amber)
        cornerRadius = dp(context, 8f).toFloat()
      }
      setPadding(0, dp(context, 13f), 0, dp(context, 13f))
      elevation = dp(context, 2f).toFloat()
      setOnClickListener {
        gridShown = !gridShown
        gridSection.visibility = if (gridShown) View.VISIBLE else View.GONE
        text = if (gridShown) "HIDE LEVELS" else "LEVEL SELECT"
        Log.i(TAG, "DEBUG: level grid ${if (gridShown) "SHOWN" else "HIDDEN"}")
      }
    }
    card.addView(levelBtn)
    card.addView(gridSection)
    card.addView(margin(context, 10f))

    // Toggles: TAP GAIN WEAPONS, then TAP GAIN ITEMS.
    card.addView(makeToggle(context, "TAP GAIN WEAPONS",
      { tapGainWeapons }, { tapGainWeapons = it },
      { on -> Log.i(TAG, "DEBUG: tap-gain WEAPONS ${if (on) "ON" else "OFF"}") }))
    card.addView(margin(context, 8f))
    card.addView(makeToggle(context, "TAP GAIN ITEMS",
      { tapGainItems }, { tapGainItems = it },
      { on -> Log.i(TAG, "DEBUG: tap-gain ITEMS ${if (on) "ON" else "OFF"}") }))
    card.addView(margin(context, 8f))
    // SHOW DEBUG WEAPONS/ITEMS: reveals the UNRELEASED RED PEPPERS +
    // FLAMETHROWER (weapons) and SILVER + GOLD POTION (items) in their lists.
    // Default OFF (hidden). Revealing NEVER grants them - list visibility only.
    card.addView(makeToggle(context, "SHOW DEBUG WEAPONS/ITEMS",
      { showDebugGear }, { showDebugGear = it },
      { on ->
        Log.i(TAG, "DEBUG: show-debug gear ${if (on) "ON" else "OFF"} " +
          "(red peppers, flamethrower, silver+gold potion - visibility only)")
        // Re-filter both lists by the new visibility state, then refresh.
        weaponOrderKey = ""
        itemOrderKey = ""
        tick()
      }))
    card.addView(margin(context, 8f))
    // SHOW X/Y COORDS: the live P1 X/Y readout in the minimap corner.
    // Default OFF so the map stays clean; ON reveals it.
    card.addView(makeToggle(context, "SHOW X/Y COORDS",
      { showCoords }, { showCoords = it },
      { on ->
        Log.i(TAG, "DEBUG: show X/Y coords ${if (on) "ON" else "OFF"}")
        coordText?.visibility = if (on) View.VISIBLE else View.GONE
      }))

    // CLOSE.
    card.addView(margin(context, 12f))
    card.addView(TextView(context).apply {
      text = "CLOSE"
      textSize = 11f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      paint.letterSpacing = 0.25f
      gravity = Gravity.CENTER
      setTextColor(ink)
      background = GradientDrawable().apply {
        setColor(Color.rgb(28, 34, 42))
        setStroke(dp(context, 1f), Color.rgb(70, 85, 100))
        cornerRadius = dp(context, 8f).toFloat()
      }
      setPadding(0, dp(context, 11f), 0, dp(context, 11f))
      elevation = dp(context, 2f).toFloat()
      setOnClickListener { overlay.visibility = View.GONE }
    })

    cardScroll.addView(card, FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    overlay.addView(cardScroll, FrameLayout.LayoutParams(
      dp(context, 360f), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
    debugOverlay = overlay
    return overlay
  }

  /** 48-button level grid (1..48); each tap writes "current_level" (0x1E7C). */
  private fun levelGridSection(context: Context, amber: Int): View {
    val section = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(context, 12f), 0, 0)
    }
    section.addView(TextView(context).apply {
      text = "GO TO LEVEL   (verified: at the main menu, then start a new game)"
      textSize = 8f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      paint.letterSpacing = 0.15f
      setTextColor(amber)
      setPadding(0, 0, 0, dp(context, 6f))
    })
    val cols = 8
    val size = dp(context, 30f)
    for (r in 0 until 48 / cols) {
      val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
      for (c in 0 until cols) {
        val n = r * cols + c + 1
        row.addView(
          TextView(context).apply {
            text = n.toString()
            textSize = 11f
            setTypeface(Typeface.MONOSPACE)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(amber)
            background = GradientDrawable().apply {
              setColor(Color.rgb(28, 34, 42))
              setStroke(dp(context, 1f), Color.argb(140, 150, 165, 180))
              cornerRadius = dp(context, 5f).toFloat()
            }
            setOnClickListener { setDebugLevel(n) }
          },
          LinearLayout.LayoutParams(size, size).apply {
            rightMargin = dp(context, 6f)
            bottomMargin = dp(context, 5f)
          }
        )
      }
      section.addView(row)
    }
    return section
  }

  /** Toggle row: label left, ON/OFF pill right. Click flips the state. */
  private fun makeToggle(
    context: Context,
    label: String,
    get: () -> Boolean,
    set: (Boolean) -> Unit,
    onChange: (Boolean) -> Unit,
  ): View {
    val bg = GradientDrawable().apply {
      setColor(Color.rgb(28, 34, 42))
      cornerRadius = dp(context, 8f).toFloat()
      setStroke(dp(context, 1f), Color.rgb(70, 85, 100))
    }
    val pill = TextView(context).apply {
      textSize = 9f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      setPadding(dp(context, 12f), dp(context, 3f), dp(context, 12f), dp(context, 3f))
    }
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = bg
      minimumHeight = dp(context, 44f)
      setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f))
      elevation = dp(context, 2f).toFloat()
      addView(TextView(context).apply {
        text = label
        textSize = 10.5f
        setTypeface(Typeface.MONOSPACE)
        paint.isFakeBoldText = true
        paint.letterSpacing = 0.15f
        setTextColor(ink)
      }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      addView(pill)
    }
    fun refresh() {
      val on = get()
      bg.setColor(if (on) Color.argb(70, 112, 255, 100) else Color.rgb(28, 34, 42))
      bg.setStroke(dp(context, 1f),
        if (on) Color.argb(220, 112, 255, 100) else Color.rgb(70, 85, 100))
      pill.text = if (on) "ON" else "OFF"
      pill.setTextColor(if (on) Color.rgb(8, 12, 9) else dim)
      pill.background = GradientDrawable().apply {
        setColor(if (on) acid else Color.rgb(48, 54, 62))
        cornerRadius = dp(context, 6f).toFloat()
      }
    }
    row.setOnClickListener { val on = !get(); set(on); refresh(); onChange(on) }
    refresh()
    return row
  }

  /**
   * "Some" ammo per weapon for a TAP GAIN WEAPONS tap (count slots are u8).
   * Keyed by weapon ID (0 = squirt, which is the u16 ammo special case).
   * Amounts chosen by the player; 99 as the fallback for any ID added later.
   */
  private val GRANT_WEAPON_AMMO: Map<Int, Int> = mapOf(
    1 to 99,    // fire extinguisher
    2 to 5,     // ray gun (15 glitched the in-game counter; 5 is safe)
    3 to 50,    // weed wacker
    4 to 20,    // holy cross
    5 to 5,     // large cannon ("large gun")
    6 to 20,    // soda cans (not specified; same as other throwables)
    7 to 20,    // tomatoes
    8 to 20,    // popsicles
    9 to 20,    // red peppers
    10 to 20,   // plates
    11 to 20,   // forks
    12 to 5,    // football (10 glitched the in-game counter)
    13 to 99,   // flamethrower
  )
  /** Squirt ammo is a u16 (ammo_low/ammo_high) — grant a bigger "some". */
  private val GRANT_SQUIRT_AMMO = 999

  /**
   * WEAPON GRANT (TAP GAIN WEAPONS ON): give the player the weapon —
   * count slot <- GRANT_WEAPON_AMMO[id] (squirt: ammo u16 <- 999) — and
   * select it. Only verified profile fields are written.
   */
  private fun grantWeapon(spec: WeaponSpec) {
    runCatching {
      val id = spec.weaponId ?: return@runCatching
      if (id == 0) {
        val ok = memory.writeU8("ammo_low", GRANT_SQUIRT_AMMO and 0xFF)
        val ok2 = memory.writeU8("ammo_high", (GRANT_SQUIRT_AMMO shr 8) and 0xFF)
        Log.i(TAG, "grant: squirt ammo <- $GRANT_SQUIRT_AMMO (ok=$ok,$ok2)")
      } else {
        val amt = GRANT_WEAPON_AMMO[id] ?: 99
        val f = spec.countField
        if (f != null) {
          val ok = memory.writeU8(f, amt)
          Log.i(TAG, "grant: $f <- $amt (ok=$ok)")
        }
      }
      val sel = memory.writeU8("weapon_selected", id)
      Log.i(TAG, "grant: ${spec.label} selected (weapon_selected <- $id, ok=$sel)")
    }.onFailure { Log.w(TAG, "weapon grant failed for ${spec.label}", it) }
  }

  /** Write the chosen level to the verified "current_level" slot (0x1E7C). */
  private fun setDebugLevel(n: Int) {
    try {
      val ack = memory.writeU8("current_level", n)
      minimapView?.setContext(n)   // instant minimap response (tick() re-reads within 200 ms)
      Log.i(TAG, "DEBUG: set level $n (write ack=$ack)")
    } catch (e: Exception) {
      Log.e(TAG, "DEBUG: set level $n FAILED", e)
    }
  }

  // -- bottom section: [item row] over [minimap | weapon grid] --------------

  private fun bottomSection(context: Context): View {
    val section = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
    }
    section.addView(itemsRow(context))
    section.addView(spacer(context, 10f))
    val main = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val minimap = minimapPlaceholder(context)
    // ~64% of the row: minimap gets the bulk, weapon panel stays slim but
    // wide enough for sprite + name (names may wrap to 2 lines).
    minimap.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.8f)
    main.addView(minimap)
    main.addView(margin(context, 10f))
    val panel = weaponPanel(context)
    panel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
    main.addView(panel)
    section.addView(main, LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    return section
  }

  /**
   * Weapons panel on the RIGHT of the minimap: big rows (sprite + name +
   * live hex count), ~5 visible at a time in a vertical ScrollView.
   * All 7 verified weapon rows are selectable (tap writes weapon_selected);
   * throwables are dimmed placeholders. tick() keeps the list auto-sorted
   * with owned weapons on top.
   */
  private fun weaponPanel(context: Context): View {
    val scroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = true
      setPadding(dp(context, 6f), dp(context, 6f), dp(context, 4f), dp(context, 6f))
    }
    val stack = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      clipChildren = false
      clipToPadding = false
    }
    weaponStack = stack
    weaponViews = WEAPONS.map { w ->
      val bg = GradientDrawable().apply {
        setColor(cellUnowned)
        cornerRadius = dp(context, 10f).toFloat()
        setStroke(dp(context, 1f), Color.rgb(70, 85, 100))
      }
      val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bg
        setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
        elevation = dp(context, 1.5f).toFloat()
      }
      w.sprite?.let { res ->
        row.addView(
          ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(res)
            contentDescription = w.label
            layoutParams = LinearLayout.LayoutParams(dp(context, 34f), dp(context, 34f))
          }
        )
        row.addView(margin(context, 8f))
      }
      val name = TextView(context).apply {
        text = w.label
        textSize = 11.5f
        setTypeface(Typeface.MONOSPACE)
        paint.isFakeBoldText = true
        setTextColor(ink)
        maxLines = 2
      }
      row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      var countTv: TextView? = null
      if (w.countField != null) {
        countTv = TextView(context).apply {
          text = ""
          textSize = 13f
          setTypeface(Typeface.MONOSPACE)
          paint.isFakeBoldText = true
          setTextColor(water)
        }
        row.addView(countTv)
      }
      val mode = if (w.weaponId != null) Mode.WEAPON else Mode.DISABLED
      val cell = Cell(row, bg, name, countTv, w.weaponId, w.countField, mode)
      if (w.weaponId != null) {
        val id = w.weaponId
        val spec = w
        row.setOnClickListener {
          // only fires when tick() has enabled it
          if (tapGainWeapons) grantWeapon(spec) else writeWeapon(id)
        }
      }
      cells.add(cell)
      row
    }
    // Initial order: the VISIBLE weapons in canonical order (debug gear is
    // hidden by default; ownership + the debug toggle are applied by the
    // first tick()/reorderWeaponStack, and re-applied whenever they change).
    WEAPONS.indices.filter { weaponVisible(WEAPONS[it]) }.forEach { idx ->
      stack.addView(weaponViews[idx], LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 50f)
      ).apply { topMargin = dp(context, 5f) })
    }
    scroll.addView(stack)
    return scroll
  }

  /** Re-orders the scroll list: owned weapons first, stable within groups.
   *  Debug gear (RED PEPPERS / FLAMETHROWER) is only included when the
   *  "SHOW DEBUG WEAPONS/ITEMS" toggle is ON - the rest are simply not
   *  re-added, so the toggle shows/hides them without granting anything. */
  private fun reorderWeaponStack(ownedFlags: List<Boolean>) {
    val visible = WEAPONS.indices.filter { weaponVisible(WEAPONS[it]) }
    val target = visible.filter { ownedFlags[it] } + visible.filter { !ownedFlags[it] }
    val key = target.joinToString("") { it.toString() }
    if (key == weaponOrderKey) return
    weaponOrderKey = key
    val ctx = weaponStack.context
    weaponStack.removeAllViews()
    target.forEach { idx ->
      weaponStack.addView(weaponViews[idx], LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 50f)
      ).apply { topMargin = dp(ctx, 5f) })
    }
  }

  /**
   * Items strip: horizontally scrollable row of cells (like the weapon list,
   * but horizontal), auto-sorted with owned items first via reorderItemRow().
   * Bigger cells than the old 44dp row (now ITEM_CELL_DP) — the freed space
   * from the removed HP/ammo rows goes here.
   */
  private fun itemsRow(context: Context): View {
    val cell = dp(context, ITEM_CELL_DP)
    val stack = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      clipChildren = false
      clipToPadding = false
      setPadding(dp(context, 2f), dp(context, 3f), dp(context, 2f), dp(context, 3f))
    }
    itemStack = stack
    itemViews = ITEMS.map { item ->
      val v = makeCell(context, item.sprite, item.label, null, item.countField, item.badge,
        cell, itemActionFor(item), item.mode)
      // Debug gear (SILVER / GOLD POTION) is hidden by default; the strip is
      // re-sorted by the first tick()/reorderItemRow and by the "SHOW DEBUG
      // WEAPONS/ITEMS" toggle (visibility only, never grants).
      if (itemVisible(item)) {
        stack.addView(v, LinearLayout.LayoutParams(cell, cell).apply {
          leftMargin = dp(context, 4f)
        })
      }
      v
    }
    val scroll = HorizontalScrollView(context).apply {
      isVerticalScrollBarEnabled = false
      setOverScrollMode(View.OVER_SCROLL_NEVER)
      addView(stack, ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    return scroll
  }

  /** AUTO-SORT the item strip: owned items (verified count slot > 0) first,
   *  relative order preserved within each group (mirrors reorderWeaponStack). */
  private fun reorderItemRow(ownedFlags: List<Boolean>) {
    val stack = itemStack ?: return
    // Only VISIBLE items stay in the strip; debug gear (SILVER / GOLD POTION)
    // is excluded unless the "SHOW DEBUG WEAPONS/ITEMS" toggle is ON.
    val visible = ITEMS.indices.filter { itemVisible(ITEMS[it]) }
    val target = visible.filter { ownedFlags[it] } + visible.filter { !ownedFlags[it] }
    val key = target.joinToString("") { it.toString() }
    if (key == itemOrderKey) return
    itemOrderKey = key
    val ctx = stack.context
    val cell = dp(ctx, ITEM_CELL_DP)
    stack.removeAllViews()
    target.forEach { idx ->
      stack.addView(itemViews[idx], LinearLayout.LayoutParams(cell, cell).apply {
        leftMargin = dp(ctx, 4f)
      })
    }
  }

  /**
   * Minimap box: hosts the MinimapView (level 1-48 maps, LRU-loaded, live P1
   * marker) with the NEIGHBORS counter chip (sprite + count) overlaid
   * top-right, the LIVES counter chip (label + count) overlaid top-left,
   * the DEBUG button bottom-left, the SAVE STATE / LOAD STATE buttons
   * bottom-right, and the tiny live "X:n Y:n" readout above them (it is
   * hidden by default; the extra bottom margin keeps it clear of the
   * save/load button row when the SHOW X/Y COORDS toggle reveals it).
   */
  private fun minimapPlaceholder(context: Context): View {
    val box = FrameLayout(context).apply {
      background = GradientDrawable().apply {
        setColor(Color.rgb(16, 20, 19))
        setStroke(dp(context, 1f), Color.rgb(4, 6, 6))
        cornerRadius = dp(context, 12f).toFloat()
      }
      clipToOutline = true
    }
    val mm = MinimapView(context)
    minimapView = mm
    box.addView(
      mm,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // The X/Y readout (hidden by default) sits ABOVE the save/load button
    // row at bottom-right so the two never overlap when SHOW X/Y COORDS is
    // enabled: button row ~30dp + its 6dp margin + 6dp breathing room.
    val coords = TextView(context).apply {
      text = "X:-- Y:--"
      textSize = 9f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      setTextColor(Color.argb(230, 130, 240, 150))
      background = GradientDrawable().apply { setColor(Color.argb(140, 4, 10, 16)) }
      setPadding(dp(context, 8f), 0, dp(context, 10f), dp(context, 7f))
    }
    coordText = coords
    coords.visibility = if (showCoords) View.VISIBLE else View.GONE
    val coordsLp = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM or Gravity.END)
    coordsLp.bottomMargin = dp(context, 42f)
    coordsLp.rightMargin = dp(context, 4f)
    box.addView(coords, coordsLp)
    // LIVES counter chip (top-left corner): label + live spare-lives count
    // from "lives" (0x1D4C, read-only). Mirrors the NEIGHBORS chip on the
    // opposite corner; amber count at 0 = next death is game over.
    val lives = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = GradientDrawable().apply {
        setColor(Color.argb(200, 4, 10, 16))
        setStroke(dp(context, 1f), Color.argb(120, 112, 255, 100))
        cornerRadius = dp(context, 10f).toFloat()
        setPadding(dp(context, 8f), dp(context, 3f), dp(context, 9f), dp(context, 3f))
      }
      elevation = dp(context, 2.5f).toFloat()
      addView(TextView(context).apply {
        text = "LIVES"
        textSize = 9f
        setTypeface(Typeface.MONOSPACE)
        paint.isFakeBoldText = true
        paint.letterSpacing = 0.15f
        setTextColor(acid)
      })
      livesValue = TextView(context).apply {
        text = "-"
        textSize = 12f
        setTypeface(Typeface.MONOSPACE)
        paint.isFakeBoldText = true
        setTextColor(ink)
        setPadding(dp(context, 6f), 0, 0, 0)
      }
      addView(livesValue)
    }
    val livesLp = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.TOP or Gravity.START)
    livesLp.topMargin = dp(context, 6f)
    livesLp.leftMargin = dp(context, 6f)
    box.addView(lives, livesLp)
    // NEIGHBORS counter chip (top-right corner): neighbor sprite + live count
    // from "neighbors_remaining" (0x1D52). Moved here from the header — the
    // counter reads as map/HUD data, not a title.
    val nb = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      background = GradientDrawable().apply {
        setColor(Color.argb(200, 4, 10, 16))
        setStroke(dp(context, 1f), Color.argb(120, 112, 255, 100))
        cornerRadius = dp(context, 10f).toFloat()
        setPadding(dp(context, 7f), dp(context, 3f), dp(context, 9f), dp(context, 3f))
      }
      elevation = dp(context, 2.5f).toFloat()
    }
    nb.addView(
      ImageView(context).apply {
        setImageResource(R.drawable.neighbor)
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 20f))
      }
    )
    // (The old "N" label was removed — sprite + count reads cleanly on its own.)
    neighborsValue = TextView(context).apply {
      text = "-"
      textSize = 12f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      setTextColor(ink)
    }
    nb.addView(neighborsValue)
    val nbLp = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.TOP or Gravity.END)
    nbLp.topMargin = dp(context, 6f)
    nbLp.rightMargin = dp(context, 6f)
    box.addView(nb, nbLp)
    // DEBUG button (small, bottom-left corner): opens the popup DEBUG window
    // (buildDebugOverlay). Moved here from the old top header; compact so it
    // reads as a corner control, not a title.
    val dbg = TextView(context).apply {
      text = "DEBUG"
      textSize = 10f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      paint.letterSpacing = 0.2f
      gravity = Gravity.CENTER
      setTextColor(ink)
    }
    val dbgBg = GradientDrawable().apply {
      setColor(Color.argb(215, 16, 22, 30))
      setStroke(dp(context, 1f), Color.argb(160, 255, 202, 54))
      cornerRadius = dp(context, 7f).toFloat()
      setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
    }
    dbg.background = dbgBg
    dbg.elevation = dp(context, 2.5f).toFloat()
    dbg.setOnClickListener {
      val open = debugOverlay.visibility != View.VISIBLE
      debugOverlay.visibility = if (open) View.VISIBLE else View.GONE
      dbgBg.setColor(if (open) Color.rgb(30, 66, 120) else Color.argb(215, 16, 22, 30))
      Log.i(TAG, "DEBUG window ${if (open) "OPEN" else "CLOSED"}")
    }
    val dbgLp = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM or Gravity.START)
    dbgLp.bottomMargin = dp(context, 6f)
    dbgLp.leftMargin = dp(context, 6f)
    box.addView(dbg, dbgLp)
    // SAVE STATE / LOAD STATE (bottom-right corner): full libretro core
    // snapshots via EmulatorState, persisted to filesDir/state/default.lbs.
    // Styled like the DEBUG corner button; SAVE carries the acid accent,
    // LOAD the water accent. Taps flash SAVED / LOADED / NO STATE / FAILED
    // before reverting, so the result is readable at a glance.
    // Base (rest-after-flash) stroke, matching stateButton's construction.
    val saveStroke = withAlpha(acid, 160)
    val loadStroke = withAlpha(water, 160)
    val saveBtn = stateButton(context, "SAVE STATE", acid)
    saveBtn.setOnClickListener { doSaveState(saveBtn, saveStroke) }
    val loadBtn = stateButton(context, "LOAD STATE", water)
    loadBtn.setOnClickListener { doLoadState(loadBtn, loadStroke) }
    val stateRow = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      addView(saveBtn, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      addView(margin(context, 6f))
      addView(loadBtn, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val stateLp = FrameLayout.LayoutParams(
      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      Gravity.BOTTOM or Gravity.END)
    stateLp.bottomMargin = dp(context, 6f)
    stateLp.rightMargin = dp(context, 6f)
    box.addView(stateRow, stateLp)
    return box
  }

  /** [alpha]/255 over an opaque RGB color (project has no androidx ColorUtils). */
  private fun withAlpha(rgb: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))

  /** Compact corner button for SAVE STATE / LOAD STATE (DEBUG-button family). */
  private fun stateButton(context: Context, text: String, accent: Int): TextView =
    TextView(context).apply {
      this.text = text
      textSize = 9.5f
      setTypeface(Typeface.MONOSPACE)
      paint.isFakeBoldText = true
      paint.letterSpacing = 0.15f
      gravity = Gravity.CENTER
      setTextColor(ink)
      background = GradientDrawable().apply {
        setColor(Color.argb(215, 16, 22, 30))
        setStroke(dp(context, 1f), withAlpha(accent, 160))
        cornerRadius = dp(context, 7f).toFloat()
        setPadding(dp(context, 10f), dp(context, 7f), dp(context, 10f), dp(context, 7f))
      }
      elevation = dp(context, 2.5f).toFloat()
    }

  /** Pending flash-revert per button (a re-tap cancels only its own revert —
   *  never the shared handler's other callbacks, e.g. the 200 ms poller). */
  private val flashReverts = mutableMapOf<TextView, Runnable>()

  /**
   * Momentary result flash on a state button, reverting after [ms] ms.
   * [baseStroke] is the button's constructed stroke (GradientDrawable in
   * android-35 has no getStrokeColor(), so it is passed in, not read back).
   */
  private fun flashStateButton(button: TextView, label: String, ok: Boolean, baseStroke: Int, ms: Long = 900) {
    val bg = button.background as GradientDrawable
    val baseLabel = button.text.toString()
    val baseColor = bg.color
    val baseTextColor = button.currentTextColor
    button.text = label
    bg.setColor(if (ok) Color.argb(235, 26, 52, 34) else Color.argb(235, 56, 22, 20))
    bg.setStroke(dp(button.context, 2f), if (ok) acid else Color.rgb(255, 94, 64))
    button.setTextColor(if (ok) Color.rgb(196, 255, 180) else Color.rgb(255, 192, 172))
    flashReverts[button]?.let { handler.removeCallbacks(it) }  // a re-tap cancels this button's pending flash first
    val revert = Runnable {
      // The map can only hold THIS revert when it fires: every new flash for
      // the same button removes its predecessor before posting, and onStop()
      // clears the whole handler. So a non-null entry is enough.
      if (flashReverts.containsKey(button)) {
        button.text = baseLabel
        bg.setColor(baseColor)
        bg.setStroke(dp(button.context, 1f), baseStroke)
        button.setTextColor(baseTextColor)
        flashReverts.remove(button)
      }
    }
    flashReverts[button] = revert
    handler.postDelayed(revert, ms)
  }

  /** SAVE STATE: snapshot the full core state to the persistent state file. */
  private fun doSaveState(button: TextView, baseStroke: Int) {
    val file = stateFile
    if (file == null) return
    val ok = try {
      val snapshot = state.snapshot()
      if (snapshot != null) {
        file.parentFile?.mkdirs()
        file.writeBytes(snapshot)
        Log.i(TAG, "SAVE STATE ok: ${snapshot.size} bytes -> $file")
        true
      } else {
        Log.w(TAG, "SAVE STATE failed: core returned no snapshot")
        false
      }
    } catch (e: Exception) {
      Log.e(TAG, "SAVE STATE failed", e)
      false
    }
    flashStateButton(button, if (ok) "SAVED ✓" else "FAILED", ok, baseStroke)
  }

  /** LOAD STATE: restore the persistent state file into the running core. */
  private fun doLoadState(button: TextView, baseStroke: Int) {
    val file = stateFile
    if (file == null) return
    if (!file.exists() || file.length() == 0L) {
      Log.i(TAG, "LOAD STATE: no saved state at $file")
      flashStateButton(button, "NO STATE", false, baseStroke)
      return
    }
    val ok = try {
      val bytes = file.readBytes()
      val loaded = state.restore(bytes)
      Log.i(TAG, "LOAD STATE ${if (loaded) "ok" else "REJECTED by core"}: ${bytes.size} bytes from $file")
      loaded
    } catch (e: Exception) {
      Log.e(TAG, "LOAD STATE failed", e)
      false
    }
    flashStateButton(button, if (ok) "LOADED ✓" else "FAILED", ok, baseStroke)
  }

  // -- cell factory ----------------------------------------------------------

  /**
   * Generic square cell: sprite image (or bold text fallback) on a rounded,
   * high-contrast background. Optionally a live count badge. Tappability is
   * fixed by [mode] and enforced each tick by applyCellState().
   */
  private fun makeCell(
    context: Context,
    spriteRes: Int?,
    label: String,
    weaponId: Int?,
    countField: String?,
    withBadge: Boolean,
    cellPx: Int,
    onUse: (() -> Unit)? = null,
    mode: Mode = Mode.DISABLED,
  ): View {
    val bg = GradientDrawable().apply {
      setColor(cellUnowned)
      cornerRadius = dp(context, 8f).toFloat()
      setStroke(dp(context, 1f), Color.rgb(70, 85, 100))
    }

    val labelTv: TextView?
    val badgeTv: TextView?
    val view: View
    if (spriteRes != null) {
      val cell = FrameLayout(context).apply { background = bg }
      val img = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(context, 3f)
        setPadding(pad, pad, pad, pad)
        setImageResource(spriteRes)
        contentDescription = label
      }
      cell.addView(img, FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      var badge: TextView? = null
      if (withBadge) {
        badge = TextView(context).apply {
          text = "-"
          textSize = 11f
          setTypeface(Typeface.MONOSPACE)
          paint.isFakeBoldText = true
          setTextColor(Color.rgb(8, 12, 9))
          background = GradientDrawable().apply {
            setColor(Color.argb(210, 242, 245, 244))
            cornerRadius = dp(context, 6f).toFloat()
            setPadding(dp(context, 4f), 0, dp(context, 4f), 0)
          }
        }
        cell.addView(badge, FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
          gravity = Gravity.BOTTOM or Gravity.END
          bottomMargin = dp(context, 2f)
          rightMargin = dp(context, 2f)
        })
      }
      view = cell
      labelTv = null
      badgeTv = badge
    } else {
      val tv = TextView(context).apply {
        text = label
        textSize = 9f
        setTypeface(Typeface.MONOSPACE)
        paint.isFakeBoldText = true
        setTextColor(ink)
        gravity = Gravity.CENTER
        background = bg
        setPadding(dp(context, 2f), dp(context, 2f), dp(context, 2f), dp(context, 2f))
      }
      view = tv
      labelTv = tv
      badgeTv = null
    }

    val cell = Cell(view, bg, labelTv, badgeTv, weaponId, countField, mode)
    if (onUse != null) {
      val action = onUse
      view.setOnClickListener { action() } // only fires when tick() has enabled it
    }
    view.elevation = dp(context, 1.5f).toFloat()
    cells.add(cell)
    return view
  }

  private fun writeWeapon(id: Int) {
    runCatching {
      memory.writeU8("weapon_selected", id)
      Log.i(TAG, "weapon $id selected: wrote to weapon_selected (0x1CBC)")
    }.onFailure { Log.w(TAG, "weapon select failed", it) }
  }

  /**
   * Per-item action, decided at TAP time by the TAP GAIN ITEMS toggle:
   *   toggle ON  -> every item grants one (medkit grants a medkit too)
   *   toggle OFF -> MEDKIT keeps its USE behavior (consume + heal); every
   *                 other item tap is inert (tick() disables the cells too)
   */
  private fun itemActionFor(item: ItemSpec): (() -> Unit)? = when (item.mode) {
    Mode.ITEM_USE -> {
      val f = item.countField ?: return null
      { if (tapGainItems) grantItem(f, item.increment) else useMedkit() }
    }
    Mode.ITEM_GRANT -> {
      val f = item.countField ?: return null
      { if (tapGainItems) grantItem(f, item.increment)
        else Log.i(TAG, "item tap inert: TAP GAIN ITEMS is OFF (${item.label})") }
    }
    else -> null
  }

  /**
   * ITEM GRANT (verified slot only): write one unit to the item's verified
   * count slot so a tap grants one. Used by pandora / clown / skull key / shoes
   * / potions — all of these offsets are causally-verified writable (Mesen
   * bridge, human-confirmed). increment=true (KEYS) adds one to the current
   * count (n -> n+1, capped at 255) instead of overwriting, since keys is a
   * true COUNT slot (verified: wrote 9 -> 9 keys, 5 -> 5 keys).
   */
  private fun grantItem(field: String, increment: Boolean = false) {
    runCatching {
      val before = memory.readU8(field)
      val value = if (increment) {
        (if (before < 0) 0 else before + 1).coerceAtMost(255)
      } else {
        GRANT_VALUE
      }
      val ok = memory.writeU8(field, value)
      Log.i(TAG, "grant: $field <- $value (was $before, write ok=$ok)")
      cells.firstOrNull { it.countField == field }?.badge?.text = value.toString()
    }.onFailure { Log.w(TAG, "item grant failed for $field", it) }
  }

  /**
   * USE MEDKIT (item button tap) — built only from causally-verified write
   * slots, no guesses:
   *   1. consume one:  medkit_count (0x1D1A) <- count - 1   [verified write]
   *   2. heal to full: health (0x1CB8)     <- 10            [verified write]
   * Refuses if the count reads 0, and refuses if health is already full
   * (>= 10) so a med-kit is never wasted.
   */
  private fun useMedkit() {
    runCatching {
      val n = memory.readU8("medkit_count")
      if (n <= 0) { Log.i(TAG, "medkit use refused: count is 0"); return@runCatching }
      val hp = memory.readU8("health")
      if (hp >= 10) {
        Log.i(TAG, "medkit use refused: health already full ($hp/10)")
        return@runCatching
      }
      val consumed = memory.writeU8("medkit_count", n - 1)
      val healed = memory.writeU8("health", 10)
      Log.i(TAG, "medkit used: $n -> ${n - 1} (ok=$consumed), health -> 10 (ok=$healed)")
      cells.firstOrNull { it.countField == "medkit_count" }?.badge?.text = (n - 1).toString()
    }.onFailure { Log.w(TAG, "medkit use failed", it) }
  }

  // -- utilities -------------------------------------------------------------

  private fun margin(context: Context, dp: Float): View =
    View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(context, dp), dp(context, dp)) }

  private fun spacer(context: Context, dp: Float): View =
    View(context).apply {
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, dp))
    }

  private fun dp(context: Context, v: Float): Int =
    (v * context.resources.displayMetrics.density).toInt()

  companion object {
    private const val TAG = "ZamnSecondScreen"
  }
}

/** Safe read of a u8 profile field; -1 if unavailable this tick. */
private fun GameMemory.readField(id: String): Int =
  runCatching { readU8(id) }.getOrDefault(-1)

