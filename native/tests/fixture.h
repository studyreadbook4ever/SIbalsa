// SPDX-License-Identifier: Unlicense
#pragma once
#include "sibalsa/runtime.h"
#include <string>

namespace sibalsa::test {
// Deterministic random weights used only for tests; this is not a trained chat model.
void writeFixture(const std::string& path, bool int8 = false,
                  ModelConfig config = {8, 12, 2, 1, 2, 8, 32, 10000.0f, 1e-5f});
}
