<!-- SPDX-License-Identifier: Unlicense -->
# SIbalsa native runtimes

This directory contains an original, dependency-free C++17 CPU implementation of a causal transformer and a separate language-model training runtime. All source code here is under the repository's Unlicense. It does not contain third-party inference engines, copied implementation snippets, pretrained weights, tokenizers, or datasets.

The inference runtime performs embedding lookup, RMSNorm, rotary positional embeddings, grouped-query causal attention, residuals, SwiGLU, and vocabulary projection. It memory-maps user-supplied float32 or rowwise-int8 tensors and maintains a bounded key/value cache. The training runtime uses the frozen transformer, computes corrected-response SFT or paired DPO losses, and optimizes a low-rank vocabulary output projection. These updates change the logits used by actual autoregressive generation; they do not train a response selector.

Host builds expose these runtimes as `sibalsa_inference` and `sibalsa_training` shared libraries. Android packages both implementations and JNI bridges in a single `libsibalsa_android.so` per ABI. Inference sessions and trainers retain independent Java APIs, handle registries, model mappings, adapter state and lifecycle. One Android shared object owns all native C++ allocations, exceptions and statically linked C++ runtime state; no C++ object API crosses an Android shared-library boundary. C++ and inline symbols are hidden, archive symbols are excluded from exports, and only JNI entry points are public. This avoids duplicate static C++ runtime state within SIbalsa and avoids bundling a host-conflicting `libc++_shared.so`.

This is a portable scalar CPU reference implementation. It has no GPU/NPU backend or claim of flagship-phone latency. Its architecture is deliberately limited to [the explicit SIbalsa model format](docs/model-format.md). Models with attention biases, scaled RoPE, sliding-window attention, QK normalization, different activation functions, or other unsupported features require another runtime or a reviewed architecture extension. A matching tokenizer and chat template remain the integrator's responsibility.

## Host build and checks

```sh
cmake -S native -B /tmp/sibalsa-native-build -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/sibalsa-native-build -j4
ctest --test-dir /tmp/sibalsa-native-build --output-on-failure
```

Run those commands from the repository root. `SIBALSA_SANITIZERS=ON` enables AddressSanitizer and UndefinedBehaviorSanitizer in a Debug host build. `SIBALSA_BUILD_JNI=ON` builds the same JNI bridges on a host with a JDK. Android builds always include JNI and disable host CLI/tests.

The tests create and delete deterministic random models in a temporary directory. They compare independent full-sequence causal forwarding with incremental cached forwarding; compare int8 and float32 results; finite-difference every A/B parameter for SFT and DPO; verify loss reduction and changed generated token IDs; test cancellation without an optimizer commit; reject malformed/overlapping/truncated/non-finite models and adapters; and check exact adapter round trips and flattened deltas. The generated weights are test fixtures, not trained conversational models.

## CLI

```sh
/tmp/sibalsa-native-build/sibalsa_native_cli fixture /tmp/toy.sib int8
/tmp/sibalsa-native-build/sibalsa_native_cli info /tmp/toy.sib
/tmp/sibalsa-native-build/sibalsa_native_cli init-adapter /tmp/toy.sib 2 42 /tmp/base.json
/tmp/sibalsa-native-build/sibalsa_native_cli logits /tmp/toy.sib 256,97
/tmp/sibalsa-native-build/sibalsa_native_cli sft /tmp/toy.sib 256,97,98 2 180 /tmp/adapter.siblora
/tmp/sibalsa-native-build/sibalsa_native_cli score /tmp/toy.sib 256,97,98 2 /tmp/adapter.siblora
/tmp/sibalsa-native-build/sibalsa_native_cli generate /tmp/toy.sib 256,97 8 /tmp/adapter.siblora
```

The CLI fixture has a vocabulary of 258 and context capacity 256, compatible with a byte test tokenizer (BOS 256 / EOS 257). No tokenizer or conversational quality is implied. `init-adapter` writes a JSON array in the exact flattened native float32 layout; round initialization should use this artifact rather than reproducing floating-point initialization in another language.

`score` loads a model-bound adapter, computes completion-token SFT loss with the given prompt mask, and returns JSON containing finite `loss` and the scored completion `tokens`. It performs no optimizer step and writes no files. Use it to compare baseline and candidate adapters on an independent, task-appropriate local evaluation set. A lower loss on a synthetic fixture or the training example demonstrates mechanics; it does not establish real chat quality or generalization.

## Training contract

The base transformer and original output matrix are frozen. With final hidden state `h`, logits are `W_output h + scale B A h`. A has shape `[rank, dim]`, B has shape `[vocab, rank]`, and the vector contains row-major A followed by row-major B. Default scale is 1. A is deterministic from an explicit 64-bit integer PRNG and seed; B starts at zero. Clients in the same round must install the same exact base adapter and compatible model metadata.

SFT takes a token sequence containing a nonempty prompt followed by a nonempty corrected completion. `promptLength` masks all prompt labels. The loss averages completion-token negative log likelihoods, with token `t` predicted from position `t-1`. The trainer updates A and B with globally clipped SGD. Hidden states are recomputed with the frozen backbone, so training needs no derivative through the transformer. This saves memory but offers less expressive adaptation than inserting trainable adapters throughout transformer layers.

DPO takes a nonempty prompt and chosen/rejected completions for that same prompt. It minimizes the standard negative log sigmoid of the beta-scaled policy-versus-reference sequence-log-probability difference. The reference adapter is fixed at creation or when a global base adapter is installed through `setParameters(..., true)` / JNI `setAdapterParameters`. Optimizer steps do not move that reference. JNI does not expose raw thumbs-up/down training or KTO; the integrator must collect compatible corrected completions or genuine same-prompt preference pairs.

Cancellation can interrupt matrix loops, attention, full forwarding, and token loss/gradient calculation. A training step calculates and validates a candidate before committing it under a small cancellation/commit lock. Cancellation is atomic with that commit: a request completed before commit prevents it, and completed prior steps remain completed. Cancellation alone does not undo earlier minibatches; the Android job wrapper must discard the whole job result when its request is cancelled. Runtime/session methods other than `cancel` require serial use; JNI serializes them with object-local mutexes. Model files must remain immutable for the lifetime of their mappings.

The cache and fullforward buffers have explicit individual bounds; this is not a total process-memory budget. Parameters, reference adapters, gradients, candidate updates, Java arrays, and independently mapped inference/training models can coexist. Applications must choose safe context/rank limits and stop inference before memory-intensive training on devices with limited RAM.

## JNI ownership

`ai.sibalsa.runtime.InferenceBridge` and `ai.sibalsa.runtime.TrainingBridge` bind their respective logical runtimes. On Android they load the same `sibalsa_android` library; host JVM checks use the two host libraries. Handles are tagged, monotonically allocated registry IDs, never unchecked pointers. A session retains its own shared model ownership, so closing its model handle cannot invalidate an in-flight forward. Training independently maps the supplied model path. Unknown, wrong-kind and stale handles fail as `IllegalArgumentException`; runtime failures are translated to `IOException`, allocation failures to `OutOfMemoryError`. Close handles in `finally` blocks.

`tests/JniSmoke.java` checks the same bridges on a host JVM. Compile it together with all Java files in `android/library/src/main/java/ai/sibalsa/runtime/` and run it with `-Djava.library.path=<JNI build directory>` and a CLI-generated fixture path. `scripts/check.py` performs this check automatically.
