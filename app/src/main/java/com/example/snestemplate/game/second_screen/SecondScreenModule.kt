package com.example.snestemplate.game.second_screen

import android.content.Context
import android.view.View

interface SecondScreenModule {
  fun createView(context: Context): View
  fun onStop() {}
}
