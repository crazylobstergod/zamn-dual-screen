package com.example.snestemplate.runtime.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import com.example.snestemplate.game.second_screen.SecondScreenModule

class SecondaryDisplayPresentation(context: Context, display: Display, private val module: SecondScreenModule, private val forwardKey: (KeyEvent) -> Boolean) : Presentation(context, display) {
  override fun onCreate(state: Bundle?) { super.onCreate(state); window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE); Log.i("SnesDisplay", "Secondary Presentation is touchable but non-focusable; gamepad focus remains with gameplay Activity"); setContentView(module.createView(context)) }
  override fun dispatchKeyEvent(event: KeyEvent): Boolean { Log.i("SnesInput", "Forwarding key from secondary display: code=${event.keyCode} action=${event.action}"); return forwardKey(event) }
  override fun onStop() { module.onStop(); super.onStop() }
}
