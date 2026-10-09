// SPDX-License-Identifier: Unlicense
#include "sibalsa/runtime.h"
#include "jni_common.h"

namespace {
struct Session {
  explicit Session(std::shared_ptr<const sibalsa::Model> model,
                   std::shared_ptr<const sibalsa::LoRAAdapter> adapter, std::uint32_t capacity)
      : value(std::move(model), std::move(adapter), capacity) {}
  sibalsa::InferenceSession value;
  std::mutex mutex;
};
sibalsa::jni::Registry<const sibalsa::Model> models(1);
sibalsa::jni::Registry<Session> sessions(2);
}
extern "C" {
JNIEXPORT jlong JNICALL Java_ai_sibalsa_runtime_InferenceBridge_loadModel(JNIEnv* env, jclass, jstring path) {
  return sibalsa::jni::invoke(env, [&] { return models.add(sibalsa::Model::load(sibalsa::jni::utf8(env, path))); }, jlong(0));
}
JNIEXPORT jintArray JNICALL Java_ai_sibalsa_runtime_InferenceBridge_modelInfo(JNIEnv* env, jclass, jlong id) {
  return sibalsa::jni::invoke(env, [&] {
    const auto model = models.get(id);
    const auto& c = model->config();
    jint values[3] = {static_cast<jint>(c.vocab), static_cast<jint>(c.dim), static_cast<jint>(c.maxContext)};
    auto out = env->NewIntArray(3); if (!out) throw std::bad_alloc();
    env->SetIntArrayRegion(out, 0, 3, values); return out;
  }, static_cast<jintArray>(nullptr));
}
JNIEXPORT jstring JNICALL Java_ai_sibalsa_runtime_InferenceBridge_modelSha256(JNIEnv* env, jclass, jlong id) {
  return sibalsa::jni::invoke(env, [&] {
    const auto model = models.get(id);
    auto out = env->NewStringUTF(model->sha256().c_str());
    if (!out) throw std::bad_alloc();
    return out;
  }, static_cast<jstring>(nullptr));
}
JNIEXPORT jlong JNICALL Java_ai_sibalsa_runtime_InferenceBridge_createSession(JNIEnv* env, jclass, jlong id,
                                                                           jfloatArray params, jint rank, jint capacity) {
  return sibalsa::jni::invoke(env, [&] {
    if (rank <= 0 || rank > 256 || capacity <= 0) throw std::invalid_argument("invalid rank/session context");
    const auto model = models.get(id);
    std::shared_ptr<sibalsa::LoRAAdapter> adapter;
    if (params) {
      adapter = std::make_shared<sibalsa::LoRAAdapter>(sibalsa::LoRAAdapter::initialize(*model, rank, 0));
      adapter->parameters = sibalsa::jni::floats(env, params, adapter->parameters.size());
      adapter->validate(*model);
    }
    return sessions.add(std::make_shared<Session>(model, adapter, static_cast<std::uint32_t>(capacity)));
  }, jlong(0));
}
JNIEXPORT jfloatArray JNICALL Java_ai_sibalsa_runtime_InferenceBridge_nextLogits(JNIEnv* env, jclass, jlong id, jint token) {
  return sibalsa::jni::invoke(env, [&] {
    const auto session = sessions.get(id); std::lock_guard<std::mutex> lock(session->mutex);
    return sibalsa::jni::array(env, session->value.step(token));
  }, static_cast<jfloatArray>(nullptr));
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_InferenceBridge_resetSession(JNIEnv* env, jclass, jlong id) {
  sibalsa::jni::invokeVoid(env, [&] { const auto s = sessions.get(id); std::lock_guard<std::mutex> lock(s->mutex); s->value.reset(); });
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_InferenceBridge_cancelSession(JNIEnv* env, jclass, jlong id) {
  sibalsa::jni::invokeVoid(env, [&] { sessions.get(id)->value.cancel(); });
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_InferenceBridge_closeSession(JNIEnv* env, jclass, jlong id) {
  sibalsa::jni::invokeVoid(env, [&] { sessions.get(id)->value.cancel(); sessions.remove(id); });
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_InferenceBridge_closeModel(JNIEnv* env, jclass, jlong id) {
  sibalsa::jni::invokeVoid(env, [&] { models.remove(id); });
}
}
