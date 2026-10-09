// SPDX-License-Identifier: Unlicense
#include "fixture.h"
#include "sibalsa/training.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <functional>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <thread>
#include <unistd.h>

namespace {
void expect(bool good, const std::string& message) { if (!good) throw std::runtime_error(message); }
void near(double a, double b, double tolerance, const std::string& message) {
  expect(std::isfinite(a) && std::isfinite(b) && std::abs(a - b) <= tolerance,
         message + ": " + std::to_string(a) + " vs " + std::to_string(b));
}
void throws(const std::function<void()>& action, const std::string& message) {
  try { action(); } catch (const std::exception&) { return; }
  throw std::runtime_error("expected rejection: " + message);
}
std::vector<unsigned char> read(const std::string& path) {
  std::ifstream in(path, std::ios::binary | std::ios::ate);
  std::vector<unsigned char> bytes(static_cast<std::size_t>(in.tellg()));
  in.seekg(0); in.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())); return bytes;
}
void write(const std::string& path, const std::vector<unsigned char>& bytes) {
  std::ofstream out(path, std::ios::binary);
  out.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
}
void put32(std::vector<unsigned char>& b, std::size_t i, std::uint32_t v) {
  for (int j = 0; j < 4; ++j) b[i+j] = static_cast<unsigned char>(v >> (8*j));
}
std::uint64_t get64(const std::vector<unsigned char>& b, std::size_t i) {
  std::uint64_t v = 0; for (int j = 0; j < 8; ++j) v |= std::uint64_t(b[i+j]) << (8*j); return v;
}
struct Temporary {
  std::string dir;
  Temporary() { char path[] = "/tmp/sibalsa-native-test-XXXXXX"; const auto p = mkdtemp(path); if (!p) throw std::runtime_error("mkdtemp"); dir = p; }
  ~Temporary() {
    for (const auto& name : {"float.sib", "int8.sib", "bad.sib", "adapter.siblora", "large.sib"}) unlink((dir + "/" + name).c_str());
    rmdir(dir.c_str());
  }
  std::string file(const std::string& name) const { return dir + "/" + name; }
};
}
int main() {
  try {
    Temporary temp;
    const auto floatPath = temp.file("float.sib"), int8Path = temp.file("int8.sib"), badPath = temp.file("bad.sib");
    sibalsa::test::writeFixture(floatPath); sibalsa::test::writeFixture(int8Path, true);
    const auto model = sibalsa::Model::load(floatPath), quantized = sibalsa::Model::load(int8Path);
    expect(model->config().heads == 2 && model->config().kvHeads == 1, "fixture must test GQA");
    const unsigned char abc[] = {'a','b','c'};
    expect(sibalsa::sha256(abc, 3) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA256 known vector");
    expect(model->sha256() == sibalsa::sha256(read(floatPath).data(), read(floatPath).size()), "model hash identity");

    const std::vector<std::int32_t> tokens = {1, 2, 4, 3, 6};
    const auto full = model->forwardHidden(tokens);
    sibalsa::InferenceSession session(model);
    for (std::size_t t = 0; t < tokens.size(); ++t) {
      const auto logits = session.step(tokens[t]);
      const auto expected = model->tensor("output").multiply(full[t]);
      for (std::size_t i = 0; i < logits.size(); ++i) near(logits[i], expected[i], 2e-6, "KV vs independent fullforward");
    }
    const auto shortForward = model->forwardHidden({1, 2});
    for (std::size_t i = 0; i < full[0].size(); ++i) near(full[1][i], shortForward[1][i], 1e-6, "causal mask future independence");
    sibalsa::InferenceSession quantSession(quantized);
    session.reset();
    for (auto token : tokens) {
      const auto a = session.step(token), b = quantSession.step(token);
      for (std::size_t i = 0; i < a.size(); ++i) near(a[i], b[i], 0.012, "rowwise int8 close to float");
    }
    session.cancel(); throws([&] { session.step(1); }, "inference cancellation");
    session.reset(); expect(session.size() == 0, "reset clears cache position");
    session.step(1);
    throws([&] { session.step(-1); }, "negative token");
    throws([&] { session.step(8); }, "token overflow");
    expect(session.size() == 1, "failed token leaves position unchanged");
    sibalsa::InferenceSession bounded(model, {}, 2);
    bounded.step(1); bounded.step(2); throws([&] { bounded.step(3); }, "bounded KV cache");
    throws([&] { sibalsa::InferenceSession invalid(model, {}, 33); }, "invalid cache capacity");
    session.reset(); const auto generated = session.greedyGenerate({1, 2}, 4); expect(generated.size() == 4, "real autoregressive generation");

    auto adapter = sibalsa::LoRAAdapter::initialize(*model, 2, 123);
    const auto initial = adapter.parameters;
    expect(initial == sibalsa::LoRAAdapter::initialize(*model, 2, 123).parameters, "deterministic shared initialization");
    expect(initial != sibalsa::LoRAAdapter::initialize(*model, 2, 124).parameters, "seed changes initialization");
    for (std::size_t i = 16; i < initial.size(); ++i) expect(initial[i] == 0, "B starts at zero");
    sibalsa::TrainingRuntime trainer(model, adapter);
    const std::vector<std::int32_t> sequence = {1, 2, 3, 3};
    const auto before = trainer.sftGradient(sequence, 2).loss;
    for (int i = 0; i < 180; ++i) trainer.trainSft(sequence, 2, 0.1f, 2.0f);
    const auto after = trainer.sftGradient(sequence, 2).loss;
    expect(after < before * 0.2, "corrected-response SFT loss must decrease");
    const auto trained = trainer.adapter();
    sibalsa::InferenceSession adapted(model, std::make_shared<sibalsa::LoRAAdapter>(trained));
    const auto response = adapted.greedyGenerate({1, 2}, 1);
    expect(response.front() == 3, "LoRA must adapt generated vocabulary token");
    const auto analytic = trainer.sftGradient(sequence, 2);
    const auto base = trainer.adapter().parameters;
    for (std::size_t i = 0; i < base.size(); ++i) {
      auto plus = base, minus = base;
      plus[i] += 0.001f; minus[i] -= 0.001f;
      trainer.setParameters(plus, false); const auto high = trainer.sftGradient(sequence, 2).loss;
      trainer.setParameters(minus, false); const auto low = trainer.sftGradient(sequence, 2).loss;
      const auto numerical = (high - low) / 0.002;
      near(analytic.gradient[i], numerical, 0.0003 + std::abs(numerical) * 0.04, "SFT finite-difference A/B gradient");
    }
    trainer.setParameters(base);
    const auto delta = trainer.deltaFrom(initial);
    for (std::size_t i = 0; i < base.size(); ++i) expect(delta[i] == base[i] - initial[i], "exact flattened federation delta");
    trainer.saveAdapter(temp.file("adapter.siblora"));
    const auto restored = sibalsa::LoRAAdapter::load(temp.file("adapter.siblora"), *model);
    expect(restored.parameters == base && restored.modelSha256 == model->sha256(), "adapter round trip exact");
    sibalsa::TrainingRuntime evaluator(model, restored);
    const auto scored = evaluator.sftGradient(sequence, 2);
    near(scored.loss, after, 1e-9, "reloaded adapter evaluation loss");
    expect(scored.tokens == sequence.size() - 2 && evaluator.adapter().parameters == base,
           "read-only scoring masks prompt and leaves adapter unchanged");
    throws([&] { sibalsa::LoRAAdapter::load(temp.file("adapter.siblora"), *quantized); }, "adapter model mismatch");
    auto invalidParams = base; invalidParams[0] = std::numeric_limits<float>::quiet_NaN();
    throws([&] { trainer.setParameters(invalidParams); }, "non-finite adapter");
    expect(trainer.adapter().parameters == base, "invalid parameter set is transactional");
    throws([&] { trainer.trainSft(sequence, 2, std::numeric_limits<float>::infinity()); }, "non-finite LR");
    throws([&] { trainer.sftGradient(sequence, 0); }, "empty prompt mask");
    throws([&] { trainer.sftGradient(sequence, 4); }, "empty completion");
    throws([&] { trainer.sftGradient({1, 2, 99}, 2); }, "training bad token");
    trainer.cancel(); throws([&] { trainer.trainSft(sequence, 2, 0.1f); }, "cancelled training");
    expect(trainer.adapter().parameters == base, "cancelled training leaves weights unchanged");
    trainer.clearCancellation();

    sibalsa::TrainingRuntime dpo(model, adapter);
    const auto dpoBefore = dpo.dpoGradient({1, 2}, {3}, {4}, 0.5f).loss;
    for (int i = 0; i < 120; ++i) dpo.trainDpo({1, 2}, {3}, {4}, 0.5f, 0.1f, 2.0f);
    expect(dpo.dpoGradient({1, 2}, {3}, {4}, 0.5f).loss < dpoBefore * 0.3, "paired DPO loss drop");
    const auto dpoParams = dpo.adapter().parameters;
    const auto dpoAnalytic = dpo.dpoGradient({1, 2}, {3}, {4}, 0.5f);
    for (std::size_t i = 0; i < dpoParams.size(); ++i) {
      auto plus = dpoParams, minus = dpoParams; plus[i] += 0.001f; minus[i] -= 0.001f;
      dpo.setParameters(plus, false); const auto high = dpo.dpoGradient({1, 2}, {3}, {4}, 0.5f).loss;
      dpo.setParameters(minus, false); const auto low = dpo.dpoGradient({1, 2}, {3}, {4}, 0.5f).loss;
      const double numerical = (high - low) / 0.002;
      near(dpoAnalytic.gradient[i], numerical, 0.0003 + std::abs(numerical) * 0.04, "DPO finite-difference A/B gradient");
    }

    const auto original = read(floatPath);
    auto corrupt = original; corrupt[0] = 'X'; write(badPath, corrupt);
    throws([&] { sibalsa::Model::load(badPath); }, "malformed magic");
    corrupt = original; corrupt.resize(70); write(badPath, corrupt);
    throws([&] { sibalsa::Model::load(badPath); }, "truncated model");
    corrupt = original; put32(corrupt, 16, 0xffffffffU); write(badPath, corrupt);
    throws([&] { sibalsa::Model::load(badPath); }, "overflow dimensions");
    corrupt = original; put32(corrupt, 64+56, 7); write(badPath, corrupt);
    throws([&] { sibalsa::Model::load(badPath); }, "unknown dtype");
    corrupt = original; const auto first = get64(corrupt, 64+64); put32(corrupt, first, 0x7fc00000); write(badPath, corrupt);
    throws([&] { sibalsa::Model::load(badPath); }, "NaN model weight");
    corrupt = original;
    for (int i = 0; i < 8; ++i) corrupt[64 + 80 + 64 + i] = corrupt[64 + 64 + i];
    write(badPath, corrupt); throws([&] { sibalsa::Model::load(badPath); }, "overlapping tensors");
    auto badAdapter = read(temp.file("adapter.siblora")); badAdapter.pop_back(); write(badPath, badAdapter);
    throws([&] { sibalsa::LoRAAdapter::load(badPath, *model); }, "truncated adapter");

    sibalsa::test::writeFixture(temp.file("large.sib"), false, {128, 192, 4, 2, 2, 64, 256, 10000, 1e-5f});
    const auto largeModel = sibalsa::Model::load(temp.file("large.sib"));
    sibalsa::TrainingRuntime slow(largeModel, sibalsa::LoRAAdapter::initialize(*largeModel, 2, 42), 256);
    const auto untouched = slow.adapter().parameters;
    std::vector<std::int32_t> longSequence(200, 1);
    bool interrupted = false;
    std::thread worker([&] { try { slow.trainSft(longSequence, 5, 0.1f); } catch (const std::runtime_error&) { interrupted = true; } });
    std::this_thread::sleep_for(std::chrono::milliseconds(2)); slow.cancel(); worker.join();
    expect(interrupted && slow.adapter().parameters == untouched, "mid-forward cancellation does not commit minibatch");
    std::cout << "PASS native inference/training: GQA/RoPE causal cache, int8, SFT/DPO finite differences, loss drops, cancellation, malformed files, exact delta/serialization\n";
    return 0;
  } catch (const std::exception& error) { std::cerr << "FAIL " << error.what() << '\n'; return 1; }
}
