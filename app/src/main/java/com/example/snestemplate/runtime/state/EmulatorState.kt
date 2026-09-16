package com.example.snestemplate.runtime.state

/**
 * Raw libretro core save-state access (JNI). Backed by the working native
 * bridge in frontend.cpp: nativeStateSize/nativeSaveState/nativeLoadState,
 * which lock the core the same way the RAM accessors do, so serialization
 * never interleaves with the emulation thread's retro_run().
 *
 * Snes9x's unfreeze reads the whole snapshot into local buffers and commits
 * only on full success, so a corrupt/incompatible state returns false and
 * leaves the running game untouched.
 */
interface NativeCoreState {
  fun size(): Int
  fun save(buffer: ByteArray): Boolean
  fun load(buffer: ByteArray): Boolean
}

/** Small profile-independent emulator save-state API for game UIs. */
class EmulatorState(private val core: NativeCoreState) {
  /** Full core save-state snapshot, or null if unavailable this moment. */
  fun snapshot(): ByteArray? {
    val size = core.size()
    if (size <= 0) return null
    val buffer = ByteArray(size)
    return if (core.save(buffer)) buffer else null
  }

  /** Restore a snapshot taken by [snapshot]; false if the core rejects it. */
  fun restore(state: ByteArray): Boolean = state.isNotEmpty() && core.load(state)
}
