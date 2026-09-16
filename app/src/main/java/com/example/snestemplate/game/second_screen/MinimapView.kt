package com.example.snestemplate.game.second_screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View

/**
 * Minimap panel: top-down level image + live player dot.
 *
 * CALIBRATION MODEL (universal, observed on levels 1-2):
 *   The map PNG is a 1:1 top-down render of world space — same origin, no
 *   rotation, no per-level scale. Both calibrated levels fit "image = world
 *   + constant offset" with sub-10px error, and level 1's anchors were
 *   captured with ±10px noise, so both levels are consistent with ONE
 *   universal transform (1:1 + a small constant offset, ≤8px).
 *
 *   Automation: every level defaults to the identity transform (offset 0).
 *   Draft accuracy: the dot lands within ~10px of the player (less than half
 *   a character-width). LEVEL_OFFSETS is the per-level CORRECTION table —
 *   add an entry there (from 2-4 anchors) to make a level pixel-exact.
 *
 *   - Level 1: calibrated, 4 anchors (±10px capture) -> identity fits (±10px)
 *   - Level 2: calibrated, 4 anchors (±3px)      -> offset (-1, -8), ≤3px
 *   - Levels 3-48: identity draft (pattern from 1-2)
 *
 *   The calibration anchors are internal reference data only — they are NOT
 *   drawn on screen (anchor crosshairs were removed to keep the map clean).
 *
 * SMOOTH FOLLOW: setPlayer updates the TARGET at the 200 ms poll rate; the
 * camera + dot render from an eased position advanced ~every 16 ms by a
 * handler-driven loop while in motion, so the dot glides instead of
 * teleporting. Idle cost is zero. (A Choreographer-based loop was tried
 * first but its vsync callbacks never fired on this dual-display host, so
 * the map froze — the handler loop uses the same message-loop mechanism as
 * the working 200 ms poller, and setPlayer ALWAYS invalidates as a backstop.)
 *
 * MEMBERS: setPlayer(worldX, worldY), setContext(level)
 */
class MinimapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

  companion object {
    /** Levels that ship a map image (assets/map/level_N.png). */
    val MAP_LEVELS: List<Int> = (1..48).toList()

    /** Full-resolution bitmaps kept resident: the [MAX_CACHED] most recent levels. */
    const val MAX_CACHED = 3

    /**
     * Per-level CORRECTION table (world->image offset), the only per-level
     * calibration data. Absent entry = identity (universal 1:1 default).
     * Level 2 was the one level where the offset exceeded anchor noise.
     */
    private val LEVEL_OFFSETS: Map<Int, FloatArray> = mapOf(
      2 to floatArrayOf(-1f, -8f)
    )

    /**
     * Verified anchor world positions (per level) — calibration REFERENCE
     * DATA ONLY. Not rendered; kept as the source for the LEVEL_OFFSETS
     * correction entries above.
     */
    private val LEVEL_ANCHORS: Map<Int, Array<FloatArray>> = mapOf(
      1 to arrayOf(
        floatArrayOf(376f, 576f),
        floatArrayOf(1014f, 58f),
        floatArrayOf(1336f, 128f),
        floatArrayOf(52f, 360f)
      ),
      2 to arrayOf(
        floatArrayOf(33f, 769f),
        floatArrayOf(1138f, 911f),
        floatArrayOf(1144f, 576f),
        floatArrayOf(473f, 64f)
      )
    )

    const val ZOOM = 2.0f
  }

  // LRU: access order. On overflow the eldest entry is merely dereferenced
  // and left for the GC — we must NOT call recycle() here: Android's
  // hardware RenderThread can still be sampling that bitmap from an in-flight
  // display list, and drawing a recycled bitmap is a hard crash
  // ("Canvas: trying to use a recycled bitmap" — 09-13 00:37-00:39). Eviction
  // is also triggered by plain reads in access-order mode, so the entry may
  // legitimately stay cached.
  private val cache = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<Int, Bitmap>): Boolean = size > MAX_CACHED
  }
  private val assetCtx = context.applicationContext

  @Volatile
  var hasMarker = false
    private set
  @Volatile
  var lastWorldX = 0
    private set
  @Volatile
  var lastWorldY = 0
    private set
  @Volatile
  var levelNo = 0
    private set

  private val paint = Paint() // nearest-neighbor: cheaper blit, crisper pixel art
  private val acid = Color.rgb(112, 255, 100)
  private val ink = Color.rgb(242, 245, 244)
  private val dim = Color.rgb(150, 165, 180)
  private val panelFill = Color.rgb(16, 20, 18)
  private val markerFill = Color.rgb(255, 92, 76)
  private val markerRing = Color.rgb(242, 245, 244)
  private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = android.graphics.Typeface.MONOSPACE
    isFakeBoldText = true
    textAlign = Paint.Align.CENTER
  }
  private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

  /** Raw P1 world position (worldX = 0x0130|0x0131<<8, worldY = 0x0132|0x0133<<8). */
  fun setPlayer(worldX: Int, worldY: Int) {
    val changed = worldX != lastWorldX || worldY != lastWorldY
    lastWorldX = worldX
    lastWorldY = worldY
    if (!changed) return
    // BACKSTOP: always draw — the map can never freeze even if the easing
    // loop below misbehaves on this device.
    postInvalidate()
    if (!hasMarker) {
      // First sighting: snap directly (no easing from the origin).
      hasMarker = true
      dispX = worldX.toFloat()
      dispY = worldY.toFloat()
      return
    }
    if (!smoothRunning) {
      smoothRunning = true
      lastStepMs = 0L
      handler.post(smoothStep)
    }
    // If already running, the in-flight loop picks up the new target.
  }

  // -- smooth follow ---------------------------------------------------------
  // The position TARGET updates at the 200 ms poll rate, but the camera + dot
  // are rendered from an eased display position advanced ~every 16 ms — only
  // while the player is moving (idle cost is zero). This turns the old 5 Hz
  // stepped teleports into smooth, continuous follow.
  //
  // IMPLEMENTATION NOTE: this is a Handler loop, NOT Choreographer — on this
  // dual-display host the Choreographer's vsync callbacks never fired (map
  // froze permanently), while the main-looper message loop is proven to work
  // (the 200 ms poller). If the main thread is ever busy, steps coalesce to
  // a lower rate instead of freezing.
  private val handler = Handler(Looper.getMainLooper())
  private var dispX = 0f
  private var dispY = 0f
  private var smoothRunning = false
  private var lastStepMs = 0L
  private var smoothLogged = false
  private val smoothTauMs = 45.0 // ~2-3 ticks to converge; frame-rate independent

  private val smoothStep = object : Runnable {
    override fun run() {
      if (!smoothRunning) return
      val now = SystemClock.uptimeMillis()
      val dt = if (lastStepMs == 0L) 16.0
               else (now - lastStepMs).toDouble().coerceIn(1.0, 100.0)
      lastStepMs = now
      if (!smoothLogged) {
        smoothLogged = true
        Log.i("MinimapView", "smooth follow: first step (dt=$dt ms)")
      }
      val k = (1.0 - Math.exp(-dt / smoothTauMs)).toFloat()
      dispX += (lastWorldX - dispX) * k
      dispY += (lastWorldY - dispY) * k
      val done = Math.abs(lastWorldX - dispX) < 0.5f &&
                 Math.abs(lastWorldY - dispY) < 0.5f
      if (done) {
        dispX = lastWorldX.toFloat()
        dispY = lastWorldY.toFloat()
        smoothRunning = false
      }
      postInvalidate()
      if (smoothRunning) handler.postDelayed(this, 16)
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    smoothRunning = false
    handler.removeCallbacks(smoothStep)
  }

  /** Switch the active level's map. Loads on demand (LRU, ≤3 resident). */
  fun setContext(level: Int) {
    if (level == levelNo) return
    levelNo = level
    // Snap the eased position to the target so a level change doesn't fly in
    // from the previous level's coordinates.
    dispX = lastWorldX.toFloat()
    dispY = lastWorldY.toFloat()
    loadMap(level)
    postInvalidate()
  }

  private fun mapFor(level: Int): Bitmap? = cache[level]

  /**
   * Decode assets/map/level_N.png at full resolution into the LRU cache.
   * One synchronous decode per first visit (~200-400 ms on the UI thread);
   * revisits within the last 3 levels are instant.
   */
  private fun loadMap(level: Int) {
    if (level !in MAP_LEVELS || cache.containsKey(level)) return
    try {
      assetCtx.assets.open("map/level_$level.png").use { stream ->
        BitmapFactory.decodeStream(stream)?.let { cache[level] = it }
      }
    } catch (e: Exception) {
      // Missing asset or decode failure: level renders as "NO MAP".
    }
  }

  private fun drawPlaceholder(canvas: Canvas, w: Int, h: Int) {
    canvas.drawColor(panelFill)
    textPaint.color = dim
    textPaint.textSize = dp(12f).toFloat()
    canvas.drawText("NO MAP", w / 2f, h / 2f - dp(4f), textPaint)
    textPaint.textSize = dp(8f).toFloat()
    canvas.drawText("LEVEL ${levelNo}", w / 2f, h / 2f + dp(12f), textPaint)
    drawBorder(canvas, w, h)
  }

  protected override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width
    if (w == 0 || height == 0) return

    val map = mapFor(levelNo)
    if (map == null) {
      drawPlaceholder(canvas, w, height)
      return
    }

    // Camera zoom centered on the player (or image center), then apply the
    // level's world->image correction (identity by default).
    val W = map.width.toFloat()
    val H = map.height.toFloat()
    // Zoom: keep the intended 2x, but never let the scaled map end up smaller
    // than the view cell in either axis. Level maps have differing aspect
    // ratios than the (landscape) cell, and when they differ by >2x the old
    // math produced an empty camera range (coerceIn with min>max) and crashed
    // (IllegalArgumentException "empty range" — 09-13 00:49). Requiring the
    // scaled map to cover the cell in both axes keeps every clamp range valid.
    val z = maxOf(minOf(w / W, height / H) * ZOOM, w / W, height / H)
    val halfW = w / (2f * z)
    val halfH = height / (2f * z)

    val off = LEVEL_OFFSETS[levelNo] ?: floatArrayOf(0f, 0f)
    val tx = (dispX + off[0]).coerceIn(0f, W)
    val ty = (dispY + off[1]).coerceIn(0f, H)
    val cx = tx.coerceIn(halfW, W - halfW)
    val cy = ty.coerceIn(halfH, H - halfH)

    val ox = w / 2f - cx * z
    val oy = height / 2f - cy * z

    val x0 = (-ox / z).coerceIn(0f, W)
    val y0 = (-oy / z).coerceIn(0f, H)
    val vw = (w / z).coerceAtMost(W - x0)
    val vh = (height / z).coerceAtMost(H - y0)

    canvas.drawBitmap(
      map,
      Rect(x0.toInt(), y0.toInt(), (x0 + vw).toInt(), (y0 + vh).toInt()),
      Rect(0, 0, w, height),
      paint
    )

    // Player dot (all map levels: calibrated or identity-draft).
    if (hasMarker) {
      val dx = ox + tx * z
      val dy = oy + ty * z
      if (dx in -20f..(w + 20f) && dy in -20f..(height + 20f)) {
        markerPaint.style = Paint.Style.FILL
        markerPaint.color = markerFill
        canvas.drawCircle(dx, dy, dp(4f).toFloat(), markerPaint)
        markerPaint.style = Paint.Style.STROKE
        markerPaint.color = markerRing
        markerPaint.strokeWidth = dp(1.5f).toFloat()
        canvas.drawCircle(dx, dy, dp(7f).toFloat(), markerPaint)
      }
    }

    drawBorder(canvas, w, height)
  }

  /**
   * Minimap frame — a "pressed into the brick wall" recess, not a raised panel:
   *   1) crisp dark outer frame (replaces the old acid-green ring), then
   *   2) a soft inset bevel with light from above — dark shadow on the TOP +
   *      LEFT inner edges, faint light on the BOTTOM + RIGHT inner edges. That
   *      top-shadow / bottom-light gradient is what reads as "smashed in".
   * Kept subtle on purpose.
   */
  private fun drawBorder(canvas: Canvas, w: Int, h: Int) {
    val t = dp(2f).toFloat()
    // 1) dark outer frame.
    borderPaint.style = Paint.Style.STROKE
    borderPaint.color = Color.rgb(4, 6, 6)
    borderPaint.strokeWidth = t
    canvas.drawRect(t / 2f, t / 2f, (w - t / 2f), (h - t / 2f), borderPaint)

    // 2) recessed bevel (light from above).
    val depth = dp(9f)
    bevelPaint.style = Paint.Style.FILL
    // top shadow
    bevelPaint.shader = LinearGradient(0f, t, 0f, (t + depth).toFloat(),
      Color.argb(200, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
    canvas.drawRect(t, t, (w - t), (t + depth).toFloat(), bevelPaint)
    // left shadow
    bevelPaint.shader = LinearGradient(t, 0f, (t + depth).toFloat(), 0f,
      Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
    canvas.drawRect(t, t, (t + depth).toFloat(), (h - t), bevelPaint)
    // bottom light (light catching the lower lip of the recess)
    bevelPaint.shader = LinearGradient(0f, (h - t), 0f, (h - t - depth).toFloat(),
      Color.argb(70, 150, 200, 255), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
    canvas.drawRect(t, (h - t - depth).toFloat(), (w - t), (h - t), bevelPaint)
    // right light
    bevelPaint.shader = LinearGradient((w - t), 0f, (w - t - depth).toFloat(), 0f,
      Color.argb(55, 150, 200, 255), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
    canvas.drawRect((w - t - depth).toFloat(), t, (w - t), (h - t), bevelPaint)
    bevelPaint.shader = null
  }
}
