// SPDX-License-Identifier: Unlicense
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace sibalsa {

struct ModelConfig {
  std::uint32_t dim = 0;
  std::uint32_t hiddenDim = 0;
  std::uint32_t heads = 0;
  std::uint32_t kvHeads = 0;
  std::uint32_t layers = 0;
  std::uint32_t vocab = 0;
  std::uint32_t maxContext = 0;
  float ropeTheta = 10000.0f;
  float rmsEpsilon = 1e-5f;
};

class Tensor {
 public:
  std::uint32_t rows() const noexcept { return rows_; }
  std::uint32_t cols() const noexcept { return cols_; }
  float at(std::size_t row, std::size_t col) const;
  std::vector<float> row(std::size_t index) const;
  std::vector<float> multiply(const std::vector<float>& input,
                              const std::atomic<bool>* cancelled = nullptr) const;

 private:
  friend class Model;
  std::uint32_t rows_ = 0, cols_ = 0, dtype_ = 0;
  const unsigned char* bytes_ = nullptr;
};

class Model {
 public:
  static std::shared_ptr<const Model> load(const std::string& path);
  ~Model();
  const ModelConfig& config() const noexcept { return config_; }
  const std::string& sha256() const noexcept { return sha256_; }
  const Tensor& tensor(const std::string& name) const;
  // Independent full-sequence causal forward pass, useful for cache validation.
  std::vector<std::vector<float>> forwardHidden(const std::vector<std::int32_t>& tokens,
                                               const std::atomic<bool>* cancelled = nullptr) const;
 private:
  Model() = default;
  struct Mapping;
  std::unique_ptr<Mapping> mapping_;
  ModelConfig config_;
  std::string sha256_;
  std::vector<std::pair<std::string, Tensor>> tensors_;
};

struct LoRAAdapter {
  std::uint32_t dim = 0, vocab = 0, rank = 0;
  float scale = 1.0f;
  std::string modelSha256;
  // Flattened A[rank,dim], followed by B[vocab,rank].
  std::vector<float> parameters;
  static LoRAAdapter initialize(const Model& model, std::uint32_t rank,
                                std::uint64_t seed, float scale = 1.0f);
  static LoRAAdapter load(const std::string& path, const Model& model);
  void save(const std::string& path) const;
  void validate(const Model& model) const;
  std::vector<float> logits(const Model& model, const std::vector<float>& hidden,
                            const std::atomic<bool>* cancelled = nullptr) const;
};

class InferenceSession {
 public:
  InferenceSession(std::shared_ptr<const Model> model,
                   std::shared_ptr<const LoRAAdapter> adapter = {},
                   std::uint32_t maxContext = 0);
  // Returns logits after consuming this token. Throws if cancelled or at context limit.
  std::vector<float> step(std::int32_t token);
  const std::vector<float>& lastHidden() const noexcept { return lastHidden_; }
  std::uint32_t size() const noexcept { return position_; }
  std::uint32_t capacity() const noexcept { return maxContext_; }
  void reset();
  void cancel() noexcept { cancelled_.store(true, std::memory_order_relaxed); }
  std::vector<std::int32_t> greedyGenerate(const std::vector<std::int32_t>& prompt,
                                         std::uint32_t maxNewTokens,
                                         std::int32_t eos = -1);
 private:
  std::shared_ptr<const Model> model_;
  std::shared_ptr<const LoRAAdapter> adapter_;
  std::uint32_t maxContext_ = 0, position_ = 0;
  std::vector<float> keys_, values_, lastHidden_;
  std::atomic<bool> cancelled_{false};
};

std::string sha256(const unsigned char* bytes, std::size_t size);

}  // namespace sibalsa
