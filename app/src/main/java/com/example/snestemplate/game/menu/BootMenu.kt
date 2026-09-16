package com.example.snestemplate.game.menu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.snestemplate.game.rom.RomGate

/**
 * Pre-game menu:
 *
 *  PLAY        — starts the emulator. Enabled only while the ROM gate
 *                ([RomGate]) resolves a ROM whose SHA-256 matches the
 *                approved build. The app bundles NO ROM (external load only),
 *                so there is no fallback: no verified selection, no PLAY.
 *
 *  SELECT ROM  — one-time setup: pick (SAF) or type the path of a ROM file.
 *                The choice is hash-verified against the approved build
 *                immediately and persisted (filesDir/rom/selection.json); on
 *                every later launch it is auto-re-verified and used. If it
 *                ever stops verifying, PLAY is disabled until a correct ROM
 *                is selected again.
 *
 * The whole menu is plain View widgets in the second-screen palette (no
 * resources, no dependencies) — same discipline as ZamnSecondScreen.
 */
class BootMenu(
  private val context: Context,
  private val onPlay: (RomGate.PlayPlan) -> Unit,
  private val onBrowseRequest: () -> Unit,
) : LinearLayout(context) {

  private val gate = RomGate(context)
  private var plan: RomGate.PlayPlan? = null

  private val statusState: TextView
  private val statusSource: TextView
  private val statusHash: TextView
  private val playButton: TextView
  private var selectButton: TextView

  /** Controller navigation state: 0 = PLAY, 1 = SELECT ROM. */
  private var selection = 0
  private var dialog: AlertDialog? = null

  private val acid = Color.rgb(112, 255, 100)
  private val water = Color.rgb(86, 205, 255)
  private val ink = Color.rgb(242, 245, 244)
  private val dim = Color.rgb(150, 165, 180)
  private val danger = Color.rgb(255, 122, 92)
  private val bg = Color.rgb(4, 8, 6)

  init {
    orientation = VERTICAL
    gravity = Gravity.CENTER
    setBackgroundColor(bg)

    val panel = LinearLayout(context).apply {
      orientation = VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(48f), dp(40f), dp(48f), dp(36f))
    }
    val panelBg = GradientDrawable().apply {
      setColor(Color.argb(240, 9, 14, 12))
      cornerRadius = dp(12f).toFloat()
      setStroke(dp(1f), Color.argb(60, Color.red(acid), Color.green(acid), Color.blue(acid)))
    }
    panel.background = panelBg

    // Title
    panel.addView(TextView(context).apply {
      text = "ZOMBIES ATE MY NEIGHBORS"
      typeface = Typeface.MONOSPACE
      textSize = 20f
      setTextColor(acid)
      letterSpacing = 0.32f
      gravity = Gravity.CENTER
      includeFontPadding = false
    })
    panel.addView(TextView(context).apply {
      text = "DUAL-SCREEN EDITION · ROM GATE"
      typeface = Typeface.MONOSPACE
      textSize = 9.5f
      setTextColor(dim)
      letterSpacing = 0.45f
      gravity = Gravity.CENTER
      setPadding(0, dp(6f), 0, dp(22f))
    })

    // Status block: state / source / sha
    val statusBox = LinearLayout(context).apply {
      orientation = VERTICAL
      gravity = Gravity.CENTER_HORIZONTAL
      setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    val statusBoxBg = GradientDrawable().apply {
      setColor(Color.argb(200, 6, 10, 9))
      cornerRadius = dp(8f).toFloat()
      setStroke(dp(1f), Color.argb(70, 150, 165, 180))
    }
    statusBox.background = statusBoxBg

    statusState = TextView(context).apply {
      typeface = Typeface.MONOSPACE
      textSize = 12f
      setTextColor(dim)
      letterSpacing = 0.2f
      gravity = Gravity.CENTER
    }
    statusSource = TextView(context).apply {
      typeface = Typeface.MONOSPACE
      textSize = 9.5f
      setTextColor(dim)
      gravity = Gravity.CENTER
      setPadding(0, dp(5f), 0, 0)
      maxLines = 2
      ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    statusHash = TextView(context).apply {
      typeface = Typeface.MONOSPACE
      textSize = 8.5f
      setTextColor(Color.rgb(110, 125, 140))
      gravity = Gravity.CENTER
      setPadding(0, dp(3f), 0, 0)
    }
    statusBox.addView(statusState)
    statusBox.addView(statusSource)
    statusBox.addView(statusHash)
    panel.addView(statusBox)

    // PLAY (primary — filled)
    playButton = menuButton("PLAY", acid, 54f, 16f, filled = true)
    playButton.setOnClickListener { plan?.let { onPlay(it) } }
    panel.addView(playButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22f) })

    // SELECT ROM (secondary — outlined)
    selectButton = menuButton("SELECT ROM", water, 42f, 11f, filled = false)
    selectButton.setOnClickListener { (context as? Activity)?.let { showSelectDialog(it) } }
    panel.addView(selectButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

    panel.addView(TextView(context).apply {
      text = "You need Zombies Ate My Neighbors (USA) sfc rom."
      typeface = Typeface.MONOSPACE
      textSize = 8.5f
      setTextColor(Color.rgb(105, 120, 135))
      gravity = Gravity.CENTER
      setPadding(dp(14f), dp(18f), dp(14f), 0)
    })
    panel.addView(TextView(context).apply {
      text = "GAMEPAD   UP/DOWN SELECT  ·  A CONFIRM  ·  B BACK"
      typeface = Typeface.MONOSPACE
      textSize = 7.5f
      setTextColor(Color.rgb(85, 100, 115))
      letterSpacing = 0.3f
      gravity = Gravity.CENTER
      setPadding(0, dp(10f), 0, 0)
    })

    addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

    refresh()

    // Default controller focus: point at the button the user most likely
    // needs next (no ROM yet → SELECT ROM; ready → PLAY).
    selection = if (plan == null) 1 else 0
    applySelection()
  }

  /**
   * Controller / gamepad support for the menu. During gameplay the core polls
   * its own input; before start() nothing does — so the menu handles its own
   * keys. Called from MainActivity.dispatchKeyEvent while the menu is up.
   *
   *   UP / DOWN            — move the selection between the two buttons
   *   A / CENTER / ENTER   — activate the selected button
   *   B / Y / BACK         — close the selection dialog if one is open
   *
   * Returns true when the event was consumed.
   */
  fun handleKey(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return false
    when (event.keyCode) {
      KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
        if (event.repeatCount > 0) return true
        selection = 1 - selection
        applySelection()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        return true
      }
      KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
        if (event.repeatCount > 0) return true
        return activateSelected()
      }
      KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y -> {
        val d = dialog
        if (d != null) { d.dismiss(); return true }
        return false // no dialog: let the system handle BACK (exit)
      }
    }
    return false
  }

  /** Highlight whichever button the controller has selected. */
  private fun applySelection() {
    playButton.background = buttonBg(acid, filled = true, selected = selection == 0)
    playButton.alpha = if (playButton.isEnabled) 1f else 0.45f
    selectButton.background = buttonBg(water, filled = false, selected = selection == 1)
  }

  /**
   * Activate the currently selected button from the controller. PLAY only
   * fires when the ROM gate has a verified plan; otherwise it steers the
   * user to SELECT ROM with a hint.
   */
  private fun activateSelected(): Boolean {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    if (selection == 0) {
      if (plan != null) {
        playButton.performClick()
      } else {
        selection = 1
        applySelection()
        statusState.text = "SELECT A ROM FIRST"
        statusState.setTextColor(danger)
      }
    } else {
      selectButton.performClick()
    }
    return true
  }

  private fun buttonBg(accent: Int, filled: Boolean, selected: Boolean): GradientDrawable = GradientDrawable().apply {
    cornerRadius = dp(10f).toFloat()
    if (filled) {
      setColor(accent)
      setStroke(if (selected) dp(3f) else dp(1f), if (selected) ink else accent)
    } else {
      setColor(if (selected) Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.argb(220, 8, 14, 18))
      setStroke(if (selected) dp(3f) else dp(1f), if (selected) accent else Color.argb(160, Color.red(accent), Color.green(accent), Color.blue(accent)))
    }
  }

  /** Re-evaluate what PLAY would run; call at launch and after selection changes. */
  fun refresh() {
    val r = gate.resolve()
    plan = r.plan
    when {
      r.plan == null -> {
        playButton.isEnabled = false
        playButton.alpha = 0.45f
        statusState.text = "NO ROM LOADED"
        statusState.setTextColor(danger)
        statusSource.text = r.message
        statusSource.setTextColor(danger)
        statusHash.text = "approved SHA-256 ${RomGate.REFERENCE_SHA256.take(40)}…"
      }
      else -> {
        playButton.isEnabled = true
        playButton.alpha = 1f
        statusState.text = "READY"
        statusState.setTextColor(acid)
        statusSource.text = "ROM: ${r.plan!!.selection.describe()}"
        statusSource.setTextColor(ink)
        statusHash.text = "SHA-256 ${r.plan!!.sha256.take(40)}… (verified)"
      }
    }
  }

  /** Surface a non-fatal menu error (rejections, picker failures) in the status block. */
  fun reportError(message: String) {
    val r = gate.resolve()
    plan = r.plan
    playButton.isEnabled = r.plan != null
    playButton.alpha = if (r.plan != null) 1f else 0.45f
    statusState.text = if (r.plan != null) "READY" else "ROM REQUIRED"
    statusState.setTextColor(if (r.plan != null) acid else danger)
    statusSource.text = message
    statusSource.setTextColor(danger)
    statusHash.text = "approved SHA-256 ${RomGate.REFERENCE_SHA256.take(40)}…"
  }

  /** SAF picker result — the Activity has already taken the persistable permission. */
  fun onBrowseResult(uri: Uri) {
    attemptSelection(RomGate.Selection(RomGate.Kind.URI, uri.toString()))
  }

  fun onPathChosen(rawPath: String) {
    val path = rawPath.trim()
    if (path.isEmpty()) {
      reportError("type a file path or pick one from the device")
      return
    }
    attemptSelection(RomGate.Selection(RomGate.Kind.PATH, path))
  }

  private fun attemptSelection(sel: RomGate.Selection) {
    val v = gate.verify(sel)
    when {
      v.error != null -> reportError("REJECTED — ${v.error}")
      !v.match -> reportError(
        "REJECTED — SHA-256 ${v.sha256?.take(20) ?: "??"}… does not match the approved build ${RomGate.REFERENCE_SHA256.take(20)}… (only the exact ROM is allowed)"
      )
      else -> {
        v.sha256?.let { hash -> gate.storeSelection(sel, hash) }
        refresh()
        statusState.text = "ROM ACCEPTED & SAVED"
        statusState.setTextColor(acid)
      }
    }
  }

  private fun showSelectDialog(activity: Activity) {
    val box = LinearLayout(activity).apply {
      orientation = VERTICAL
      setPadding(dp(6f), dp(4f), dp(6f), 0)
    }
    box.addView(TextView(activity).apply {
      text = "Pick the exact ROM file on this device. Its SHA-256 must match the approved build — one pick, remembered forever."
      textSize = 11f
      setTextColor(dim)
      setPadding(0, 0, 0, dp(12f))
    })
    val pathInput = EditText(activity).apply {
      hint = "…or type an absolute path, e.g. /sdcard/Download/zamn.sfc"
      textSize = 12f
      setTextColor(ink)
      setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
      val edBg = GradientDrawable().apply {
        setColor(Color.argb(200, 6, 10, 9))
        cornerRadius = dp(8f).toFloat()
        setStroke(dp(1f), Color.argb(90, 150, 165, 180))
      }
      background = edBg
    }
    box.addView(pathInput)
    val browse = TextView(activity).apply {
      text = "BROWSE DEVICE FILES…"
      typeface = Typeface.MONOSPACE
      textSize = 10.5f
      setTextColor(water)
      letterSpacing = 0.25f
      gravity = Gravity.CENTER
      setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
      val bBg = GradientDrawable().apply {
        setColor(Color.argb(220, 8, 14, 18))
        cornerRadius = dp(8f).toFloat()
        setStroke(dp(1f), Color.argb(160, Color.red(water), Color.green(water), Color.blue(water)))
      }
      background = bBg
      setOnClickListener { onBrowseRequest() }
    }
    box.addView(browse, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })

    dialog = AlertDialog.Builder(activity)
      .setTitle("SELECT ROM")
      .setView(box)
      .setPositiveButton("VERIFY PATH") { _, _ -> pathInput.text.toString().let { onPathChosen(it) } }
      .setNegativeButton("CANCEL", null)
      .show()
      .also { it.setOnDismissListener { dialog = null } }
  }

  private fun menuButton(label: String, accent: Int, heightDp: Float, textSize: Float, filled: Boolean): TextView {
    val t = TextView(context)
    t.text = label
    t.typeface = Typeface.MONOSPACE
    t.textSize = textSize
    t.letterSpacing = 0.35f
    t.gravity = Gravity.CENTER
    t.height = dp(heightDp)
    val bgD = GradientDrawable().apply {
      if (filled) {
        setColor(accent)
        setStroke(dp(1f), accent)
      } else {
        setColor(Color.argb(220, 8, 14, 18))
        setStroke(dp(1f), Color.argb(160, Color.red(accent), Color.green(accent), Color.blue(accent)))
      }
      cornerRadius = dp(10f).toFloat()
    }
    t.background = bgD
    t.setTextColor(if (filled) Color.rgb(6, 19, 10) else accent)
    return t
  }

  private fun dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()
}
