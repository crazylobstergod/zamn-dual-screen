package com.example.snestemplate.game.second_screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 10-segment pill bar matching ZAMN's 10-unit health bar.
 * Units 1-3 render as a danger color; otherwise water blue.
 */
class ZamnHealthBar(context: Context) : View(context) {
  companion object { const val MAX_UNITS = 10 }

  var units: Int = 0
    set(value) { field = value.coerceIn(0, MAX_UNITS); invalidate() }

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
    color = Color.argb(90, 86, 205, 255)
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return
    val gap = w * 0.016f
    val cw = (w - gap * (MAX_UNITS - 1)) / MAX_UNITS
    val danger = units in 1..3
    val radius = h / 2f
    val rect = RectF()
    for (i in 0 until MAX_UNITS) {
      val left = i * (cw + gap)
      rect.set(left, 0f, left + cw, h)
      if (i < units) {
        fillPaint.color = if (danger) Color.rgb(255, 94, 64) else Color.rgb(86, 205, 255)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        highlightPaint.color = if (danger) Color.argb(80, 255, 210, 190) else Color.argb(80, 210, 245, 255)
        canvas.drawRoundRect(RectF(left, 0f, left + cw, h * 0.45f), radius, radius, highlightPaint)
      } else {
        canvas.drawRoundRect(rect, radius, radius, emptyPaint)
      }
    }
  }
}

/**
 * Horizontal ammo tank. Fill fraction is `value / max`.
 * `max` is per-weapon capacity (e.g. squirt gun 0x150, soda 0x20); the default
 * is the in-game HUD's 3-digit (0-999) display range.
 */
class ZamnWaterGauge(context: Context) : View(context) {
  companion object { const val HUD_MAX = 999 }

  var value: Int = 0
    set(v) { field = v; invalidate() }

  var max: Int = HUD_MAX
    set(v) { field = v.coerceAtLeast(1); invalidate() }

  private val tankPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(9, 24, 36) }
  private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = 2f
    color = Color.argb(130, 86, 205, 255)
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return
    val radius = h / 2f
    val rect = RectF(0f, 0f, w, h)
    canvas.drawRoundRect(rect, radius, radius, tankPaint)

    val fraction = (value.coerceIn(0, max) / max.toFloat()).coerceIn(0f, 1f)
    if (fraction > 0f) {
      val save = canvas.save()
      canvas.clipRect(0f, 0f, w * fraction, h)
      waterPaint.shader = LinearGradient(
        0f, 0f, w, 0f,
        intArrayOf(Color.rgb(22, 82, 128), Color.rgb(52, 160, 232), Color.rgb(126, 224, 255)),
        null,
        Shader.TileMode.CLAMP
      )
      canvas.drawRoundRect(rect, radius, radius, waterPaint)
      canvas.drawRoundRect(RectF(0f, 0f, w, h * 0.4f), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(50, 220, 245, 255) })
      canvas.restoreToCount(save)
    }
    canvas.drawRoundRect(rect, radius, radius, rimPaint)
  }
}

/** Teardrop icon for the squirt gun theming. */
class ZamnDroplet(color: Int) : Drawable() {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
  }
  private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = Color.argb(110, 235, 250, 255)
  }

  override fun draw(canvas: Canvas) {
    val b = bounds
    if (b.width() == 0 || b.height() == 0) return
    val w = b.width().toFloat()
    val h = b.height().toFloat()
    val cx = b.exactCenterX()
    val r = w / 2f
    val path = Path()
    path.addCircle(cx, b.bottom - r, r, Path.Direction.CW)
    path.moveTo(cx, b.top.toFloat())
    path.lineTo(b.left + w * 0.22f, b.top + h * 0.52f)
    path.lineTo(b.right - w * 0.22f, b.top + h * 0.52f)
    path.close()
    canvas.drawPath(path, paint)
    canvas.drawOval(RectF(b.left + w * 0.30f, b.top + h * 0.58f, b.left + w * 0.52f, b.top + h * 0.80f), shine)
  }

  override fun setAlpha(alpha: Int) { paint.alpha = alpha }
  override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
  override fun getOpacity() = PixelFormat.TRANSLUCENT
}
