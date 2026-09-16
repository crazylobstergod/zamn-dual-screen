package com.example.snestemplate.runtime.memory

import com.example.snestemplate.game.profile.GameProfile
import com.example.snestemplate.game.profile.MemoryField

interface NativeRam {
  fun systemRamSize(): Int
  fun readU8(offset: Int): Int
  fun writeU8(offset: Int, value: Int): Boolean
}

class GameMemory(private val profile: GameProfile, private val ram: NativeRam) {
  private fun field(id: String): MemoryField = profile.fields[id] ?: error("No verified memory field named '$id'")
  fun readU8(id: String): Int { val f=field(id); require(f.type == "u8") { "$id is not a u8 field" }; require(f.offset in 0 until ram.systemRamSize()) { "$id is outside system RAM" }; return ram.readU8(f.offset) }
  fun writeU8(id: String, value: Int): Boolean { val f=field(id); require(f.type == "u8") { "$id is not a u8 field" }; require(f.access == "read_write") { "$id is not writable" }; require(value in 0..255) { "u8 value out of range" }; require(f.offset in 0 until ram.systemRamSize()) { "$id is outside system RAM" }; return ram.writeU8(f.offset, value) }

  /**
   * Read-only raw byte access (no profile field required). Used by the DEBUG
   * "NEIGHBOR SCAN" diff tool while hunting the real in-game counters; reads
   * never mutate game state, so this is safe to keep.
   */
  fun rawReadU8(offset: Int): Int {
    require(offset in 0 until ram.systemRamSize()) { "raw offset $offset is outside system RAM" }
    return ram.readU8(offset)
  }
}
