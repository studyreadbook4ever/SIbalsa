<!-- SPDX-License-Identifier: Unlicense -->
# SIbalsa portable model format v1

All integers and IEEE-754 float32 numbers are little endian. A model is an immutable regular file. No tokenizer, executable code or training data is embedded. The runtime computes a SHA-256 digest of the entire file as the model identity. Conversion and redistribution rights for externally supplied weights and tokenizer files are separate from SIbalsa source licensing.

## Architecture

The supported decoder uses learned token embeddings; pre-attention RMSNorm; bias-free Q/K/V/O matrices; full causal grouped-query attention; residual addition; pre-feedforward RMSNorm; bias-free SwiGLU (`SiLU(w1 h) * w3 h`, followed by w2); residual addition; final RMSNorm; and a bias-free vocabulary output matrix.

Queries have `heads` heads; keys and values have `kvHeads`, where `heads % kvHeads == 0`. Each KV head is shared by a consecutive group of query heads. `headDim = dim / heads` must be even. For index `i` in the first half of a head and token position `p`, rotary embeddings use angle `p / ropeTheta^(2*i/headDim)` and rotate coordinates `(i, i + headDim/2)` as `(a*cos - b*sin, b*cos + a*sin)`. This is the half-split rotate-half convention used by compatible Hugging Face Llama-style tensors. There is no rotary scaling, truncation/window mask, position offset, projection bias, or QK normalization. Embeddings are not multiplied by `sqrt(dim)`. Attention scores are divided by `sqrt(headDim)` before a stable softmax over positions through the current token.

Weights are row-major; a matrix `[rows, cols]` computes `output[row] = sum(weight[row,col] * input[col])`. Configurations requiring unsupported behavior must be rejected by converters rather than silently approximated. This specification does not imply compatibility with every model bearing a Llama or SmolLM name.

## 64-byte header

| Offset | Type | Value |
|---|---|---|
| 0 | 8 bytes | ASCII `SIBLLM01` |
| 8 | u32 | version 1 |
| 12 | u32 | headerBytes, including descriptor table/padding |
| 16 | u32 | dim |
| 20 | u32 | hiddenDim |
| 24 | u32 | heads |
| 28 | u32 | kvHeads |
| 32 | u32 | layers |
| 36 | u32 | vocab |
| 40 | u32 | maxContext |
| 44 | u32 | tensorCount, exactly `3 + 9*layers` |
| 48 | f32 | ropeTheta |
| 52 | f32 | rmsEpsilon |
| 56 | u64 | reserved, must be zero |

The descriptor table immediately follows at offset 64. `headerBytes >= 64 + 80*tensorCount`; 64-byte alignment of the header and payloads is recommended. File size is bounded at 32 GiB. Dimensions, file ranges, overflow, shapes, names, flags and finite tensor values are checked before inference.

## 80-byte tensor descriptor

| Relative offset | Type | Meaning |
|---|---|---|
| 0 | 48 bytes | Nonempty ASCII tensor name, NUL-terminated |
| 48 | u32 | rows |
| 52 | u32 | cols |
| 56 | u32 | dtype: 0 float32, 1 rowwise int8 |
| 60 | u32 | reserved, zero |
| 64 | u64 | payload offset from file start; divisible by 4 |
| 72 | u64 | exact payload byte length |

Names must be unique and shapes must match the required tensors. Payloads must follow the header and may not overlap each other. A tied vocabulary head must be written as a separate payload, even if it duplicates token embeddings.

Float32 payloads consist of `rows*cols` float32 values. Int8 payloads consist of `rows` positive finite float32 scales, followed immediately by `rows*cols` signed two's-complement int8 values. The reconstructed row value is `scale[row] * quantized[row,col]`. A zero quantized row still requires a positive scale. RMSNorm weights are float32 only. For conversion, a common supported quantization is per-row `scale = max(abs(row))/127`, with a positive fallback for an all-zero row and rounded values clamped to `[-127,127]`.

## Required tensors

| Name | Shape |
|---|---|
| tok_embeddings | `[vocab,dim]` |
| final_norm | `[1,dim]`, float32 |
| output | `[vocab,dim]` |
| layers.N.attn_norm | `[1,dim]`, float32 |
| layers.N.wq | `[dim,dim]` |
| layers.N.wk | `[kvHeads*headDim,dim]` |
| layers.N.wv | `[kvHeads*headDim,dim]` |
| layers.N.wo | `[dim,dim]` |
| layers.N.ffn_norm | `[1,dim]`, float32 |
| layers.N.w1 | `[hiddenDim,dim]`, gate projection |
| layers.N.w2 | `[dim,hiddenDim]`, down projection |
| layers.N.w3 | `[hiddenDim,dim]`, up projection |

`N` ranges from zero through `layers-1`. Compatible Hugging Face names map as follows: `model.embed_tokens.weight` to tok_embeddings; `model.norm.weight` to final_norm; `lm_head.weight` to output; each layer's input_layernorm to attn_norm, post_attention_layernorm to ffn_norm, self_attn q/k/v/o projections to wq/wk/wv/wo, and mlp gate/down/up projections to w1/w2/w3. The converter must also validate configuration semantics and reject extra behavior such as nontrivial rope_scaling or nonzero biases.

## Output-head adapter file

Adapter files use a 96-byte header followed by raw float32 parameters:

| Offset | Type | Meaning |
|---|---|---|
| 0 | 8 bytes | ASCII `SIBLORA1` |
| 8 | u32 | version 1 |
| 12 | u32 | dim |
| 16 | u32 | vocab |
| 20 | u32 | rank |
| 24 | f32 | positive scale, JNI uses 1 |
| 28 | u32 | parameter count `(dim+vocab)*rank` |
| 32 | 64 bytes | lowercase ASCII SHA-256 of model file |
| 96 | f32[] | row-major A `[rank,dim]` then B `[vocab,rank]` |

Files must have the exact expected length and only finite parameters. The model digest, dimensions and rank are checked on load. Save writes a private temporary file, flushes it and atomically replaces the destination. The SHA-256 is an identity binding, not a signature or authenticity guarantee. Model/adapter distribution should separately authenticate its source.

The raw flattened vector passed through JNI has the same A-then-B order. A coordinator may aggregate compatible factor parameter deltas from a common base, but averaging factors is not identical to averaging products `BA`. SIbalsa's server/client integration must describe which operation it uses and evaluate the resulting global adapter; this file format does not make that equivalence claim.
