#!/usr/bin/env python3
# SPDX-License-Identifier: Unlicense
"""Convert an integrator-owned, supported Llama-style safetensors checkpoint. No downloads."""
import argparse
import hashlib
import json
import math
import mmap
from pathlib import Path
import shutil
import struct
import tempfile
import zipfile


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field: " + key)
        result[key] = value
    return result


def json_read(data):
    return json.loads(data, object_pairs_hook=strict_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Nonfinite JSON number")))


def validate_tokenizer(tokenizer):
    model = tokenizer.get("model", {})
    if model.get("type") != "BPE" or model.get("byte_fallback", False) or model.get("dropout") not in (None, 0):
        raise ValueError("Only deterministic ByteLevel BPE is supported")
    if tokenizer.get("normalizer") is not None or model.get("continuing_subword_prefix") or model.get("end_of_word_suffix"):
        raise ValueError("Tokenizer normalization or subword markers require a custom integration")
    pre = tokenizer.get("pre_tokenizer", {})
    stages = pre.get("pretokenizers", []) if pre.get("type") == "Sequence" else [pre]
    if not (len(stages) == 1 and stages[0].get("type") == "ByteLevel") and not (
            len(stages) == 2 and stages[0].get("type") == "Split" and stages[1].get("type") == "ByteLevel"
            and stages[1].get("use_regex", True) is False):
        raise ValueError("Only ByteLevel or Split then ByteLevel(use_regex=false) is supported")
    byte_level = False
    for stage in stages:
        if stage.get("type") == "ByteLevel":
            byte_level = True
        elif stage.get("type") == "Split" and not stage.get("invert", False) and stage.get("behavior") == "Isolated" and isinstance(stage.get("pattern", {}).get("Regex"), str):
            pass
        else:
            raise ValueError("Unsupported tokenizer preprocessing")
    if not byte_level:
        raise ValueError("ByteLevel pre-tokenizer required")
    vocab = model.get("vocab", {})
    if not isinstance(vocab, dict) or any(type(value) is not int or value < 0 for value in vocab.values()) or len(set(vocab.values())) != len(vocab):
        raise ValueError("Invalid BPE vocabulary IDs")
    inverse = {value: key for key, value in vocab.items()}
    added = set()
    for token in tokenizer.get("added_tokens", []):
        if any(token.get(flag, False) for flag in ("lstrip", "rstrip", "single_word")):
            raise ValueError("Unsupported added-token whitespace semantics")
        content, identifier = token.get("content"), token.get("id")
        if not isinstance(content, str) or not content or content in added or type(identifier) is not int or identifier < 0:
            raise ValueError("Invalid or duplicate added token")
        if token.get("special") is not True:
            raise ValueError("Non-special added tokens require a custom integration")
        if (identifier in inverse and inverse[identifier] != content) or (content in vocab and vocab[content] != identifier):
            raise ValueError("Added-token ID conflicts with vocabulary")
        added.add(content)
        inverse[identifier] = content


class SafeTensors:
    def __init__(self, folder):
        self.files, self.tensors = [], {}
        try:
            self._load(folder)
        except BaseException:
            self.close()
            raise

    def _load(self, folder):
        paths = sorted(folder.glob("*.safetensors"))
        if not paths:
            raise ValueError("No safetensors files; no automatic downloads are performed")
        for path in paths:
            file = path.open("rb")
            try:
                mapping = mmap.mmap(file.fileno(), 0, access=mmap.ACCESS_READ)
            except BaseException:
                file.close()
                raise
            self.files.append((file, mapping))
            if len(mapping) < 8:
                raise ValueError("Truncated safetensors file")
            size = struct.unpack_from("<Q", mapping)[0]
            if not 2 <= size <= min(64 * 1024 * 1024, len(mapping) - 8):
                raise ValueError("Invalid safetensors header length")
            header = json_read(mapping[8:8 + size])
            if not isinstance(header, dict):
                raise ValueError("Safetensors header must be an object")
            ranges = []
            for name, value in header.items():
                if name == "__metadata__":
                    if not isinstance(value, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in value.items()):
                        raise ValueError("Safetensors metadata must be string pairs")
                    continue
                if name in self.tensors:
                    raise ValueError("Duplicate checkpoint tensor: " + name)
                dtype, shape, offsets = value["dtype"], value["shape"], value["data_offsets"]
                if dtype not in {"F32", "F16", "BF16"} or not shape or any(type(x) is not int or x < 1 for x in shape):
                    raise ValueError("Only nonempty F32/F16/BF16 tensors are supported")
                width = 4 if dtype == "F32" else 2
                if len(offsets) != 2 or any(type(x) is not int for x in offsets):
                    raise ValueError("Invalid checkpoint offsets")
                begin, end = offsets
                if begin < 0 or end - begin != math.prod(shape) * width or 8 + size + end > len(mapping):
                    raise ValueError("Invalid checkpoint tensor extent")
                ranges.append((begin, end))
                self.tensors[name] = (mapping, 8 + size + begin, dtype, tuple(shape))
            ranges.sort()
            if any(a[1] > b[0] for a, b in zip(ranges, ranges[1:])):
                raise ValueError("Overlapping checkpoint tensors")

    def row(self, name, index, expected):
        mapping, start, dtype, shape = self.tensors[name]
        actual = (1, shape[0]) if len(shape) == 1 else shape
        if actual != expected:
            raise ValueError(f"Shape mismatch for {name}: {shape}, expected {expected}")
        columns = expected[1]
        offset = start + index * columns * (4 if dtype == "F32" else 2)
        if dtype == "BF16":
            values = [struct.unpack("<f", struct.pack("<I", bits << 16))[0] for bits in struct.unpack_from("<" + "H" * columns, mapping, offset)]
        else:
            values = struct.unpack_from("<" + ("f" if dtype == "F32" else "e") * columns, mapping, offset)
        if any(not math.isfinite(x) for x in values):
            raise ValueError("Nonfinite checkpoint tensor")
        return values

    def close(self):
        for file, mapping in self.files:
            mapping.close()
            file.close()


def align(value):
    return (value + 63) // 64 * 64


def _convert(folder, output, quantize, context, rank, prefix, suffix):
    config = json_read((folder / "config.json").read_text())
    if config.get("model_type") != "llama" or config.get("hidden_act", "silu") != "silu":
        raise ValueError("Built-in converter supports standard Llama-style RMSNorm/RoPE/SwiGLU only; add a custom integration for another architecture")
    if config.get("attention_bias", False) or config.get("mlp_bias", False) or config.get("partial_rotary_factor", 1) != 1:
        raise ValueError("Projection biases/partial RoPE are unsupported")
    if config.get("qk_norm", False) or config.get("use_qk_norm", False) or config.get("sliding_window") is not None:
        raise ValueError("QK normalization/sliding-window attention requires a custom integration")
    scaling = config.get("rope_scaling")
    if scaling and scaling.get("rope_type", scaling.get("type")) != "default":
        raise ValueError("Scaled/hybrid RoPE is unsupported; never silently reinterpret a model")
    dim, hidden, heads = config["hidden_size"], config["intermediate_size"], config["num_attention_heads"]
    kv, layers, vocab = config.get("num_key_value_heads", heads), config["num_hidden_layers"], config["vocab_size"]
    for value, minimum, maximum in [(dim, 1, 16384), (hidden, 1, 65536), (heads, 1, 256), (kv, 1, 256), (layers, 1, 128), (vocab, 2, 262144), (context, 2, 32768), (rank, 1, 64), (config["max_position_embeddings"], 2, 131072)]:
        if type(value) is not int or not minimum <= value <= maximum:
            raise ValueError("Invalid model dimension/context/rank")
    for value, minimum, maximum in ((config.get("rope_theta", 10000.0), 1, 1e9), (config.get("rms_norm_eps", 1e-5), 1e-12, 1)):
        if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value) or not minimum <= value <= maximum:
            raise ValueError("Invalid normalization/RoPE constant")
    if dim % heads or heads % kv or (dim // heads) % 2 or config.get("head_dim", dim // heads) != dim // heads:
        raise ValueError("Unsupported attention dimensions")
    context = min(context, config["max_position_embeddings"])
    if not 2 <= context <= 32768 or not 1 <= rank <= 64:
        raise ValueError("Context or rank out of range")
    tensors = SafeTensors(folder)
    try:
        if any(name.endswith(".bias") for name in tensors.tensors):
            raise ValueError("Checkpoint contains unsupported projection biases")
        if any("q_norm" in name or "k_norm" in name for name in tensors.tensors):
            raise ValueError("Checkpoint contains unsupported QK normalization")
        names = [("tok_embeddings", "model.embed_tokens.weight", vocab, dim, False),
                 ("final_norm", "model.norm.weight", 1, dim, True)]
        head = "lm_head.weight"
        if head not in tensors.tensors and config.get("tie_word_embeddings", False):
            head = "model.embed_tokens.weight"
        names.append(("output", head, vocab, dim, False))
        for layer in range(layers):
            destination, source = f"layers.{layer}", f"model.layers.{layer}"
            for short, original, rows, cols, norm in [
                ("attn_norm", "input_layernorm.weight", 1, dim, True),
                ("wq", "self_attn.q_proj.weight", dim, dim, False),
                ("wk", "self_attn.k_proj.weight", kv * (dim // heads), dim, False),
                ("wv", "self_attn.v_proj.weight", kv * (dim // heads), dim, False),
                ("wo", "self_attn.o_proj.weight", dim, dim, False),
                ("ffn_norm", "post_attention_layernorm.weight", 1, dim, True),
                ("w1", "mlp.gate_proj.weight", hidden, dim, False),
                ("w2", "mlp.down_proj.weight", dim, hidden, False),
                ("w3", "mlp.up_proj.weight", hidden, dim, False),
            ]:
                names.append((destination + "." + short, source + "." + original, rows, cols, norm))
        for _, name, rows, cols, _ in names:
            if name not in tensors.tensors:
                raise ValueError("Missing tensor: " + name)
            tensors.row(name, 0, (rows, cols))
        output.mkdir(parents=True, exist_ok=False)
        descriptors = []
        offset = align(64 + 80 * len(names))
        for name, source, rows, cols, norm in names:
            dtype = int(quantize and not norm)
            size = rows * cols * 4 if dtype == 0 else rows * 4 + rows * cols
            descriptors.append((name, source, rows, cols, dtype, offset, size))
            offset = align(offset + size)
        model = output / "model.sbm"
        with model.open("wb") as stream:
            stream.write(struct.pack("<8s10I2fQ", b"SIBLLM01", 1, align(64 + 80 * len(names)), dim, hidden, heads, kv, layers, vocab, context, len(names), config.get("rope_theta", 10000.0), config.get("rms_norm_eps", 1e-5), 0))
            for name, _, rows, cols, dtype, start, size in descriptors:
                stream.write(struct.pack("<48s4I2Q", name.encode(), rows, cols, dtype, 0, start, size))
            for name, source, rows, cols, dtype, start, size in descriptors:
                stream.seek(start)
                print("Writing", name, rows, cols, "int8" if dtype else "float32", flush=True)
                if dtype:
                    # Write scales first, then matrix; one row in memory at a time.
                    for row in range(rows):
                        values = tensors.row(source, row, (rows, cols))
                        maximum = max(map(abs, values))
                        scale = max(maximum / 127, 2**-126) if maximum else 1.0
                        stream.seek(start + row * 4)
                        stream.write(struct.pack("<f", scale))
                        stream.seek(start + rows * 4 + row * cols)
                        stream.write(bytes(max(-127, min(127, round(x / scale))) & 255 for x in values))
                else:
                    for row in range(rows):
                        stream.write(struct.pack("<" + "f" * cols, *tensors.row(source, row, (rows, cols))))
            stream.truncate(offset)
        tokenizer = json_read((folder / "tokenizer.json").read_text())
        validate_tokenizer(tokenizer)
        shutil.copyfile(folder / "tokenizer.json", output / "tokenizer.json")
        with model.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        eos = config.get("eos_token_id", [])
        eos = [] if eos is None else eos
        eos = eos if isinstance(eos, list) else [eos]
        bos = config.get("bos_token_id", -1)
        bos = -1 if bos is None else bos
        if type(bos) is not int or not -1 <= bos < vocab or any(type(x) is not int or not 0 <= x < vocab for x in eos):
            raise ValueError("Invalid special token IDs")
        integration = {"format_version": 1, "model_file": "model.sbm", "model_id": digest,
                       "tokenizer_type": "byte_bpe", "tokenizer_file": "tokenizer.json",
                       "adapter_spec": f"lm_head.lora.v1.r{rank}.d{dim}.v{vocab}", "rank": rank,
                       "dimension": rank * (dim + vocab), "max_context": context,
                       "bos_token_id": bos, "eos_token_ids": eos,
                       "prompt_prefix": prefix, "prompt_suffix": suffix,
                       "seed": 42, "dpo_beta": 0.1, "gradient_clip": 1.0}
        (output / "integration.json").write_text(json.dumps(integration, ensure_ascii=False, indent=2) + "\n")
        print("Converted owned model. Verify tokenizer IDs and the exact chat template before use.")
    finally:
        tensors.close()


def convert(folder, output, quantize, context, rank, prefix, suffix):
    if output.exists():
        raise ValueError("Output already exists; no existing model files are overwritten")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="sibalsa-convert-", dir=output.parent) as temporary:
        staged = Path(temporary) / "model"
        _convert(folder, staged, quantize, context, rank, prefix, suffix)
        staged.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--float32", action="store_true", help="Disable rowwise int8 quantization")
    parser.add_argument("--context", type=int, default=2048)
    parser.add_argument("--rank", type=int, default=4)
    parser.add_argument("--prompt-prefix", default="")
    parser.add_argument("--prompt-suffix", default="")
    parser.add_argument("--bundle", type=Path, help="Create an importable ZIP bundle (.sibalsa) with the owned weights")
    args = parser.parse_args()
    convert(args.checkpoint, args.output, not args.float32, args.context, args.rank, args.prompt_prefix, args.prompt_suffix)
    if args.bundle:
        with zipfile.ZipFile(args.bundle, "w", zipfile.ZIP_STORED) as archive:
            for name in ["integration.json", "tokenizer.json", "model.sbm"]:
                archive.write(args.output / name, name)
        print("Private model bundle:", args.bundle)


if __name__ == "__main__":
    main()
