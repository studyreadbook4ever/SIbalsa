// SPDX-License-Identifier: Unlicense
#include "fixture.h"
#include "sibalsa/training.h"

#include <iostream>
#include <iomanip>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>

namespace {
std::vector<std::int32_t> parseTokens(const std::string& text) {
  std::vector<std::int32_t> tokens;
  std::stringstream stream(text);
  std::string part;
  while (std::getline(stream, part, ',')) {
    std::size_t used = 0;
    const long long n = std::stoll(part, &used);
    if (used != part.size() || n < 0 || n > INT32_MAX) throw std::invalid_argument("invalid token list");
    tokens.push_back(static_cast<std::int32_t>(n));
  }
  return tokens;
}
unsigned number(const char* text) {
  std::size_t used = 0;
  const auto n = std::stoull(text, &used);
  if (text[0] == '-' || used != std::string(text).size() || n > 1000000) throw std::invalid_argument("invalid numeric argument");
  return static_cast<unsigned>(n);
}
void usage() {
  std::cerr << "SIbalsa portable CPU reference runtime (token IDs only)\n"
            << "  fixture <model.sib> [int8]       Generate random TEST weights, not a chat model\n"
            << "  info <model.sib>\n"
            << "  init-adapter <model.sib> <rank> <seed> <output.json>\n"
            << "  logits <model.sib> <ids>        Comma-separated token IDs; returns final logits\n"
            << "  generate <model.sib> <ids> <count> [adapter.siblora]\n"
            << "  sft <model.sib> <ids> <promptLength> <steps> <adapter.siblora>\n"
            << "  score <model.sib> <ids> <promptLength> <adapter.siblora>\n";
}
}
int main(int argc, char** argv) {
  try {
    if (argc < 3) { usage(); return 2; }
    const std::string command = argv[1], path = argv[2];
    if (command == "fixture") {
      sibalsa::test::writeFixture(path, argc == 4 && std::string(argv[3]) == "int8",
                                 {8, 12, 2, 1, 2, 258, 256, 10000.0f, 1e-5f});
      std::cout << "random test fixture written\n"; return 0;
    }
    const auto model = sibalsa::Model::load(path);
    const auto& c = model->config();
    if (command == "init-adapter" && argc == 6) {
      std::size_t used = 0;
      const auto seed = std::stoull(argv[4], &used);
      if (argv[4][0] == '-' || used != std::string(argv[4]).size()) throw std::invalid_argument("invalid seed");
      const auto adapter = sibalsa::LoRAAdapter::initialize(*model, number(argv[3]), seed);
      std::ofstream out(argv[5]);
      if (!out) throw std::runtime_error("cannot open adapter JSON output");
      out << std::setprecision(std::numeric_limits<float>::max_digits10) << "[";
      for (std::size_t i = 0; i < adapter.parameters.size(); ++i) out << (i ? "," : "") << adapter.parameters[i];
      out << "]\n";
      if (!out) throw std::runtime_error("cannot write adapter JSON output");
      std::cout << "deterministic adapter JSON written\n"; return 0;
    }
    if (command == "info") {
      std::cout << "{\"model_sha256\":\"" << model->sha256() << "\",\"dim\":" << c.dim
                << ",\"hidden_dim\":" << c.hiddenDim << ",\"heads\":" << c.heads
                << ",\"kv_heads\":" << c.kvHeads << ",\"layers\":" << c.layers
                << ",\"vocab\":" << c.vocab << ",\"max_context\":" << c.maxContext << "}\n";
      return 0;
    }
    if (command == "logits" && argc == 4) {
      sibalsa::InferenceSession session(model);
      const auto tokens = parseTokens(argv[3]);
      if (tokens.empty()) throw std::invalid_argument("empty prompt");
      std::vector<float> logits;
      for (auto token : tokens) logits = session.step(token);
      for (std::size_t i = 0; i < logits.size(); ++i) std::cout << (i ? "," : "") << logits[i];
      std::cout << '\n'; return 0;
    }
    if (command == "generate" && (argc == 5 || argc == 6)) {
      std::shared_ptr<const sibalsa::LoRAAdapter> adapter;
      if (argc == 6) adapter = std::make_shared<sibalsa::LoRAAdapter>(sibalsa::LoRAAdapter::load(argv[5], *model));
      sibalsa::InferenceSession session(model, adapter);
      const auto tokens = session.greedyGenerate(parseTokens(argv[3]), number(argv[4]));
      for (std::size_t i = 0; i < tokens.size(); ++i) std::cout << (i ? "," : "") << tokens[i];
      std::cout << '\n'; return 0;
    }
    if (command == "sft" && argc == 7) {
      sibalsa::TrainingRuntime trainer(model, sibalsa::LoRAAdapter::initialize(*model, 2, 42));
      const auto sequence = parseTokens(argv[3]);
      const auto promptLength = number(argv[4]), steps = number(argv[5]);
      if (steps == 0) throw std::invalid_argument("steps must be positive");
      const auto before = trainer.sftGradient(sequence, promptLength).loss;
      for (unsigned i = 0; i < steps; ++i) trainer.trainSft(sequence, promptLength, 0.1f, 1.0f);
      trainer.saveAdapter(argv[6]);
      std::cout << "{\"loss_before\":" << before << ",\"loss_after\":"
                << trainer.sftGradient(sequence, promptLength).loss << "}\n"; return 0;
    }
    if (command == "score" && argc == 6) {
      sibalsa::TrainingRuntime evaluator(model, sibalsa::LoRAAdapter::load(argv[5], *model), model->config().maxContext);
      const auto result = evaluator.sftGradient(parseTokens(argv[3]), number(argv[4]));
      std::cout << std::setprecision(std::numeric_limits<double>::max_digits10)
                << "{\"loss\":" << result.loss << ",\"tokens\":" << result.tokens << "}\n";
      return 0;
    }
    usage(); return 2;
  } catch (const std::exception& error) { std::cerr << "error: " << error.what() << '\n'; return 1; }
}
