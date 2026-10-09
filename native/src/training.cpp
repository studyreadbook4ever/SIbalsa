// SPDX-License-Identifier: Unlicense
#include "sibalsa/training.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace sibalsa {
namespace {
void require(bool good, const char* reason) { if (!good) throw std::invalid_argument(reason); }
void cancelled(const std::atomic<bool>& flag) {
  if (flag.load(std::memory_order_relaxed)) throw std::runtime_error("training cancelled");
}
// Returns log p(target). Accumulates derivative of log p with respect to flattened A/B.
double tokenLogProbability(const Model& model, const LoRAAdapter& adapter,
                           const std::vector<float>& hidden, std::int32_t target,
                           std::vector<float>* gradient, const std::atomic<bool>& cancel) {
  cancelled(cancel);
  require(target >= 0 && std::uint32_t(target) < adapter.vocab, "target token ID out of bounds");
  const auto logits = adapter.logits(model, hidden, &cancel);
  const double maximum = *std::max_element(logits.begin(), logits.end());
  std::vector<double> probabilities(logits.size());
  double sum = 0.0;
  for (std::size_t i = 0; i < logits.size(); ++i) {
    probabilities[i] = std::exp(double(logits[i]) - maximum); sum += probabilities[i];
  }
  const double logp = double(logits[static_cast<std::size_t>(target)]) - maximum - std::log(sum);
  require(std::isfinite(logp), "non-finite token log probability");
  if (!gradient) return logp;
  require(gradient->size() == adapter.parameters.size(), "gradient shape mismatch");
  std::vector<double> reduced(adapter.rank, 0.0), reducedGradient(adapter.rank, 0.0);
  for (std::uint32_t r = 0; r < adapter.rank; ++r)
    for (std::uint32_t d = 0; d < adapter.dim; ++d)
      reduced[r] += double(adapter.parameters[std::size_t(r) * adapter.dim + d]) * hidden[d];
  const auto bOffset = std::size_t(adapter.rank) * adapter.dim;
  for (std::uint32_t v = 0; v < adapter.vocab; ++v) {
    if ((v & 127) == 0) cancelled(cancel);
    const double derivative = (v == std::uint32_t(target) ? 1.0 : 0.0) - probabilities[v] / sum;
    for (std::uint32_t r = 0; r < adapter.rank; ++r) {
      const auto index = bOffset + std::size_t(v) * adapter.rank + r;
      (*gradient)[index] += static_cast<float>(adapter.scale * derivative * reduced[r]);
      reducedGradient[r] += adapter.scale * derivative * adapter.parameters[index];
    }
  }
  for (std::uint32_t r = 0; r < adapter.rank; ++r)
    for (std::uint32_t d = 0; d < adapter.dim; ++d)
      (*gradient)[std::size_t(r) * adapter.dim + d] += static_cast<float>(reducedGradient[r] * hidden[d]);
  return logp;
}
void validateResult(const LossGradient& result) {
  require(std::isfinite(result.loss) && result.tokens > 0, "invalid loss result");
  for (float value : result.gradient) require(std::isfinite(value), "non-finite gradient");
}
}  // namespace

TrainingRuntime::TrainingRuntime(std::shared_ptr<const Model> model, LoRAAdapter adapter,
                                 std::uint32_t maxContext)
    : model_(std::move(model)), adapter_(std::move(adapter)), reference_(adapter_) {
  require(bool(model_), "model is required for training");
  adapter_.validate(*model_);
  maxContext_ = maxContext ? maxContext : std::min(model_->config().maxContext, std::uint32_t(512));
  require(maxContext_ > 1 && maxContext_ <= model_->config().maxContext, "invalid training context capacity");
}
void TrainingRuntime::setParameters(const std::vector<float>& parameters, bool resetDpoReference) {
  LoRAAdapter candidate = adapter_;
  candidate.parameters = parameters;
  candidate.validate(*model_);
  adapter_ = std::move(candidate);
  if (resetDpoReference) resetReference();
}
void TrainingRuntime::loadAdapter(const std::string& path) {
  auto candidate = LoRAAdapter::load(path, *model_);
  require(candidate.rank == adapter_.rank && candidate.scale == adapter_.scale, "loaded adapter rank/scale differs");
  adapter_ = std::move(candidate);
  resetReference();
}
void TrainingRuntime::saveAdapter(const std::string& path) const { adapter_.save(path); }
void TrainingRuntime::resetReference() { reference_ = adapter_; }
void TrainingRuntime::cancel() noexcept {
  std::lock_guard<std::mutex> lock(commitMutex_);
  cancelled_.store(true, std::memory_order_relaxed);
}
void TrainingRuntime::clearCancellation() noexcept {
  std::lock_guard<std::mutex> lock(commitMutex_);
  cancelled_.store(false, std::memory_order_relaxed);
}
std::vector<float> TrainingRuntime::deltaFrom(const std::vector<float>& reference) const {
  require(reference.size() == adapter_.parameters.size(), "delta reference shape mismatch");
  std::vector<float> delta(reference.size());
  for (std::size_t i = 0; i < reference.size(); ++i) {
    require(std::isfinite(reference[i]), "non-finite delta reference");
    delta[i] = adapter_.parameters[i] - reference[i];
    require(std::isfinite(delta[i]), "non-finite adapter delta");
  }
  return delta;
}

LossGradient TrainingRuntime::sftGradient(const std::vector<std::int32_t>& sequence,
                                         std::uint32_t promptLength) {
  cancelled(cancelled_);
  require(sequence.size() >= 2 && sequence.size() <= maxContext_ &&
          promptLength >= 1 && promptLength < sequence.size(), "invalid SFT sequence/prompt length");
  for (auto token : sequence)
    require(token >= 0 && std::uint32_t(token) < model_->config().vocab, "training token ID out of bounds");
  // Token t is predicted from hidden state t-1. Prompt tokens never receive a label.
  const std::vector<std::int32_t> inputs(sequence.begin(), sequence.end() - 1);
  const auto states = model_->forwardHidden(inputs, &cancelled_);
  LossGradient result;
  result.tokens = sequence.size() - promptLength;
  result.gradient.assign(adapter_.parameters.size(), 0.0f);
  for (std::size_t t = promptLength; t < sequence.size(); ++t)
    result.loss -= tokenLogProbability(*model_, adapter_, states[t - 1], sequence[t], &result.gradient, cancelled_);
  result.loss /= result.tokens;
  for (float& v : result.gradient) v = static_cast<float>(-double(v) / result.tokens);
  cancelled(cancelled_);
  validateResult(result);
  return result;
}
LossGradient TrainingRuntime::completionLogProbability(const std::vector<std::int32_t>& prompt,
                                                       const std::vector<std::int32_t>& response,
                                                       const LoRAAdapter& adapter, bool needGradient) {
  require(!prompt.empty() && !response.empty() && prompt.size() < maxContext_ &&
          response.size() <= maxContext_ - prompt.size(), "invalid DPO prompt/completion length");
  std::vector<std::int32_t> sequence = prompt;
  sequence.insert(sequence.end(), response.begin(), response.end());
  for (auto token : sequence)
    require(token >= 0 && std::uint32_t(token) < model_->config().vocab, "training token ID out of bounds");
  sequence.pop_back();
  const auto states = model_->forwardHidden(sequence, &cancelled_);
  LossGradient result;
  result.tokens = response.size();
  if (needGradient) result.gradient.assign(adapter_.parameters.size(), 0.0f);
  for (std::size_t t = 0; t < response.size(); ++t)
    result.loss += tokenLogProbability(*model_, adapter, states[prompt.size() + t - 1], response[t],
                                       needGradient ? &result.gradient : nullptr, cancelled_);
  validateResult(result);
  return result;
}
LossGradient TrainingRuntime::dpoGradient(const std::vector<std::int32_t>& prompt,
                                         const std::vector<std::int32_t>& chosen,
                                         const std::vector<std::int32_t>& rejected, float beta) {
  require(std::isfinite(beta) && beta > 0 && beta <= 10, "invalid DPO beta");
  cancelled(cancelled_);
  const auto good = completionLogProbability(prompt, chosen, adapter_, true);
  const auto bad = completionLogProbability(prompt, rejected, adapter_, true);
  const auto referenceGood = completionLogProbability(prompt, chosen, reference_, false);
  const auto referenceBad = completionLogProbability(prompt, rejected, reference_, false);
  const double margin = beta * ((good.loss - bad.loss) - (referenceGood.loss - referenceBad.loss));
  const double sigmoid = margin >= 0 ? 1.0 / (1.0 + std::exp(-margin)) : std::exp(margin) / (1.0 + std::exp(margin));
  const double factor = -beta * (1.0 - sigmoid);
  LossGradient result;
  result.loss = margin >= 0 ? std::log1p(std::exp(-margin)) : -margin + std::log1p(std::exp(margin));
  result.tokens = good.tokens + bad.tokens;
  result.gradient.resize(adapter_.parameters.size());
  for (std::size_t i = 0; i < result.gradient.size(); ++i)
    result.gradient[i] = static_cast<float>(factor * (double(good.gradient[i]) - bad.gradient[i]));
  cancelled(cancelled_);
  validateResult(result);
  return result;
}
void TrainingRuntime::apply(const LossGradient& result, float learningRate, float clipNorm) {
  require(std::isfinite(learningRate) && learningRate > 0 && learningRate <= 1, "learning rate must be in (0,1]");
  require(std::isfinite(clipNorm) && clipNorm > 0 && clipNorm <= 1e6f, "invalid gradient clipping norm");
  validateResult(result);
  require(result.gradient.size() == adapter_.parameters.size(), "gradient shape mismatch");
  double normSquared = 0.0;
  for (float v : result.gradient) normSquared += double(v) * v;
  const double norm = std::sqrt(normSquared);
  const double factor = norm > clipNorm ? clipNorm / norm : 1.0;
  std::vector<float> candidate(adapter_.parameters.size());
  for (std::size_t i = 0; i < candidate.size(); ++i) {
    if ((i & 1023) == 0) cancelled(cancelled_);
    candidate[i] = static_cast<float>(adapter_.parameters[i] - learningRate * factor * result.gradient[i]);
    require(std::isfinite(candidate[i]), "non-finite optimizer result");
  }
  std::lock_guard<std::mutex> commit(commitMutex_);
  cancelled(cancelled_);
  // Commit only after a complete, finite, uncancelled optimization step.
  adapter_.parameters.swap(candidate);
}
double TrainingRuntime::trainSft(const std::vector<std::int32_t>& sequence,
                                std::uint32_t promptLength, float learningRate, float clipNorm) {
  const auto result = sftGradient(sequence, promptLength);
  apply(result, learningRate, clipNorm);
  return result.loss;
}
double TrainingRuntime::trainDpo(const std::vector<std::int32_t>& prompt,
                                const std::vector<std::int32_t>& chosen,
                                const std::vector<std::int32_t>& rejected, float beta,
                                float learningRate, float clipNorm) {
  const auto result = dpoGradient(prompt, chosen, rejected, beta);
  apply(result, learningRate, clipNorm);
  return result.loss;
}
}  // namespace sibalsa
