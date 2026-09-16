package com.example.snestemplate.game.menu

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import com.example.snestemplate.R
import com.example.snestemplate.game.second_screen.SecondScreenModule

/**
 * Placeholder for the secondary (dash) display while the boot menu is up on
 * the main screen — the real ZamnSecondScreen only makes sense once the core
 * is running with a live memory profile.
 *
 * Just the Level 22 map (a bundled asset), pre-blurred with PIL into
 * res/drawable-nodpi/level22_blur.png — no text, no runtime blur cost.
 */
class StandbyScreen : SecondScreenModule {

  override fun createView(context: Context): View = ImageView(context).apply {
    setBackgroundColor(Color.rgb(13, 16, 15))
    setImageResource(R.drawable.level22_blur)
    scaleType = ImageView.ScaleType.CENTER_CROP
  }
}
