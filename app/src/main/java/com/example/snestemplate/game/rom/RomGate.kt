package com.example.snestemplate.game.rom

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * ZAMN ROM gate — the one hard rule of this app:
 *
 * The second-screen memory map (assets/game/zamn_profile.json) was discovered
 * on one specific ZAMN build, so the emulator must only ever start with a ROM
 * whose SHA-256 equals [REFERENCE_SHA256]. A different ROM, region, or patch
 * fails the hash and is rejected — the gate never silently runs an unverified
 * ROM.
 *
 * The ROM is NOT bundled in the APK (external load only): the user picks a ROM
 * file on their device (SAF content URI) or types an absolute path, and the
 * choice is one-time — it persists under filesDir/rom/selection.json, is
 * re-verified at every launch, and is what PLAY runs. There is no fallback:
 * if no selection verifies, PLAY stays disabled.
 *
 * [REFERENCE_SHA256] is hardcoded because there is no bundled ROM to derive it
 * from; it is the hash of the original dev ROM that used to ship as
 * assets/roms/default.sfc. If the approved build ever changes, change the
 * constant (and re-discover the memory profile if the ROM itself differs).
 *
 * The gate is enforced TWICE: when the selection is made (menu) and again at
 * Play time — [stageForPlay] re-hashes the bytes it actually stages, so a file
 * that changes between selection and Play is still refused.
 */
class RomGate(private val context: Context) {

  enum class Kind { PATH, URI }

  /** A user-selected ROM location (persisted after it verifies). */
  data class Selection(val kind: Kind, val ref: String) {
    fun describe(): String = ref
  }

  /** Outcome of hash-verifying a selection against [REFERENCE_SHA256]. */
  data class VerifyResult(val match: Boolean, val sha256: String?, val error: String?)

  /** A concrete PLAY plan: which selection to stage, and the hash its bytes must have. */
  data class PlayPlan(val selection: Selection, val sha256: String, val note: String)

  /**
   * "What can PLAY run right now?" [plan] == null when no hash-verified ROM is
   * available (nothing selected, or the saved selection failed); [message]
   * explains that state for the menu.
   */
  data class Resolution(val plan: PlayPlan?, val message: String)

  private val storeFile get() = File(context.filesDir, "rom/selection.json")

  fun storedSelection(): Selection? {
    val f = storeFile
    if (!f.exists()) return null
    return try {
      val text = f.readText()
      val kind = extract(text, "kind") ?: return null
      val ref = extract(text, "ref") ?: return null
      Selection(Kind.valueOf(kind), unescape(ref))
    } catch (e: Exception) {
      Log.w(TAG, "stored selection unreadable; ignoring", e)
      null
    }
  }

  fun storeSelection(sel: Selection, sha256: String) {
    val f = storeFile
    f.parentFile?.mkdirs()
    f.writeText(
      "{\"kind\":\"" + sel.kind.name + "\",\"ref\":\"" + escape(sel.ref) + "\"," +
        "\"sha256\":\"" + sha256 + "\",\"chosenAt\":" + System.currentTimeMillis() + "}\n"
    )
  }

  fun clearSelection() { if (storeFile.exists()) storeFile.delete() }

  fun openStream(sel: Selection): InputStream? = try {
    when (sel.kind) {
      Kind.PATH -> File(sel.ref).inputStream()
      Kind.URI -> context.contentResolver.openInputStream(Uri.parse(sel.ref))
    }
  } catch (e: Exception) {
    Log.w(TAG, "cannot open selected ROM ${sel.ref}", e)
    null
  }

  /** Stream [sel] through SHA-256 and compare it to [REFERENCE_SHA256]. */
  fun verify(sel: Selection): VerifyResult {
    val stream = openStream(sel) ?: return VerifyResult(false, null, "cannot open the selected file")
    return try {
      val hash = sha256Hex(stream)
      VerifyResult(hash.equals(REFERENCE_SHA256, ignoreCase = true), hash, null)
    } catch (e: Exception) {
      Log.w(TAG, "hash verification failed for ${sel.ref}", e)
      VerifyResult(false, null, e.message ?: "hash read error")
    } finally {
      try { stream.close() } catch (_: Exception) {}
    }
  }

  /** Decide what PLAY should run right now (call at launch and after selection changes). */
  fun resolve(): Resolution {
    val saved = storedSelection()
    if (saved == null) {
      return Resolution(null, "no ROM selected — pick the ROM file first (SELECT ROM)")
    }
    val v = verify(saved)
    return when {
      v.error != null -> Resolution(null, "saved ROM is unreadable (${v.error}) — re-select the ROM file")
      v.match -> Resolution(PlayPlan(saved, REFERENCE_SHA256, "using your ROM: ${saved.describe()}"), "")
      else -> Resolution(
        null,
        "saved ROM REJECTED — SHA-256 ${v.sha256?.take(20) ?: "??"}… \u2260 approved ${REFERENCE_SHA256.take(20)}… — re-select the correct ROM"
      )
    }
  }

  /**
   * Stage the plan's ROM to [dest] and re-hash the bytes actually copied.
   * Returns true only if the staged file hashes to [PlayPlan.sha256] —
   * the last gate between the user and nativeStart().
   */
  fun stageForPlay(plan: PlayPlan, dest: File): Boolean {
    dest.parentFile?.mkdirs()
    val input = openStream(plan.selection) ?: return false
    return try {
      input.use { inStream ->
        val hash = dest.outputStream().use { out ->
          val digest = MessageDigest.getInstance("SHA-256")
          val buf = ByteArray(64 * 1024)
          while (true) {
            val n = inStream.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
            out.write(buf, 0, n)
          }
          digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!hash.equals(plan.sha256, ignoreCase = true)) {
          Log.w(TAG, "stage: ROM hash drift — staged $hash, expected ${plan.sha256}; refusing")
          false
        } else {
          true
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "stage: copy/hash failed", e)
      false
    }
  }

  companion object {
    /**
     * SHA-256 of the approved ZAMN build — the ONLY ROM this app will run.
     * Hardcoded (no bundled ROM to derive it from): this is the hash of the
     * original dev ROM, `b27e2e957fa760f4f483e2af30e03062 034a6c0066984f2e
     * 284cc2cb430b2059`.
     */
    const val REFERENCE_SHA256 = "b27e2e957fa760f4f483e2af30e03062034a6c0066984f2e284cc2cb430b2059"
    private const val TAG = "ZamnRomGate"

    fun sha256Hex(stream: InputStream): String {
      val digest = MessageDigest.getInstance("SHA-256")
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = stream.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun escape(s: String): String =
      s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun unescape(s: String): String {
      val out = StringBuilder(s.length)
      var i = 0
      while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
          when (val n = s[i + 1]) {
            'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
            '"' -> out.append('"'); '\\' -> out.append('\\')
            else -> { out.append(c); i++; continue }
          }
          i += 2
        } else { out.append(c); i++ }
      }
      return out.toString()
    }

    private fun extract(text: String, key: String): String? =
      Regex("\"$key\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)
  }
}
