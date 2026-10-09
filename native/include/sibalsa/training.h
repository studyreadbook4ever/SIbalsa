// SPDX-License-Identifier: Unlicense
#pragma once

#include "sibalsa/runtime.h"
#include <mutex>

namespace sibalsa {

struct LossGradient {
  double loss = 0.0;
  std::size_t tokens = 0;
  std::vector<float> gradient;
};

class TrainingRuntime {
 public:
  TrainingRuntime(std::shared_ptr<const Model> model, LoRAAdapter adapter,
                  std::uint32_t maxContext = 0);
  const ModelConfig& config() const noexcept { return model_->config(); }
  const std::string& modelSha256() const noexcept { return model_->sha256(); }
  std::uint32_t capacity() const noexcept { return maxContext_; }
  const LoRAAdapter& adapter() const noexcept { return adapter_; }
  void setParameters(const std::vector<float>& parameters, bool resetDpoReference = true);
  void loadAdapter(const std::string& path);
  void saveAdapter(const std::string& path) const;
  std::vector<float> deltaFrom(const std::vector<float>& reference) const;
  LossGradient sftGradient(const std::vector<std::int32_t>& sequence,
                           std::uint32_t promptLength);
  double trainSft(const std::vector<std::int32_t>& sequence,
                  std::uint32_t promptLength, float learningRate, float clipNorm = 1.0f);
  LossGradient dpoGradient(const std::vector<std::int32_t>& prompt,
                           const std::vector<std::int32_t>& chosen,
                           const std::vector<std::int32_t>& rejected,
                           float beta = 0.1f);
  double trainDpo(const std::vector<std::int32_t>& prompt,
                  const std::vector<std::int32_t>& chosen,
                  const std::vector<std::int32_t>& rejected,
                  float beta, float learningRate, float clipNorm = 1.0f);
  // DPO reference is the exact global adapter at runtime creation/load, not a moving target.
  void resetReference();
  void cancel() noexcept;
  void clearCancellation() noexcept;
 private:
  LossGradient completionLogProbability(const std::vector<std::int32_t>& prompt,
                                        const std::vector<std::int32_t>& response,
                                        const LoRAAdapter& adapter, bool needGradient);
  void apply(const LossGradient& result, float learningRate, float clipNorm);
  std::shared_ptr<const Model> model_;
  LoRAAdapter adapter_, reference_;
  std::uint32_t maxContext_ = 0;
  std::atomic<bool> cancelled_{false};
  std::mutex commitMutex_;
};

}  // namespace sibalsa
