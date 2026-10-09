// SPDX-License-Identifier: Unlicense
#include "sibalsa/runtime.h"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <limits>
#include <set>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace sibalsa {
namespace {
constexpr std::size_t kHeaderBytes = 64, kDescriptorBytes = 80;
constexpr std::uint64_t kMaxFileBytes = std::uint64_t(32) << 30;
constexpr std::size_t kMaxWorkingBytes = std::size_t(512) << 20;

void checkCancelled(const std::atomic<bool>* flag) {
  if (flag && flag->load(std::memory_order_relaxed)) throw std::runtime_error("operation cancelled");
}
void require(bool good, const std::string& reason) {
  if (!good) throw std::invalid_argument(reason);
}
std::uint32_t u32(const unsigned char* p) {
  return std::uint32_t(p[0]) | (std::uint32_t(p[1]) << 8) |
         (std::uint32_t(p[2]) << 16) | (std::uint32_t(p[3]) << 24);
}
std::uint64_t u64(const unsigned char* p) {
  return std::uint64_t(u32(p)) | (std::uint64_t(u32(p + 4)) << 32);
}
float f32(const unsigned char* p) {
  const auto bits = u32(p);
  float result;
  std::memcpy(&result, &bits, 4);
  return result;
}
void put32(std::vector<unsigned char>& out, std::uint32_t v) {
  for (int i = 0; i < 4; ++i) out.push_back(static_cast<unsigned char>(v >> (i * 8)));
}
void putFloat(std::vector<unsigned char>& out, float v) {
  std::uint32_t bits;
  std::memcpy(&bits, &v, 4);
  put32(out, bits);
}
std::size_t checkedProduct(std::size_t a, std::size_t b, const char* what) {
  if (a && b > std::numeric_limits<std::size_t>::max() / a)
    throw std::invalid_argument(std::string(what) + " size overflow");
  return a * b;
}
void finiteVector(const std::vector<float>& values, const char* name) {
  for (float v : values) require(std::isfinite(v), std::string("non-finite ") + name);
}
std::vector<float> rmsNorm(const std::vector<float>& x, const Tensor& weight, float epsilon) {
  double sum = 0.0;
  for (float v : x) sum += double(v) * v;
  const double factor = 1.0 / std::sqrt(sum / x.size() + epsilon);
  std::vector<float> out(x.size());
  for (std::size_t i = 0; i < x.size(); ++i)
    out[i] = static_cast<float>(x[i] * factor * weight.at(0, i));
  finiteVector(out, "normalized hidden state");
  return out;
}
void add(std::vector<float>& x, const std::vector<float>& y) {
  require(x.size() == y.size(), "residual shape mismatch");
  for (std::size_t i = 0; i < x.size(); ++i) x[i] += y[i];
  finiteVector(x, "residual hidden state");
}
void rope(std::vector<float>& x, std::uint32_t heads, std::uint32_t headDim,
          std::uint32_t position, float theta) {
  for (std::uint32_t h = 0; h < heads; ++h) {
    for (std::uint32_t i = 0; i < headDim / 2; ++i) {
      const double angle = position * std::pow(double(theta), -2.0 * i / headDim);
      const float c = static_cast<float>(std::cos(angle)), s = static_cast<float>(std::sin(angle));
      const auto first = h * headDim + i, second = first + headDim / 2;
      const float a = x[first], b = x[second];
      x[first] = a * c - b * s;
      x[second] = b * c + a * s;
    }
  }
}
std::vector<float> attention(const std::vector<float>& q, const float* keys, const float* values,
                             std::uint32_t count, const ModelConfig& c,
                             const std::atomic<bool>* cancelled) {
  const auto headDim = c.dim / c.heads, kvDim = c.kvHeads * headDim;
  std::vector<float> out(c.dim, 0.0f);
  std::vector<double> scores(count);
  const double factor = 1.0 / std::sqrt(double(headDim));
  for (std::uint32_t h = 0; h < c.heads; ++h) {
    checkCancelled(cancelled);
    const auto kvHead = h / (c.heads / c.kvHeads);
    double maximum = -std::numeric_limits<double>::infinity();
    for (std::uint32_t t = 0; t < count; ++t) {
      double score = 0.0;
      for (std::uint32_t i = 0; i < headDim; ++i)
        score += double(q[h * headDim + i]) * keys[std::size_t(t) * kvDim + kvHead * headDim + i];
      scores[t] = score * factor;
      require(std::isfinite(scores[t]), "non-finite attention score");
      maximum = std::max(maximum, scores[t]);
    }
    double sum = 0.0;
    for (double& score : scores) { score = std::exp(score - maximum); sum += score; }
    for (std::uint32_t i = 0; i < headDim; ++i) {
      double value = 0.0;
      for (std::uint32_t t = 0; t < count; ++t)
        value += scores[t] / sum * values[std::size_t(t) * kvDim + kvHead * headDim + i];
      out[h * headDim + i] = static_cast<float>(value);
    }
  }
  finiteVector(out, "attention output");
  return out;
}
std::vector<float> feedForward(const Model& model, const std::string& prefix,
                               const std::vector<float>& hidden,
                               const std::atomic<bool>* cancelled) {
  auto gate = model.tensor(prefix + "w1").multiply(hidden, cancelled);
  const auto up = model.tensor(prefix + "w3").multiply(hidden, cancelled);
  for (std::size_t i = 0; i < gate.size(); ++i) {
    const double x = gate[i];
    // Numerically stable SiLU; extreme negative x approaches zero.
    const double sigmoid = x >= 0 ? 1.0 / (1.0 + std::exp(-x)) : std::exp(x) / (1.0 + std::exp(x));
    gate[i] = static_cast<float>(x * sigmoid * up[i]);
  }
  finiteVector(gate, "SwiGLU output");
  return model.tensor(prefix + "w2").multiply(gate, cancelled);
}
std::size_t parameterCount(std::uint32_t dim, std::uint32_t vocab, std::uint32_t rank) {
  require(dim > 0 && vocab > 0 && rank > 0 && rank <= 256, "invalid adapter dimensions/rank");
  const auto count = checkedProduct(std::size_t(dim) + vocab, rank, "adapter");
  require(count <= kMaxWorkingBytes / sizeof(float), "adapter exceeds memory limit");
  return count;
}
void atomicWrite(const std::string& path, const std::vector<unsigned char>& bytes) {
  require(!path.empty(), "empty output path");
  std::string temporary = path + ".tmp.XXXXXX";
  std::vector<char> filename(temporary.begin(), temporary.end());
  filename.push_back('\0');
  const int fd = mkstemp(filename.data());
  if (fd < 0) throw std::runtime_error("cannot create adapter temporary file");
  bool closed = false;
  try {
    std::size_t offset = 0;
    while (offset < bytes.size()) {
      const auto wrote = write(fd, bytes.data() + offset, bytes.size() - offset);
      if (wrote < 0 && errno == EINTR) continue;
      if (wrote <= 0) throw std::runtime_error("cannot write adapter file");
      offset += static_cast<std::size_t>(wrote);
    }
    if (fsync(fd) != 0) throw std::runtime_error("cannot flush adapter file");
    const int closeResult = close(fd); closed = true;
    if (closeResult != 0) throw std::runtime_error("cannot close adapter file");
    if (rename(filename.data(), path.c_str()) != 0) throw std::runtime_error("cannot replace adapter file");
  } catch (...) {
    if (!closed) close(fd);
    unlink(filename.data());
    throw;
  }
}
}  // namespace

struct Model::Mapping {
  void* address = MAP_FAILED;
  std::size_t size = 0;
  ~Mapping() { if (address != MAP_FAILED) munmap(address, size); }
};

Model::~Model() = default;

float Tensor::at(std::size_t row, std::size_t col) const {
  require(row < rows_ && col < cols_, "tensor index out of bounds");
  if (dtype_ == 0) return f32(bytes_ + (row * cols_ + col) * 4);
  const auto quant = static_cast<std::int8_t>(bytes_[std::size_t(rows_) * 4 + row * cols_ + col]);
  return quant * f32(bytes_ + row * 4);
}
std::vector<float> Tensor::row(std::size_t index) const {
  require(index < rows_, "embedding token out of bounds");
  std::vector<float> out(cols_);
  for (std::size_t i = 0; i < cols_; ++i) out[i] = at(index, i);
  return out;
}
std::vector<float> Tensor::multiply(const std::vector<float>& input,
                                     const std::atomic<bool>* cancelled) const {
  require(input.size() == cols_, "matrix input shape mismatch");
  finiteVector(input, "matrix input");
  std::vector<float> out(rows_);
  for (std::size_t r = 0; r < rows_; ++r) {
    if ((r & 15) == 0) checkCancelled(cancelled);
    double sum = 0.0;
    if (dtype_ == 0) {
      const auto* p = bytes_ + r * cols_ * 4;
      for (std::size_t j = 0; j < cols_; ++j) {
        if ((j & 1023) == 0) checkCancelled(cancelled);
        sum += double(f32(p + j * 4)) * input[j];
      }
    } else {
      const auto* p = bytes_ + std::size_t(rows_) * 4 + r * cols_;
      for (std::size_t j = 0; j < cols_; ++j) {
        if ((j & 1023) == 0) checkCancelled(cancelled);
        sum += double(static_cast<std::int8_t>(p[j])) * input[j];
      }
      sum *= f32(bytes_ + r * 4);
    }
    out[r] = static_cast<float>(sum);
  }
  finiteVector(out, "matrix output");
  return out;
}

std::shared_ptr<const Model> Model::load(const std::string& path) {
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) throw std::runtime_error("cannot open model file");
  struct stat st {};
  if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size < std::int64_t(kHeaderBytes) ||
      std::uint64_t(st.st_size) > kMaxFileBytes || std::uint64_t(st.st_size) > std::numeric_limits<std::size_t>::max()) {
    close(fd);
    throw std::invalid_argument("model must be a regular file with valid bounded size");
  }
  auto model = std::shared_ptr<Model>(new Model);
  model->mapping_ = std::make_unique<Mapping>();
  model->mapping_->size = static_cast<std::size_t>(st.st_size);
  model->mapping_->address = mmap(nullptr, model->mapping_->size, PROT_READ, MAP_PRIVATE, fd, 0);
  close(fd);
  if (model->mapping_->address == MAP_FAILED) throw std::runtime_error("cannot mmap model file");
  const auto* data = static_cast<const unsigned char*>(model->mapping_->address);
  const auto size = model->mapping_->size;
  require(std::memcmp(data, "SIBLLM01", 8) == 0, "unsupported model magic");
  require(u32(data + 8) == 1, "unsupported model version");
  const auto headerSize = u32(data + 12);
  auto& c = model->config_;
  c.dim = u32(data + 16); c.hiddenDim = u32(data + 20);
  c.heads = u32(data + 24); c.kvHeads = u32(data + 28); c.layers = u32(data + 32);
  c.vocab = u32(data + 36); c.maxContext = u32(data + 40);
  const auto tensorCount = u32(data + 44);
  c.ropeTheta = f32(data + 48); c.rmsEpsilon = f32(data + 52);
  require(u64(data + 56) == 0, "unsupported model flags");
  require(c.dim > 0 && c.dim <= 16384 && c.hiddenDim > 0 && c.hiddenDim <= 65536 &&
          c.heads > 0 && c.kvHeads > 0 && c.heads <= c.dim && c.dim % c.heads == 0 &&
          (c.dim / c.heads) % 2 == 0 && c.heads % c.kvHeads == 0 &&
          c.layers > 0 && c.layers <= 128 && c.vocab > 1 && c.vocab <= 262144 &&
          c.maxContext > 1 && c.maxContext <= 131072, "invalid/unsupported model configuration");
  require(std::isfinite(c.ropeTheta) && c.ropeTheta >= 1 && c.ropeTheta <= 1e9f &&
          std::isfinite(c.rmsEpsilon) && c.rmsEpsilon >= 1e-12f && c.rmsEpsilon <= 1,
          "invalid RoPE/RMSNorm configuration");
  require(tensorCount == 3 + 9 * c.layers, "unexpected model tensor count");
  const auto tableBytes = kHeaderBytes + checkedProduct(tensorCount, kDescriptorBytes, "tensor table");
  require(headerSize >= tableBytes && headerSize <= size, "truncated/overlapping tensor table");
  std::set<std::string> names;
  std::vector<std::pair<std::uint64_t, std::uint64_t>> ranges;
  for (std::uint32_t i = 0; i < tensorCount; ++i) {
    const auto* p = data + kHeaderBytes + i * kDescriptorBytes;
    const auto* end = static_cast<const unsigned char*>(std::memchr(p, 0, 48));
    require(end && end != p, "invalid tensor name");
    const std::string name(reinterpret_cast<const char*>(p), static_cast<std::size_t>(end - p));
    require(names.insert(name).second, "duplicate tensor name");
    Tensor tensor;
    tensor.rows_ = u32(p + 48); tensor.cols_ = u32(p + 52); tensor.dtype_ = u32(p + 56);
    require(u32(p + 60) == 0, "unsupported tensor flags");
    const auto offset = u64(p + 64), bytes = u64(p + 72);
    require(tensor.rows_ > 0 && tensor.cols_ > 0 && tensor.dtype_ <= 1, "invalid tensor descriptor");
    const auto elements = checkedProduct(tensor.rows_, tensor.cols_, "tensor");
    const auto expected = tensor.dtype_ == 0 ? checkedProduct(elements, 4, "float tensor") :
                         elements + checkedProduct(tensor.rows_, 4, "int8 scales");
    require(bytes == expected && offset >= headerSize && offset % 4 == 0 &&
            offset <= size && bytes <= size - offset, "invalid/truncated tensor payload");
    tensor.bytes_ = data + offset;
    ranges.emplace_back(offset, offset + bytes);
    if (tensor.dtype_ == 0) {
      for (std::size_t j = 0; j < elements; ++j)
        require(std::isfinite(f32(tensor.bytes_ + j * 4)), "non-finite model weight");
    } else {
      for (std::size_t j = 0; j < tensor.rows_; ++j) {
        const float scale = f32(tensor.bytes_ + j * 4);
        require(std::isfinite(scale) && scale > 0 && std::isfinite(scale * 128.0f), "invalid int8 row scale");
      }
    }
    model->tensors_.emplace_back(name, tensor);
  }
  std::sort(ranges.begin(), ranges.end());
  for (std::size_t i = 1; i < ranges.size(); ++i)
    require(ranges[i - 1].second <= ranges[i].first, "overlapping tensor payloads");
  const auto verify = [&](const std::string& name, std::uint32_t rows, std::uint32_t cols, bool norm = false) {
    const auto& t = model->tensor(name);
    require(t.rows_ == rows && t.cols_ == cols, "incorrect tensor shape: " + name);
    require(!norm || t.dtype_ == 0, "normalization weights must be float32");
  };
  verify("tok_embeddings", c.vocab, c.dim); verify("output", c.vocab, c.dim);
  verify("final_norm", 1, c.dim, true);
  const auto kvDim = c.kvHeads * (c.dim / c.heads);
  for (std::uint32_t layer = 0; layer < c.layers; ++layer) {
    const auto p = "layers." + std::to_string(layer) + ".";
    verify(p + "attn_norm", 1, c.dim, true); verify(p + "ffn_norm", 1, c.dim, true);
    verify(p + "wq", c.dim, c.dim); verify(p + "wk", kvDim, c.dim); verify(p + "wv", kvDim, c.dim);
    verify(p + "wo", c.dim, c.dim);
    verify(p + "w1", c.hiddenDim, c.dim); verify(p + "w2", c.dim, c.hiddenDim); verify(p + "w3", c.hiddenDim, c.dim);
  }
  model->sha256_ = sibalsa::sha256(data, size);
  return model;
}

const Tensor& Model::tensor(const std::string& name) const {
  for (const auto& item : tensors_) if (item.first == name) return item.second;
  throw std::invalid_argument("missing tensor: " + name);
}

std::vector<std::vector<float>> Model::forwardHidden(const std::vector<std::int32_t>& tokens,
                                                    const std::atomic<bool>* cancelled) const {
  const auto& c = config_;
  require(!tokens.empty() && tokens.size() <= c.maxContext, "invalid fullforward context length");
  const auto working = checkedProduct(checkedProduct(tokens.size(), c.dim, "fullforward"), 7 * sizeof(float), "fullforward");
  require(working <= kMaxWorkingBytes, "fullforward exceeds memory limit");
  checkCancelled(cancelled);
  std::vector<std::vector<float>> states;
  states.reserve(tokens.size());
  for (auto token : tokens) {
    require(token >= 0 && std::uint32_t(token) < c.vocab, "token ID out of bounds");
    states.push_back(tensor("tok_embeddings").row(static_cast<std::size_t>(token)));
  }
  const auto headDim = c.dim / c.heads, kvDim = c.kvHeads * headDim;
  for (std::uint32_t layer = 0; layer < c.layers; ++layer) {
    checkCancelled(cancelled);
    const auto p = "layers." + std::to_string(layer) + ".";
    std::vector<std::vector<float>> queries;
    std::vector<float> keys(tokens.size() * kvDim), values(tokens.size() * kvDim);
    for (std::size_t t = 0; t < tokens.size(); ++t) {
      auto normalized = rmsNorm(states[t], tensor(p + "attn_norm"), c.rmsEpsilon);
      auto query = tensor(p + "wq").multiply(normalized, cancelled);
      auto key = tensor(p + "wk").multiply(normalized, cancelled);
      auto value = tensor(p + "wv").multiply(normalized, cancelled);
      rope(query, c.heads, headDim, static_cast<std::uint32_t>(t), c.ropeTheta);
      rope(key, c.kvHeads, headDim, static_cast<std::uint32_t>(t), c.ropeTheta);
      queries.push_back(std::move(query));
      std::copy(key.begin(), key.end(), keys.begin() + t * kvDim);
      std::copy(value.begin(), value.end(), values.begin() + t * kvDim);
    }
    // Causal mask: each position can attend only through itself.
    for (std::size_t t = 0; t < tokens.size(); ++t) {
      auto attended = attention(queries[t], keys.data(), values.data(), static_cast<std::uint32_t>(t + 1), c, cancelled);
      add(states[t], tensor(p + "wo").multiply(attended, cancelled));
      const auto normalized = rmsNorm(states[t], tensor(p + "ffn_norm"), c.rmsEpsilon);
      add(states[t], feedForward(*this, p, normalized, cancelled));
    }
  }
  for (auto& state : states) state = rmsNorm(state, tensor("final_norm"), c.rmsEpsilon);
  return states;
}

LoRAAdapter LoRAAdapter::initialize(const Model& model, std::uint32_t rank,
                                  std::uint64_t seed, float scale) {
  const auto& c = model.config();
  LoRAAdapter out;
  out.dim = c.dim; out.vocab = c.vocab; out.rank = rank; out.scale = scale; out.modelSha256 = model.sha256();
  out.parameters.assign(parameterCount(c.dim, c.vocab, rank), 0.0f);
  // Explicit integer PRNG, independent of STL distribution implementations.
  auto state = seed;
  const float width = static_cast<float>(1.0 / std::sqrt(double(c.dim)));
  for (std::size_t i = 0; i < std::size_t(rank) * c.dim; ++i) {
    state += 0x9e3779b97f4a7c15ULL;
    std::uint64_t value = state;
    value = (value ^ (value >> 30)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27)) * 0x94d049bb133111ebULL;
    value ^= value >> 31;
    const float unit = static_cast<float>(value >> 40) / 16777216.0f;
    out.parameters[i] = (unit * 2.0f - 1.0f) * width;
  }
  out.validate(model);
  return out;
}
void LoRAAdapter::validate(const Model& model) const {
  require(dim == model.config().dim && vocab == model.config().vocab && modelSha256 == model.sha256(),
          "adapter/model identity or shape mismatch");
  require(parameters.size() == parameterCount(dim, vocab, rank), "incorrect adapter parameter length");
  require(std::isfinite(scale) && scale > 0 && scale <= 256, "invalid LoRA scale");
  finiteVector(parameters, "adapter parameter");
}
std::vector<float> LoRAAdapter::logits(const Model& model, const std::vector<float>& hidden,
                                     const std::atomic<bool>* cancelled) const {
  require(dim == model.config().dim && vocab == model.config().vocab &&
          parameters.size() == parameterCount(dim, vocab, rank) &&
          std::isfinite(scale) && scale > 0 && scale <= 256, "invalid adapter logit shape/scale");
  require(hidden.size() == dim, "adapter hidden dimension mismatch");
  auto out = model.tensor("output").multiply(hidden, cancelled);
  std::vector<double> reduced(rank, 0.0);
  for (std::uint32_t r = 0; r < rank; ++r)
    for (std::uint32_t d = 0; d < dim; ++d) reduced[r] += double(parameters[std::size_t(r) * dim + d]) * hidden[d];
  const auto bOffset = std::size_t(rank) * dim;
  for (std::uint32_t v = 0; v < vocab; ++v) {
    if ((v & 127) == 0) checkCancelled(cancelled);
    double delta = 0.0;
    for (std::uint32_t r = 0; r < rank; ++r) delta += double(parameters[bOffset + std::size_t(v) * rank + r]) * reduced[r];
    out[v] += static_cast<float>(scale * delta);
  }
  finiteVector(out, "adapter logits");
  return out;
}
void LoRAAdapter::save(const std::string& path) const {
  require(parameters.size() == parameterCount(dim, vocab, rank), "invalid adapter shape");
  require(modelSha256.size() == 64 && modelSha256.find_first_not_of("0123456789abcdef") == std::string::npos,
          "invalid adapter model SHA256");
  require(std::isfinite(scale) && scale > 0 && scale <= 256, "invalid adapter scale");
  finiteVector(parameters, "adapter parameter");
  std::vector<unsigned char> bytes;
  bytes.reserve(96 + parameters.size() * 4);
  const char* magic = "SIBLORA1";
  bytes.insert(bytes.end(), magic, magic + 8);
  put32(bytes, 1); put32(bytes, dim); put32(bytes, vocab); put32(bytes, rank); putFloat(bytes, scale);
  put32(bytes, static_cast<std::uint32_t>(parameters.size()));
  bytes.insert(bytes.end(), modelSha256.begin(), modelSha256.end());
  for (float v : parameters) putFloat(bytes, v);
  atomicWrite(path, bytes);
}
LoRAAdapter LoRAAdapter::load(const std::string& path, const Model& model) {
  std::ifstream stream(path, std::ios::binary | std::ios::ate);
  if (!stream) throw std::runtime_error("cannot open adapter file");
  const auto size = stream.tellg();
  require(size >= 96 && std::uint64_t(size) <= kMaxWorkingBytes + 96, "invalid adapter file size");
  std::vector<unsigned char> bytes(static_cast<std::size_t>(size));
  stream.seekg(0);
  if (!stream.read(reinterpret_cast<char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())))
    throw std::runtime_error("cannot read adapter file");
  require(std::memcmp(bytes.data(), "SIBLORA1", 8) == 0 && u32(bytes.data() + 8) == 1, "unsupported adapter format");
  LoRAAdapter out;
  out.dim = u32(bytes.data() + 12); out.vocab = u32(bytes.data() + 16);
  out.rank = u32(bytes.data() + 20); out.scale = f32(bytes.data() + 24);
  const auto count = u32(bytes.data() + 28);
  require(count == parameterCount(out.dim, out.vocab, out.rank) && bytes.size() == 96 + std::size_t(count) * 4,
          "truncated/incorrect adapter payload");
  out.modelSha256.assign(reinterpret_cast<const char*>(bytes.data() + 32), 64);
  out.parameters.resize(count);
  for (std::size_t i = 0; i < count; ++i) out.parameters[i] = f32(bytes.data() + 96 + i * 4);
  out.validate(model);
  return out;
}

InferenceSession::InferenceSession(std::shared_ptr<const Model> model,
                                   std::shared_ptr<const LoRAAdapter> adapter,
                                   std::uint32_t maxContext) : model_(std::move(model)), adapter_(std::move(adapter)) {
  require(bool(model_), "model is required");
  const auto& c = model_->config();
  if (adapter_) adapter_->validate(*model_);
  maxContext_ = maxContext ? maxContext : std::min(c.maxContext, std::uint32_t(2048));
  require(maxContext_ > 0 && maxContext_ <= c.maxContext, "invalid session context capacity");
  const auto elements = checkedProduct(checkedProduct(c.layers, maxContext_, "KV cache"),
                                      c.kvHeads * (c.dim / c.heads), "KV cache");
  require(elements <= kMaxWorkingBytes / (2 * sizeof(float)), "KV cache exceeds memory limit");
  keys_.assign(elements, 0.0f); values_.assign(elements, 0.0f);
}
void InferenceSession::reset() {
  position_ = 0; lastHidden_.clear(); cancelled_.store(false, std::memory_order_relaxed);
}
std::vector<float> InferenceSession::step(std::int32_t token) {
  const auto& c = model_->config();
  require(token >= 0 && std::uint32_t(token) < c.vocab, "token ID out of bounds");
  require(position_ < maxContext_, "context limit reached; reset or shorten the conversation");
  checkCancelled(&cancelled_);
  auto hidden = model_->tensor("tok_embeddings").row(static_cast<std::size_t>(token));
  const auto headDim = c.dim / c.heads, kvDim = c.kvHeads * headDim;
  for (std::uint32_t layer = 0; layer < c.layers; ++layer) {
    checkCancelled(&cancelled_);
    const auto p = "layers." + std::to_string(layer) + ".";
    const auto normalized = rmsNorm(hidden, model_->tensor(p + "attn_norm"), c.rmsEpsilon);
    auto q = model_->tensor(p + "wq").multiply(normalized, &cancelled_);
    auto k = model_->tensor(p + "wk").multiply(normalized, &cancelled_);
    const auto v = model_->tensor(p + "wv").multiply(normalized, &cancelled_);
    rope(q, c.heads, headDim, position_, c.ropeTheta); rope(k, c.kvHeads, headDim, position_, c.ropeTheta);
    const auto base = std::size_t(layer) * maxContext_ * kvDim;
    const auto offset = base + std::size_t(position_) * kvDim;
    std::copy(k.begin(), k.end(), keys_.begin() + offset); std::copy(v.begin(), v.end(), values_.begin() + offset);
    auto attended = attention(q, keys_.data() + base, values_.data() + base, position_ + 1, c, &cancelled_);
    add(hidden, model_->tensor(p + "wo").multiply(attended, &cancelled_));
    const auto ffInput = rmsNorm(hidden, model_->tensor(p + "ffn_norm"), c.rmsEpsilon);
    add(hidden, feedForward(*model_, p, ffInput, &cancelled_));
  }
  hidden = rmsNorm(hidden, model_->tensor("final_norm"), c.rmsEpsilon);
  auto logits = adapter_ ? adapter_->logits(*model_, hidden, &cancelled_) : model_->tensor("output").multiply(hidden, &cancelled_);
  checkCancelled(&cancelled_);
  lastHidden_ = std::move(hidden); ++position_;
  return logits;
}
std::vector<std::int32_t> InferenceSession::greedyGenerate(const std::vector<std::int32_t>& prompt,
                                                         std::uint32_t maxNewTokens, std::int32_t eos) {
  require(!prompt.empty(), "prompt requires at least one token");
  require(eos == -1 || (eos >= 0 && std::uint32_t(eos) < model_->config().vocab), "EOS ID out of bounds");
  require(prompt.size() <= maxContext_ - position_, "prompt exceeds session context");
  for (auto token : prompt) require(token >= 0 && std::uint32_t(token) < model_->config().vocab, "token ID out of bounds");
  std::vector<float> logits;
  for (auto token : prompt) logits = step(token);
  std::vector<std::int32_t> generated;
  for (std::uint32_t i = 0; i < maxNewTokens; ++i) {
    checkCancelled(&cancelled_);
    const auto token = static_cast<std::int32_t>(std::max_element(logits.begin(), logits.end()) - logits.begin());
    generated.push_back(token);
    if (token == eos || i + 1 == maxNewTokens || position_ == maxContext_) break;
    logits = step(token);
  }
  return generated;
}
}  // namespace sibalsa
