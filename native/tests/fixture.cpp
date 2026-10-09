// SPDX-License-Identifier: Unlicense
#include "fixture.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <stdexcept>

namespace sibalsa::test {
namespace {
void p32(std::vector<unsigned char>& b, std::size_t at, std::uint32_t value) {
  for (int i = 0; i < 4; ++i) b.at(at + i) = static_cast<unsigned char>(value >> (i * 8));
}
void p64(std::vector<unsigned char>& b, std::size_t at, std::uint64_t value) {
  p32(b, at, static_cast<std::uint32_t>(value)); p32(b, at + 4, static_cast<std::uint32_t>(value >> 32));
}
void pf(std::vector<unsigned char>& b, std::size_t at, float value) {
  std::uint32_t bits; std::memcpy(&bits, &value, 4); p32(b, at, bits);
}
struct Shape { std::string name; std::uint32_t rows, cols; bool norm; };
std::size_t align64(std::size_t n) { return (n + 63) & ~std::size_t(63); }
}
void writeFixture(const std::string& path, bool int8, ModelConfig c) {
  std::vector<Shape> shapes = {{"tok_embeddings", c.vocab, c.dim, false},
                                {"final_norm", 1, c.dim, true}, {"output", c.vocab, c.dim, false}};
  const auto kvDim = c.kvHeads * (c.dim / c.heads);
  for (std::uint32_t l = 0; l < c.layers; ++l) {
    const auto prefix = "layers." + std::to_string(l) + ".";
    const std::vector<Shape> layer = {
      {prefix + "attn_norm", 1, c.dim, true}, {prefix + "wq", c.dim, c.dim, false},
      {prefix + "wk", kvDim, c.dim, false}, {prefix + "wv", kvDim, c.dim, false},
      {prefix + "wo", c.dim, c.dim, false}, {prefix + "ffn_norm", 1, c.dim, true},
      {prefix + "w1", c.hiddenDim, c.dim, false}, {prefix + "w2", c.dim, c.hiddenDim, false},
      {prefix + "w3", c.hiddenDim, c.dim, false}};
    shapes.insert(shapes.end(), layer.begin(), layer.end());
  }
  const auto headerSize = align64(64 + shapes.size() * 80);
  std::vector<unsigned char> bytes(headerSize, 0);
  std::memcpy(bytes.data(), "SIBLLM01", 8);
  p32(bytes, 8, 1); p32(bytes, 12, static_cast<std::uint32_t>(headerSize));
  p32(bytes, 16, c.dim); p32(bytes, 20, c.hiddenDim); p32(bytes, 24, c.heads); p32(bytes, 28, c.kvHeads);
  p32(bytes, 32, c.layers); p32(bytes, 36, c.vocab); p32(bytes, 40, c.maxContext);
  p32(bytes, 44, static_cast<std::uint32_t>(shapes.size())); pf(bytes, 48, c.ropeTheta); pf(bytes, 52, c.rmsEpsilon);
  std::uint64_t state = 42;
  for (std::size_t i = 0; i < shapes.size(); ++i) {
    const auto& shape = shapes[i];
    const auto descriptor = 64 + i * 80;
    const std::uint32_t dtype = int8 && !shape.norm ? 1 : 0;
    const auto elements = std::size_t(shape.rows) * shape.cols;
    const auto payloadSize = dtype == 0 ? elements * 4 : shape.rows * 4 + elements;
    const auto offset = align64(bytes.size());
    bytes.resize(offset + payloadSize, 0);
    std::memcpy(bytes.data() + descriptor, shape.name.c_str(), shape.name.size());
    p32(bytes, descriptor + 48, shape.rows); p32(bytes, descriptor + 52, shape.cols);
    p32(bytes, descriptor + 56, dtype); p64(bytes, descriptor + 64, offset); p64(bytes, descriptor + 72, payloadSize);
    std::vector<float> values(elements);
    for (auto& value : values) {
      state = state * 6364136223846793005ULL + 1442695040888963407ULL;
      const float unit = static_cast<float>(state >> 40) / 16777216.0f;
      value = shape.norm ? 1.0f : (unit * 2.0f - 1.0f) * static_cast<float>(0.4 / std::sqrt(shape.cols));
    }
    if (dtype == 0) {
      for (std::size_t j = 0; j < elements; ++j) pf(bytes, offset + j * 4, values[j]);
    } else {
      for (std::size_t row = 0; row < shape.rows; ++row) {
        float maximum = 0.0f;
        for (std::size_t col = 0; col < shape.cols; ++col) maximum = std::max(maximum, std::abs(values[row * shape.cols + col]));
        const float scale = maximum > 0 ? maximum / 127.0f : 1.0f;
        pf(bytes, offset + row * 4, scale);
        for (std::size_t col = 0; col < shape.cols; ++col) {
          const auto q = static_cast<std::int8_t>(std::lround(values[row * shape.cols + col] / scale));
          bytes[offset + shape.rows * 4 + row * shape.cols + col] = static_cast<unsigned char>(q);
        }
      }
    }
  }
  std::ofstream file(path, std::ios::binary);
  if (!file.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size())))
    throw std::runtime_error("cannot write fixture");
}
}  // namespace sibalsa::test
