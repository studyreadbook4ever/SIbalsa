// SPDX-License-Identifier: Unlicense
#pragma once
#include <jni.h>
#include <atomic>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace sibalsa::jni {
template<class T> class Registry {
 public:
  explicit Registry(unsigned tag) : tag_(tag) {}
  jlong add(std::shared_ptr<T> value) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (sequence_ > (std::uint64_t(std::numeric_limits<jlong>::max()) >> 8)) throw std::runtime_error("handle space exhausted");
    const jlong id = static_cast<jlong>((sequence_++ << 8) | tag_);
    entries_.emplace(id, std::move(value)); return id;
  }
  std::shared_ptr<T> get(jlong id) {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto entry = entries_.find(id);
    if (id <= 0 || (id & 255) != tag_ || entry == entries_.end()) throw std::invalid_argument("invalid or closed native handle");
    return entry->second;
  }
  void remove(jlong id) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (id <= 0 || (id & 255) != tag_ || entries_.erase(id) != 1) throw std::invalid_argument("invalid or closed native handle");
  }
 private:
  unsigned tag_;
  std::uint64_t sequence_ = 1;
  std::mutex mutex_;
  std::unordered_map<jlong, std::shared_ptr<T>> entries_;
};
inline void exception(JNIEnv* env, const char* name, const char* text) {
  if (env->ExceptionCheck()) return;
  const auto cls = env->FindClass(name);
  if (cls) { env->ThrowNew(cls, text); env->DeleteLocalRef(cls); }
}
template<class F, class R> R invoke(JNIEnv* env, F&& fn, R fallback) noexcept {
  try { return fn(); }
  catch (const std::invalid_argument& error) { exception(env, "java/lang/IllegalArgumentException", error.what()); }
  catch (const std::bad_alloc&) { exception(env, "java/lang/OutOfMemoryError", "native runtime allocation failed"); }
  catch (const std::exception& error) { exception(env, "java/io/IOException", error.what()); }
  catch (...) { exception(env, "java/io/IOException", "unknown native runtime error"); }
  return fallback;
}
template<class F> void invokeVoid(JNIEnv* env, F&& fn) noexcept {
  invoke(env, [&] { fn(); return true; }, false);
}
inline std::string utf8(JNIEnv* env, jstring value) {
  if (!value) throw std::invalid_argument("path is required");
  const auto count = env->GetStringLength(value);
  if (count < 1 || count > 4096) throw std::invalid_argument("invalid path length");
  const auto* text = env->GetStringChars(value, nullptr);
  if (!text) throw std::bad_alloc();
  std::string out;
  try {
    for (jsize i = 0; i < count; ++i) {
      std::uint32_t cp = text[i];
      if (cp == 0) throw std::invalid_argument("path contains NUL");
      if (cp >= 0xd800 && cp <= 0xdbff) {
        if (++i >= count || text[i] < 0xdc00 || text[i] > 0xdfff) throw std::invalid_argument("invalid UTF16 path");
        cp = 0x10000 + ((cp - 0xd800) << 10) + text[i] - 0xdc00;
      } else if (cp >= 0xdc00 && cp <= 0xdfff) throw std::invalid_argument("invalid UTF16 path");
      if (cp < 0x80) out.push_back(static_cast<char>(cp));
      else if (cp < 0x800) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6))); out.push_back(static_cast<char>(0x80 | (cp & 63)));
      } else if (cp < 0x10000) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12))); out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63))); out.push_back(static_cast<char>(0x80 | (cp & 63)));
      } else {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18))); out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 63)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 63))); out.push_back(static_cast<char>(0x80 | (cp & 63)));
      }
    }
  } catch (...) { env->ReleaseStringChars(value, text); throw; }
  env->ReleaseStringChars(value, text); return out;
}
inline std::vector<std::int32_t> ints(JNIEnv* env, jintArray array, std::size_t maximum) {
  if (!array) throw std::invalid_argument("token array is required");
  const auto count = env->GetArrayLength(array);
  if (count <= 0 || std::size_t(count) > maximum) throw std::invalid_argument("token sequence length out of bounds");
  std::vector<std::int32_t> values(static_cast<std::size_t>(count));
  static_assert(sizeof(jint) == sizeof(std::int32_t), "JNI integer width");
  env->GetIntArrayRegion(array, 0, count, reinterpret_cast<jint*>(values.data()));
  if (env->ExceptionCheck()) throw std::runtime_error("cannot read token array");
  return values;
}
inline std::vector<float> floats(JNIEnv* env, jfloatArray array, std::size_t expected) {
  if (!array || std::size_t(env->GetArrayLength(array)) != expected) throw std::invalid_argument("adapter array shape mismatch");
  std::vector<float> values(expected);
  env->GetFloatArrayRegion(array, 0, static_cast<jsize>(expected), values.data());
  if (env->ExceptionCheck()) throw std::runtime_error("cannot read adapter array");
  for (float value : values) if (!std::isfinite(value)) throw std::invalid_argument("non-finite adapter value");
  return values;
}
inline jfloatArray array(JNIEnv* env, const std::vector<float>& values) {
  if (values.size() > std::size_t(std::numeric_limits<jsize>::max())) throw std::invalid_argument("native result array too large");
  auto out = env->NewFloatArray(static_cast<jsize>(values.size()));
  if (!out) throw std::bad_alloc();
  env->SetFloatArrayRegion(out, 0, static_cast<jsize>(values.size()), values.data());
  return out;
}
}  // namespace sibalsa::jni
