// SPDX-License-Identifier: Unlicense
#include "sibalsa/training.h"
#include "jni_common.h"

namespace {
struct Trainer {
  Trainer(std::shared_ptr<const sibalsa::Model> model, sibalsa::LoRAAdapter adapter, std::uint32_t capacity)
      : value(std::move(model), std::move(adapter), capacity) {}
  sibalsa::TrainingRuntime value;
  std::mutex mutex;
};
sibalsa::jni::Registry<Trainer> trainers(3);
}
extern "C" {
JNIEXPORT jlong JNICALL Java_ai_sibalsa_runtime_TrainingBridge_createTrainer(JNIEnv* env, jclass, jstring path,
                                                                          jint rank, jlong seed, jint capacity) {
  return sibalsa::jni::invoke(env, [&] {
    if (rank <= 0 || rank > 256 || capacity <= 1) throw std::invalid_argument("invalid rank/training context");
    const auto model = sibalsa::Model::load(sibalsa::jni::utf8(env, path));
    auto adapter = sibalsa::LoRAAdapter::initialize(*model, static_cast<std::uint32_t>(rank), static_cast<std::uint64_t>(seed));
    return trainers.add(std::make_shared<Trainer>(model, std::move(adapter), static_cast<std::uint32_t>(capacity)));
  }, jlong(0));
}
JNIEXPORT jstring JNICALL Java_ai_sibalsa_runtime_TrainingBridge_modelSha256(JNIEnv* env, jclass, jlong id) {
  return sibalsa::jni::invoke(env, [&] {
    const auto trainer = trainers.get(id);
    auto out = env->NewStringUTF(trainer->value.modelSha256().c_str());
    if (!out) throw std::bad_alloc();
    return out;
  }, static_cast<jstring>(nullptr));
}
JNIEXPORT jfloat JNICALL Java_ai_sibalsa_runtime_TrainingBridge_trainSft(JNIEnv* env, jclass, jlong id, jintArray sequence,
                                                                     jint prompt, jfloat rate, jfloat clip) {
  return sibalsa::jni::invoke(env, [&] {
    if (prompt < 1) throw std::invalid_argument("invalid prompt length");
    const auto t = trainers.get(id); std::lock_guard<std::mutex> lock(t->mutex);
    const auto tokens = sibalsa::jni::ints(env, sequence, t->value.capacity());
    return static_cast<jfloat>(t->value.trainSft(tokens, static_cast<std::uint32_t>(prompt), rate, clip));
  }, jfloat(0));
}
JNIEXPORT jfloat JNICALL Java_ai_sibalsa_runtime_TrainingBridge_trainDpo(JNIEnv* env, jclass, jlong id, jintArray prompt,
                                                                     jintArray chosen, jintArray rejected,
                                                                     jfloat beta, jfloat rate, jfloat clip) {
  return sibalsa::jni::invoke(env, [&] {
    const auto t = trainers.get(id); std::lock_guard<std::mutex> lock(t->mutex);
    const auto p = sibalsa::jni::ints(env, prompt, t->value.capacity());
    const auto a = sibalsa::jni::ints(env, chosen, t->value.capacity());
    const auto b = sibalsa::jni::ints(env, rejected, t->value.capacity());
    return static_cast<jfloat>(t->value.trainDpo(p, a, b, beta, rate, clip));
  }, jfloat(0));
}
JNIEXPORT jfloatArray JNICALL Java_ai_sibalsa_runtime_TrainingBridge_adapterParameters(JNIEnv* env, jclass, jlong id) {
  return sibalsa::jni::invoke(env, [&] {
    const auto t = trainers.get(id); std::lock_guard<std::mutex> lock(t->mutex);
    return sibalsa::jni::array(env, t->value.adapter().parameters);
  }, static_cast<jfloatArray>(nullptr));
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_TrainingBridge_setAdapterParameters(JNIEnv* env, jclass, jlong id,
                                                                                 jfloatArray params) {
  sibalsa::jni::invokeVoid(env, [&] {
    const auto t = trainers.get(id); std::lock_guard<std::mutex> lock(t->mutex);
    t->value.setParameters(sibalsa::jni::floats(env, params, t->value.adapter().parameters.size()), true);
  });
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_TrainingBridge_cancelTrainer(JNIEnv* env, jclass, jlong id) {
  sibalsa::jni::invokeVoid(env, [&] { trainers.get(id)->value.cancel(); });
}
JNIEXPORT void JNICALL Java_ai_sibalsa_runtime_TrainingBridge_closeTrainer(JNIEnv* env, jclass, jlong id) {
  sibalsa::jni::invokeVoid(env, [&] { trainers.get(id)->value.cancel(); trainers.remove(id); });
}
}
