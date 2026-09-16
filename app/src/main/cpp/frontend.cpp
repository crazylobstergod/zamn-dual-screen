#include <jni.h>
#include <android/log.h>
#include <vector>
#include <mutex>
#include <cstring>
#include <atomic>
#include <chrono>
#include "libretro.h"

static JavaVM *vm;
static std::mutex audioLock;
static std::mutex coreLock;
static std::vector<int16_t> audio;
static bool buttons[16]{};
static unsigned frameWidth = 256, frameHeight = 224;
static std::vector<uint16_t> frameBuffers[3];
static jobject frameBufferObjects[3]{};
static std::atomic<int> latestBuffer{0};
static int writeBuffer = 1;
static float coreFps = 60.0f;
static retro_pixel_format negotiatedFormat = RETRO_PIXEL_FORMAT_0RGB1555;
static std::atomic<uint64_t> videoFrames{0};
static std::atomic<uint64_t> bitmapLockFailures{0};
static std::atomic<uint64_t> runNanos{0}, copyNanos{0}, perfFrames{0};

static const char *formatName(retro_pixel_format format) {
  switch (format) {
    case RETRO_PIXEL_FORMAT_RGB565: return "RGB565";
    case RETRO_PIXEL_FORMAT_XRGB8888: return "XRGB8888";
    case RETRO_PIXEL_FORMAT_0RGB1555: return "0RGB1555";
    default: return "unknown";
  }
}

static JNIEnv *currentEnv() {
  if (!vm) return nullptr;
  JNIEnv *env = nullptr;
  return vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK ? env : nullptr;
}

static bool environment(unsigned cmd, void *data) {
  switch (cmd) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
      const auto requested = *(retro_pixel_format*)data;
      const bool accepted = requested == RETRO_PIXEL_FORMAT_RGB565;
      if (accepted) negotiatedFormat = requested;
      __android_log_print(ANDROID_LOG_INFO, "SnesVideo", "core requested pixel format=%s (%d), accepted=%d", formatName(requested), requested, accepted);
      return accepted;
    }
    case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data = true; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data = false; return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: return true;
    default: return false;
  }
}
static void video(const void *pixels, unsigned width, unsigned height, size_t pitch) {
  const auto copyStart = std::chrono::steady_clock::now();
  const uint64_t count = ++videoFrames;
  if (!pixels) {
    __android_log_print(ANDROID_LOG_WARN, "SnesVideo", "video frame=%llu ptr=null; skipped", count);
    return;
  }
  auto *dst = reinterpret_cast<uint8_t *>(frameBuffers[writeBuffer].data());
  const size_t rowBytes = width * 2; // RGB565 was explicitly negotiated above.
  const unsigned rows = height < 478 ? height : 478;
  const size_t bytesPerRow = rowBytes < pitch ? rowBytes : pitch;
  auto *out = static_cast<uint8_t*>(dst); auto *in = static_cast<const uint8_t*>(pixels);
  for (unsigned y=0; y<rows; ++y)
    memcpy(dst + y * rowBytes, in + y * pitch, bytesPerRow);
  copyNanos += std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - copyStart).count();
  if (count <= 3 || count % 120 == 0)
    __android_log_print(ANDROID_LOG_INFO, "SnesVideo", "frame=%llu %ux%u pitch=%zu ptr=%p core=%s native-buffer copiedRows=%u", count, width, height, pitch, pixels, formatName(negotiatedFormat), rows);
  frameWidth=width; frameHeight=height;
  latestBuffer.store(writeBuffer, std::memory_order_release);
  writeBuffer = (writeBuffer + 1) % 3;
  if (writeBuffer == latestBuffer.load(std::memory_order_acquire)) writeBuffer = (writeBuffer + 1) % 3;
}
static void audioSample(int16_t left, int16_t right) { std::lock_guard<std::mutex> l(audioLock); audio.push_back(left); audio.push_back(right); }
static size_t audioBatch(const int16_t *data, size_t frames) { std::lock_guard<std::mutex> l(audioLock); audio.insert(audio.end(), data, data + frames * 2); return frames; }
static void inputPoll() {}
static int16_t inputState(unsigned port, unsigned, unsigned, unsigned id) { return port == 0 && id < 16 && buttons[id]; }

extern "C" JNIEXPORT jboolean JNICALL Java_com_example_snestemplate_MainActivity_nativeStart(JNIEnv *env, jobject, jstring rom) {
  for (int i=0;i<3;i++) { frameBuffers[i].assign(512*478, 0); if (frameBufferObjects[i]) env->DeleteGlobalRef(frameBufferObjects[i]); frameBufferObjects[i] = env->NewGlobalRef(env->NewDirectByteBuffer(frameBuffers[i].data(), frameBuffers[i].size()*sizeof(uint16_t))); }
  const char *path = env->GetStringUTFChars(rom, nullptr);
  retro_set_environment(environment); retro_set_video_refresh(video); retro_set_audio_sample(audioSample); retro_set_audio_sample_batch(audioBatch); retro_set_input_poll(inputPoll); retro_set_input_state(inputState);
  retro_init(); retro_game_info game{}; game.path = path; bool ok = retro_load_game(&game);
  retro_system_av_info av{}; retro_get_system_av_info(&av); coreFps = av.timing.fps > 1.0 ? (float)av.timing.fps : 60.0f;
  __android_log_print(ANDROID_LOG_INFO, "SnesPerf", "core target fps=%.6f sampleRate=%.1f", coreFps, av.timing.sample_rate);
  __android_log_print(ANDROID_LOG_INFO, "SnesWram", "RETRO_MEMORY_SYSTEM_RAM size=%zu bytes", retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM));
  env->ReleaseStringUTFChars(rom, path); return ok;
}
extern "C" JNIEXPORT jobject JNICALL Java_com_example_snestemplate_MainActivity_nativeGetFrameBuffer(JNIEnv *env, jobject, jint index) { return index >= 0 && index < 3 ? env->NewLocalRef(frameBufferObjects[index]) : nullptr; }
extern "C" JNIEXPORT jlong JNICALL Java_com_example_snestemplate_MainActivity_nativeGetLatestFrame(JNIEnv*, jobject) { return (static_cast<jlong>(latestBuffer.load(std::memory_order_acquire)) << 48) | (static_cast<jlong>(frameWidth) << 24) | frameHeight; }
extern "C" JNIEXPORT jfloat JNICALL Java_com_example_snestemplate_MainActivity_nativeGetCoreFps(JNIEnv*, jobject) { return coreFps; }
extern "C" JNIEXPORT jfloat JNICALL Java_com_example_snestemplate_MainActivity_nativeGetAverageRunMs(JNIEnv*, jobject) { const auto n=perfFrames.load(); return n ? (jfloat)((double)runNanos.load()/n/1000000.0) : 0.0f; }
extern "C" JNIEXPORT void JNICALL Java_com_example_snestemplate_MainActivity_nativeRunFrame(JNIEnv*, jobject) { const auto start=std::chrono::steady_clock::now(); { std::lock_guard<std::mutex> lock(coreLock); retro_run(); } runNanos += std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count(); ++perfFrames; }
extern "C" JNIEXPORT jint JNICALL Java_com_example_snestemplate_MainActivity_nativeGetSystemRamSize(JNIEnv*, jobject) { std::lock_guard<std::mutex> lock(coreLock); return (jint)retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM); }
extern "C" JNIEXPORT jint JNICALL Java_com_example_snestemplate_MainActivity_nativeReadRamU8(JNIEnv*, jobject, jint offset) { std::lock_guard<std::mutex> lock(coreLock); const size_t size=retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM); auto *ram=static_cast<const uint8_t*>(retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)); return ram && offset >= 0 && (size_t)offset < size ? ram[offset] : -1; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_snestemplate_MainActivity_nativeWriteRamU8(JNIEnv*, jobject, jint offset, jint value) { std::lock_guard<std::mutex> lock(coreLock); const size_t size=retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM); auto *ram=static_cast<uint8_t*>(retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM)); if (!ram || offset < 0 || (size_t)offset >= size || value < 0 || value > 255) return JNI_FALSE; ram[offset]=(uint8_t)value; return JNI_TRUE; }
// -- libretro save states --------------------------------------------------
// All three take coreLock, exactly like the RAM accessors, so serialize /
// unserialize never interleave with the emulation thread's retro_run().
extern "C" JNIEXPORT jint JNICALL Java_com_example_snestemplate_MainActivity_nativeStateSize(JNIEnv*, jobject) { std::lock_guard<std::mutex> lock(coreLock); return (jint)retro_serialize_size(); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_snestemplate_MainActivity_nativeSaveState(JNIEnv *env, jobject, jbyteArray buffer) {
  std::lock_guard<std::mutex> lock(coreLock);
  const size_t size = retro_serialize_size();
  if (size == 0 || !buffer || env->GetArrayLength(buffer) != (jsize)size) return JNI_FALSE;
  jbyte *bytes = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(buffer, nullptr));
  if (!bytes) return JNI_FALSE;
  const bool ok = retro_serialize(bytes, size) != 0;
  env->ReleasePrimitiveArrayCritical(buffer, bytes, JNI_ABORT);
  __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, "SnesState", "save_state: %zu bytes ok=%d", size, ok);
  return ok ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_example_snestemplate_MainActivity_nativeLoadState(JNIEnv *env, jobject, jbyteArray buffer) {
  std::lock_guard<std::mutex> lock(coreLock);
  if (!buffer) return JNI_FALSE;
  const jsize size = env->GetArrayLength(buffer);
  if (size <= 0) return JNI_FALSE;
  jbyte *bytes = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(buffer, nullptr));
  if (!bytes) return JNI_FALSE;
  const bool ok = retro_unserialize(bytes, (size_t)size) != 0;
  env->ReleasePrimitiveArrayCritical(buffer, bytes, JNI_ABORT);
  __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, "SnesState", "load_state: %d bytes ok=%d (Snes9x commits only on full success)", size, ok);
  return ok ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jint JNICALL Java_com_example_snestemplate_MainActivity_nativeDrainAudio(JNIEnv *env, jobject, jshortArray target) {
  std::lock_guard<std::mutex> l(audioLock); jsize n = env->GetArrayLength(target); size_t copied = (audio.size() < (size_t)n ? audio.size() : (size_t)n); if (copied) env->SetShortArrayRegion(target, 0, copied, audio.data()); audio.erase(audio.begin(), audio.begin()+copied); return (jint)copied;
}
extern "C" JNIEXPORT void JNICALL Java_com_example_snestemplate_MainActivity_nativeSetButton(JNIEnv*, jobject, jint id, jboolean pressed) { if (id >= 0 && id < 16) buttons[id] = pressed; }
extern "C" JNIEXPORT void JNICALL Java_com_example_snestemplate_MainActivity_nativeStop(JNIEnv *env, jobject) { retro_unload_game(); retro_deinit(); for (auto &object : frameBufferObjects) if(object) { env->DeleteGlobalRef(object); object=nullptr; } }
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *javaVm, void *) { vm = javaVm; return JNI_VERSION_1_6; }
