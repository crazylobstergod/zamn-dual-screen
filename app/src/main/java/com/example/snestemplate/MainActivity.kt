package com.example.snestemplate

import android.app.*
import android.os.*
import android.graphics.*
import android.graphics.drawable.*
import android.media.*
import android.view.*
import android.util.Log
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import java.util.concurrent.locks.LockSupport
import java.io.File
import com.example.snestemplate.game.menu.BootMenu
import com.example.snestemplate.game.menu.StandbyScreen
import com.example.snestemplate.game.profile.GameProfileLoader
import com.example.snestemplate.game.rom.RomGate
import com.example.snestemplate.game.second_screen.ZamnSecondScreen
import com.example.snestemplate.runtime.display.SecondaryDisplayPresentation
import com.example.snestemplate.runtime.memory.GameMemory
import com.example.snestemplate.runtime.memory.NativeRam
import com.example.snestemplate.runtime.state.EmulatorState
import com.example.snestemplate.runtime.state.NativeCoreState

class MainActivity : Activity() {
  private lateinit var view: GLSurfaceView
  private lateinit var displayManager: DisplayManager
  private var secondaryPresentation: SecondaryDisplayPresentation? = null
  private lateinit var gameMemory: GameMemory
  private lateinit var emulatorState: EmulatorState
  @Volatile private var running = false
  private var started = false
  private var coreStarted = false
  private lateinit var bootMenu: BootMenu
  private lateinit var romGate: RomGate
  private external fun nativeStart(romPath: String): Boolean
  private external fun nativeRunFrame()
  private external fun nativeGetFrameBuffer(index: Int): ByteBuffer
  private external fun nativeGetLatestFrame(): Long
  private external fun nativeGetCoreFps(): Float
  private external fun nativeGetAverageRunMs(): Float
  private external fun nativeGetSystemRamSize(): Int
  private external fun nativeReadRamU8(offset: Int): Int
  private external fun nativeWriteRamU8(offset: Int, value: Int): Boolean
  private external fun nativeStateSize(): Int
  private external fun nativeSaveState(buffer: ByteArray): Boolean
  private external fun nativeLoadState(buffer: ByteArray): Boolean
  private external fun nativeDrainAudio(target: ShortArray): Int
  private external fun nativeSetButton(id: Int, pressed: Boolean)
  private external fun nativeStop()
  override fun onCreate(state: Bundle?) { super.onCreate(state); window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY; renderer=SnesRenderer(); view=GLSurfaceView(this).apply { setEGLContextClientVersion(2); setRenderer(renderer); renderMode=GLSurfaceView.RENDERMODE_WHEN_DIRTY }; romGate=RomGate(this); bootMenu=BootMenu(this, { plan -> onPlayTapped(plan) }, { startRomBrowse() }); val root=FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); addView(bootMenu, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }; setContentView(root); displayManager=getSystemService(Context.DISPLAY_SERVICE) as DisplayManager; showStandby() }
  /**
   * While the boot menu is up, the dash display shows a standby screen
   * (blurred ZAMN map + wordmark) instead of black. The real
   * ZamnSecondScreen replaces it automatically in configureSecondaryDisplay()
   * when PLAY starts.
   */
  private fun showStandby() {
    val secondary = displayManager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY } ?: run { Log.i("SnesDisplay", "no secondary display for standby"); return }
    try {
      secondaryPresentation?.dismiss()
      secondaryPresentation = SecondaryDisplayPresentation(this, secondary, StandbyScreen(), { event -> this@MainActivity.dispatchKeyEvent(event) }).also { it.show() }
      Log.i("SnesDisplay", "standby screen on display ${secondary.displayId}")
    } catch (e: Exception) {
      Log.e("SnesDisplay", "unable to show standby screen", e)
    }
  }
  private fun configureSecondaryDisplay() { val displays=displayManager.displays; displays.forEach { display -> Log.i("SnesDisplay", "display id=${display.displayId} name=${display.name} size=${display.mode.physicalWidth}x${display.mode.physicalHeight} refresh=${display.refreshRate} default=${display.displayId==Display.DEFAULT_DISPLAY}") }; val secondary=displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }; Log.i("SnesDisplay", "gameplay display=${Display.DEFAULT_DISPLAY}; secondary display=${secondary?.displayId ?: "none"}"); if(secondary != null) try { secondaryPresentation?.dismiss(); secondaryPresentation=SecondaryDisplayPresentation(this,secondary,ZamnSecondScreen(gameMemory,emulatorState),{ event -> this@MainActivity.dispatchKeyEvent(event) }).also { it.show() } } catch(e: Exception) { Log.e("SnesDisplay","Unable to show secondary presentation",e) } }
  /**
   * PLAY entry point (boot menu). Stages the gate-approved ROM into app-private
   * storage — re-hashing the staged bytes against the reference (RomGate is the
   * single source of the hash rule) — and only then hands a verified file to
   * the core.
   */
  private fun onPlayTapped(plan: RomGate.PlayPlan) {
    if (started) return
    val rom = File(filesDir, "roms/active.sfc")
    if (!romGate.stageForPlay(plan, rom)) {
      bootMenu.reportError("PLAY blocked — the staged ROM failed the SHA-256 gate")
      return
    }
    try {
      start(rom)
    } catch (e: Exception) {
      if (coreStarted) { coreStarted = false; try { nativeStop() } catch (_: Exception) {} }
      Log.e("SnesBoot", "core failed to start", e)
      bootMenu.reportError("PLAY failed — ${e.message}")
      return
    }
    started = true
    bootMenu.visibility = View.GONE
    Log.i("SnesBoot", "playing ${rom.absolutePath} (${plan.note})")
  }
  private fun startRomBrowse() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
    try { startActivityForResult(intent, REQ_PICK_ROM) }
    catch (e: Exception) { Log.e("SnesBoot", "no document picker", e); bootMenu.reportError("no file picker available on this device") }
  }
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode != REQ_PICK_ROM || resultCode != RESULT_OK) return
    val uri = data?.data ?: return
    if (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
      try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
      catch (e: SecurityException) { Log.w("SnesBoot", "persistable URI permission denied", e) }
    }
    bootMenu.onBrowseResult(uri)
  }
  private fun start(rom: File) {
    if (!nativeStart(rom.absolutePath)) throw IllegalStateException("Could not load ROM: ${rom.name}")
    coreStarted = true
    renderer.buffers = Array(3) { nativeGetFrameBuffer(it) }
    val profile=GameProfileLoader.load(this,"game/zamn_profile.json")
    gameMemory=GameMemory(profile, object : NativeRam { override fun systemRamSize()=nativeGetSystemRamSize(); override fun readU8(offset:Int)=nativeReadRamU8(offset); override fun writeU8(offset:Int,value:Int)=nativeWriteRamU8(offset,value) })
    emulatorState=EmulatorState(object : NativeCoreState { override fun size()=nativeStateSize(); override fun save(buffer:ByteArray)=nativeSaveState(buffer); override fun load(buffer:ByteArray)=nativeLoadState(buffer) })
    Log.i("SnesWram", "System RAM exposed to Kotlin: ${nativeGetSystemRamSize()} bytes")
    configureSecondaryDisplay()
    Log.i("SnesVideo", "OpenGL renderer ready: attached=${view.isAttachedToWindow} visible=${view.visibility == View.VISIBLE} size=${view.width}x${view.height}")
    launchEmulation()
  }
  private fun launchEmulation() { running=true; val fps=nativeGetCoreFps(); val period=(1_000_000_000.0/fps).toLong(); val track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(32040).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()).setBufferSizeInBytes(65536).setTransferMode(AudioTrack.MODE_STREAM).build(); track.play()
    Thread { val samples=ShortArray(4096); var deadline=System.nanoTime(); while(running) { nativeRunFrame(); var count=nativeDrainAudio(samples); while(count>0) { track.write(samples,0,count,AudioTrack.WRITE_BLOCKING); count=nativeDrainAudio(samples) }; view.requestRender(); deadline+=period; val now=System.nanoTime(); if(now<deadline) LockSupport.parkNanos(deadline-now) else if(now-deadline>period*3) deadline=now }; track.stop(); track.release() }.start() }
  override fun onDestroy() { running=false; secondaryPresentation?.dismiss(); if (coreStarted) { coreStarted=false; nativeStop() }; super.onDestroy() }
  private fun button(code:Int)=when(code) { KeyEvent.KEYCODE_DPAD_UP->4; KeyEvent.KEYCODE_DPAD_DOWN->5; KeyEvent.KEYCODE_DPAD_LEFT->6; KeyEvent.KEYCODE_DPAD_RIGHT->7; KeyEvent.KEYCODE_BUTTON_B->0; KeyEvent.KEYCODE_BUTTON_A->8; KeyEvent.KEYCODE_BUTTON_X->9; KeyEvent.KEYCODE_BUTTON_Y->1; KeyEvent.KEYCODE_BUTTON_L1->10; KeyEvent.KEYCODE_BUTTON_R1->11; KeyEvent.KEYCODE_BUTTON_SELECT->2; KeyEvent.KEYCODE_BUTTON_START->3; else->-1 }
  override fun dispatchKeyEvent(e:KeyEvent):Boolean {
    // Before the core starts the menu owns the gamepad (PLAY / SELECT ROM);
    // afterwards the core polls its own input as before.
    if (!started && bootMenu.handleKey(e)) return true
    val b=button(e.keyCode); if(b>=0) { nativeSetButton(b,e.action==KeyEvent.ACTION_DOWN); return true }; return super.dispatchKeyEvent(e)
  }
  private lateinit var renderer: SnesRenderer
  inner class SnesRenderer : GLSurfaceView.Renderer {
    var buffers: Array<ByteBuffer>? = null
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f)); position(0) }
    private val texCoords: FloatBuffer = ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var program=0; private var texture=0; private var textureAllocated=false; private var viewW=1; private var viewH=1; private var presented=0L; private var started=0L; private var uploadNanos=0L
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { GLES20.glClearColor(0f,0f,0f,1f); program=program(); texture=IntArray(1).also { GLES20.glGenTextures(1,it,0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,it[0]); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE) }[0] }
    override fun onSurfaceChanged(gl: GL10?, width:Int, height:Int) { viewW=width; viewH=height }
    override fun onDrawFrame(gl: GL10?) { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); val pool=buffers ?: return; val packed=nativeGetLatestFrame(); val index=(packed ushr 48).toInt(); val sw=((packed ushr 24) and 0xffffff).toInt().coerceIn(1,512); val sh=(packed and 0xffffff).toInt().coerceIn(1,478); val pixels=pool[index]; pixels.position(0); pixels.limit(sw*sh*2); val uploadStart=System.nanoTime(); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture); if(!textureAllocated) { GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGB,512,478,0,GLES20.GL_RGB,GLES20.GL_UNSIGNED_SHORT_5_6_5,null); textureAllocated=true }; GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,sw,sh,GLES20.GL_RGB,GLES20.GL_UNSIGNED_SHORT_5_6_5,pixels); uploadNanos += System.nanoTime()-uploadStart; val aspect=4f/3f; if(viewW.toFloat()/viewH>aspect) { val w=(viewH*aspect).toInt(); GLES20.glViewport((viewW-w)/2,0,w,viewH) } else { val h=(viewW/aspect).toInt(); GLES20.glViewport(0,(viewH-h)/2,viewW,h) }; val u=sw/512f; val v=sh/478f; texCoords.clear(); texCoords.put(floatArrayOf(0f,v,u,v,0f,0f,u,0f)); texCoords.position(0); GLES20.glUseProgram(program); val pos=GLES20.glGetAttribLocation(program,"aPos"); val uv=GLES20.glGetAttribLocation(program,"aUv"); GLES20.glEnableVertexAttribArray(pos); GLES20.glVertexAttribPointer(pos,2,GLES20.GL_FLOAT,false,0,vertices); GLES20.glEnableVertexAttribArray(uv); GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,texCoords); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4); val now=System.nanoTime(); if(started==0L) started=now; if(++presented%180L==0L) Log.i("SnesPerf","coreFps=${nativeGetCoreFps()} presentedFps=${presented*1e9/(now-started)} avgRetroRun=${nativeGetAverageRunMs()}ms avgUpload=${uploadNanos/presented/1e6}ms") }
    private fun program(): Int {
      fun shader(type: Int, code: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, code)
        GLES20.glCompileShader(id)
        return id
      }
      val p = GLES20.glCreateProgram()
      GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){gl_Position=vec4(aPos,0.0,1.0);vUv=aUv;}"))
      GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec2 vUv; uniform sampler2D s; void main(){gl_FragColor=texture2D(s,vUv);}"))
      GLES20.glLinkProgram(p)
      return p
    }
  }
  companion object { const val REQ_PICK_ROM = 0x7; init { System.loadLibrary("snes_frontend") } }
}
