# SPDX-License-Identifier: Unlicense
"""Independent converter/native contract checks using original temporary synthetic weights.

No pretrained artifacts, downloads, third-party Python packages, or copied model code are used.
"""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("sibalsa_converter_under_test", ROOT / "scripts/convert_model.py")
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)
NATIVE = Path(os.environ.get("SIBALSA_NATIVE_CLI", ROOT / ".build/native-host/sibalsa_native_cli"))


def original_tensors():
    """Small one-layer GQA decoder; values are mathematical test data, not a checkpoint copy."""
    shapes = {
        "model.embed_tokens.weight": (8, 8),
        "model.norm.weight": (8,),
        "lm_head.weight": (8, 8),
        "model.layers.0.input_layernorm.weight": (8,),
        "model.layers.0.self_attn.q_proj.weight": (8, 8),
        "model.layers.0.self_attn.k_proj.weight": (4, 8),
        "model.layers.0.self_attn.v_proj.weight": (4, 8),
        "model.layers.0.self_attn.o_proj.weight": (8, 8),
        "model.layers.0.post_attention_layernorm.weight": (8,),
        "model.layers.0.mlp.gate_proj.weight": (12, 8),
        "model.layers.0.mlp.down_proj.weight": (8, 12),
        "model.layers.0.mlp.up_proj.weight": (12, 8),
    }
    tensors = {}
    for salt, (name, shape) in enumerate(shapes.items()):
        values = [1 + (i % 3 - 1) * 0.03 if len(shape) == 1 else
                  ((i * 37 + salt * 19) % 127 - 63) * 0.003 for i in range(math.prod(shape))]
        tensors[name] = {"shape": list(shape), "dtype": "F32", "values": values}
    return tensors


def encode_safetensors(tensors, metadata=None):
    header, payload = {}, bytearray()
    if metadata is not None:
        header["__metadata__"] = metadata
    for name, tensor in tensors.items():
        dtype, values = tensor["dtype"], tensor["values"]
        if dtype == "BF16":
            data = b"".join(struct.pack("<H", struct.unpack("<I", struct.pack("<f", value))[0] >> 16)
                            for value in values)
        else:
            # Unsupported dtype tests mutate the header after writing a valid payload.
            data = struct.pack("<" + ("f" if dtype == "F32" else "e") * len(values), *values)
        start = len(payload)
        payload.extend(data)
        header[name] = {"dtype": dtype, "shape": tensor["shape"], "data_offsets": [start, len(payload)]}
    return header, bytes(payload)


def write_safetensors(path, header, payload):
    encoded = json.dumps(header, allow_nan=False, separators=(",", ":")).encode()
    # Safetensors headers may be padded with spaces to align the data section.
    encoded += b" " * ((-len(encoded)) % 8)
    path.write_bytes(struct.pack("<Q", len(encoded)) + encoded + payload)


def matrix_vector(flat, rows, columns, vector):
    return [sum(flat[row * columns + col] * vector[col] for col in range(columns)) for row in range(rows)]


def normalize(vector, weights, epsilon):
    factor = 1 / math.sqrt(sum(x * x for x in vector) / len(vector) + epsilon)
    return [x * factor * weight for x, weight in zip(vector, weights)]


def rotate_heads(vector, heads, position, theta):
    result = list(vector)
    width = len(vector) // heads
    for head in range(heads):
        for i in range(width // 2):
            a, b = head * width + i, head * width + i + width // 2
            angle = position / theta ** (2 * i / width)
            cosine, sine = math.cos(angle), math.sin(angle)
            result[a] = vector[a] * cosine - vector[b] * sine
            result[b] = vector[b] * cosine + vector[a] * sine
    return result


def reference_logits(weights, tokens):
    """Independent full-sequence Python equations, without reusing native/converter functions."""
    dim, hidden, heads, kv_heads = 8, 12, 2, 1
    head_dim = dim // heads
    epsilon, theta = 1e-5, 10000
    prefix = "model.layers.0."
    states = [weights["model.embed_tokens.weight"][token * dim:(token + 1) * dim] for token in tokens]
    queries, keys, values = [], [], []
    for position, state in enumerate(states):
        normalized = normalize(state, weights[prefix + "input_layernorm.weight"], epsilon)
        query = matrix_vector(weights[prefix + "self_attn.q_proj.weight"], dim, dim, normalized)
        key = matrix_vector(weights[prefix + "self_attn.k_proj.weight"], kv_heads * head_dim, dim, normalized)
        value = matrix_vector(weights[prefix + "self_attn.v_proj.weight"], kv_heads * head_dim, dim, normalized)
        queries.append(rotate_heads(query, heads, position, theta))
        keys.append(rotate_heads(key, kv_heads, position, theta))
        values.append(value)
    result = []
    for position, state in enumerate(states):
        attended = []
        for head in range(heads):
            kv_head = head // (heads // kv_heads)
            query = queries[position][head * head_dim:(head + 1) * head_dim]
            scores = [sum(query[i] * keys[t][kv_head * head_dim + i] for i in range(head_dim)) / math.sqrt(head_dim)
                      for t in range(position + 1)]
            maximum = max(scores)
            probabilities = [math.exp(score - maximum) for score in scores]
            total = sum(probabilities)
            attended.extend(sum(probabilities[t] / total * values[t][kv_head * head_dim + i]
                                for t in range(position + 1)) for i in range(head_dim))
        attention_output = matrix_vector(weights[prefix + "self_attn.o_proj.weight"], dim, dim, attended)
        state = [x + y for x, y in zip(state, attention_output)]
        normalized = normalize(state, weights[prefix + "post_attention_layernorm.weight"], epsilon)
        gate = matrix_vector(weights[prefix + "mlp.gate_proj.weight"], hidden, dim, normalized)
        up = matrix_vector(weights[prefix + "mlp.up_proj.weight"], hidden, dim, normalized)
        activated = [g / (1 + math.exp(-g)) * u for g, u in zip(gate, up)]
        down = matrix_vector(weights[prefix + "mlp.down_proj.weight"], dim, hidden, activated)
        final = normalize([x + y for x, y in zip(state, down)], weights["model.norm.weight"], epsilon)
        result.append(matrix_vector(weights["lm_head.weight"], 8, dim, final))
    return result[-1]


class ConverterTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="sibalsa-converter-test-")
        self.folder = Path(self.temporary.name) / "checkpoint"
        self.folder.mkdir()
        self.output = Path(self.temporary.name) / "converted"
        self.config = {
            "model_type": "llama", "hidden_act": "silu", "hidden_size": 8,
            "intermediate_size": 12, "num_attention_heads": 2, "num_key_value_heads": 1,
            "num_hidden_layers": 1, "vocab_size": 8, "max_position_embeddings": 32,
            "rope_theta": 10000.0, "rms_norm_eps": 1e-5, "bos_token_id": 1,
            "eos_token_id": 2, "tie_word_embeddings": False,
        }
        self.tokenizer = {
            "version": "1.0", "pre_tokenizer": {"type": "ByteLevel", "add_prefix_space": False,
                                                 "trim_offsets": True, "use_regex": False},
            "decoder": {"type": "ByteLevel", "add_prefix_space": False, "trim_offsets": True, "use_regex": False},
            "model": {"type": "BPE", "vocab": {str(i): i for i in range(8)}, "merges": []},
            "added_tokens": [],
        }
        self.tensors = original_tensors()
        self.write_inputs()

    def tearDown(self):
        self.temporary.cleanup()

    def write_inputs(self, metadata=None):
        (self.folder / "config.json").write_text(json.dumps(self.config))
        (self.folder / "tokenizer.json").write_text(json.dumps(self.tokenizer))
        header, payload = encode_safetensors(self.tensors, metadata)
        write_safetensors(self.folder / "model.safetensors", header, payload)

    def convert(self, quantize=False, output=None):
        destination = output or self.output
        with contextlib.redirect_stdout(io.StringIO()):
            CONVERTER.convert(self.folder, destination, quantize, 24, 2, "prompt:", "reply:")
        return destination

    def native(self, command, path, *args):
        if not NATIVE.is_file():
            self.skipTest("Build .build/native-host/sibalsa_native_cli or set SIBALSA_NATIVE_CLI")
        completed = subprocess.run([str(NATIVE), command, str(path), *args], text=True, capture_output=True, timeout=20)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        return completed.stdout.strip()

    def source_values(self):
        # Interpret our encoded source values independently of the converter reader.
        values = {}
        for name, tensor in self.tensors.items():
            dtype = tensor["dtype"]
            if dtype == "F32":
                values[name] = [struct.unpack("<f", struct.pack("<f", x))[0] for x in tensor["values"]]
            elif dtype == "F16":
                values[name] = [struct.unpack("<e", struct.pack("<e", x))[0] for x in tensor["values"]]
            else:
                values[name] = [struct.unpack("<f", struct.pack("<I", struct.unpack("<I", struct.pack("<f", x))[0] & 0xffff0000))[0]
                                for x in tensor["values"]]
        return values

    def test_float_header_manifest_digest_and_native_numerics(self):
        output = self.convert()
        model = output / "model.sbm"
        raw = model.read_bytes()
        fields = struct.unpack_from("<8s10I2fQ", raw)
        self.assertEqual(fields[:2], (b"SIBLLM01", 1))
        self.assertGreaterEqual(fields[2], 64 + 80 * 12, "headerBytes must cover the descriptor table")
        self.assertEqual(fields[3:12], (8, 12, 2, 1, 1, 8, 24, 12, 10000.0))
        self.assertAlmostEqual(fields[12], 1e-5, places=11)
        self.assertEqual(fields[13], 0)
        manifest = json.loads((output / "integration.json").read_text())
        self.assertEqual(manifest["model_id"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(manifest["dimension"], 2 * (8 + 8))
        self.assertEqual(manifest["adapter_spec"], "lm_head.lora.v1.r2.d8.v8")
        self.assertEqual((manifest["prompt_prefix"], manifest["prompt_suffix"]), ("prompt:", "reply:"))
        self.assertEqual((output / "tokenizer.json").read_bytes(), (self.folder / "tokenizer.json").read_bytes())
        info = json.loads(self.native("info", model))
        self.assertEqual(info["model_sha256"], manifest["model_id"])
        weights = self.source_values()
        for tokens in ([1], [1, 3], [1, 3, 5, 2]):
            with self.subTest(tokens=tokens):
                actual = [float(x) for x in self.native("logits", model, ",".join(map(str, tokens))).split(",")]
                expected = reference_logits(weights, tokens)
                self.assertEqual(len(actual), len(expected))
                for a, b in zip(actual, expected):
                    self.assertAlmostEqual(a, b, delta=3e-5)

    def test_int8_payloads_and_native_numerics(self):
        output = self.convert(quantize=True)
        raw = (output / "model.sbm").read_bytes()
        tensor_count = struct.unpack_from("<I", raw, 44)[0]
        self.assertEqual(tensor_count, 12)
        sources = list(self.tensors)
        # Converter descriptor order deliberately differs from source insertion order.
        mapping = {"tok_embeddings": "model.embed_tokens.weight", "final_norm": "model.norm.weight", "output": "lm_head.weight"}
        short = {"attn_norm": "input_layernorm.weight", "ffn_norm": "post_attention_layernorm.weight",
                 "wq": "self_attn.q_proj.weight", "wk": "self_attn.k_proj.weight", "wv": "self_attn.v_proj.weight",
                 "wo": "self_attn.o_proj.weight", "w1": "mlp.gate_proj.weight", "w2": "mlp.down_proj.weight", "w3": "mlp.up_proj.weight"}
        mapping.update({"layers.0." + name: "model.layers.0." + source for name, source in short.items()})
        reference = self.source_values()
        for i in range(tensor_count):
            name, rows, cols, dtype, reserved, offset, length = struct.unpack_from("<48s4I2Q", raw, 64 + 80 * i)
            name = name.split(b"\0", 1)[0].decode()
            self.assertEqual(reserved, 0)
            self.assertEqual(offset % 4, 0)
            source = mapping[name]
            self.assertIn(source, sources)
            if len(self.tensors[source]["shape"]) == 1:
                self.assertEqual(dtype, 0, "norm weights remain float32")
                continue
            self.assertEqual(dtype, 1)
            self.assertEqual(length, rows * 4 + rows * cols)
            scales = struct.unpack_from("<" + "f" * rows, raw, offset)
            quantized = struct.unpack_from("<" + "b" * (rows * cols), raw, offset + rows * 4)
            expected_values = reference[source]
            restored = []
            for row in range(rows):
                self.assertTrue(math.isfinite(scales[row]) and scales[row] > 0)
                scale = max(abs(x) for x in expected_values[row * cols:(row + 1) * cols]) / 127
                for col in range(cols):
                    q = max(-127, min(127, round(expected_values[row * cols + col] / scale)))
                    self.assertEqual(quantized[row * cols + col], q)
                    restored.append(q * scales[row])
            reference[source] = restored
        self.native("info", output / "model.sbm")
        actual = [float(x) for x in self.native("logits", output / "model.sbm", "1,3,5,2").split(",")]
        for a, b in zip(actual, reference_logits(reference, [1, 3, 5, 2])):
            self.assertAlmostEqual(a, b, delta=3e-5)

    def test_f16_bf16_and_tied_embedding_conversion(self):
        for dtype in ("F16", "BF16"):
            with self.subTest(dtype=dtype):
                for tensor in self.tensors.values():
                    tensor["dtype"] = dtype
                self.config["tie_word_embeddings"] = True
                self.tensors.pop("lm_head.weight", None)
                self.write_inputs()
                output = self.convert(output=Path(self.temporary.name) / dtype)
                reference = self.source_values()
                reference["lm_head.weight"] = reference["model.embed_tokens.weight"]
                logits = [float(x) for x in self.native("logits", output / "model.sbm", "1,3,2").split(",")]
                for a, b in zip(logits, reference_logits(reference, [1, 3, 2])):
                    self.assertAlmostEqual(a, b, delta=3e-5)

    def test_reject_unsupported_configuration(self):
        cases = {
            "other_architecture": {"model_type": "qwen2"}, "other_activation": {"hidden_act": "gelu"},
            "attention_bias": {"attention_bias": True}, "mlp_bias": {"mlp_bias": True},
            "partial_rotary": {"partial_rotary_factor": 0.5}, "scaled_rotary": {"rope_scaling": {"rope_type": "linear", "factor": 2}},
            "custom_head_dim": {"head_dim": 8}, "sliding_window": {"sliding_window": 4},
            "qk_normalization": {"qk_norm": True}, "zero_layers": {"num_hidden_layers": 0},
            "zero_heads": {"num_attention_heads": 0}, "noninteger_dim": {"hidden_size": 8.0},
            "nan_rope": {"rope_theta": float("nan")}, "zero_epsilon": {"rms_norm_eps": 0},
            "bad_bos": {"bos_token_id": 9}, "bad_eos": {"eos_token_id": [2, 9]},
        }
        original = dict(self.config)
        for name, change in cases.items():
            with self.subTest(name=name):
                self.config = {**original, **change}
                self.write_inputs()
                with self.assertRaises(ValueError):
                    self.convert(output=Path(self.temporary.name) / name)

    def test_reject_extra_architecture_tensors_and_biases(self):
        for name in ("model.layers.0.self_attn.q_proj.bias", "model.layers.0.self_attn.q_norm.weight"):
            with self.subTest(name=name):
                self.tensors = original_tensors()
                self.tensors[name] = {"shape": [4], "dtype": "F32", "values": [1.0] * 4}
                self.write_inputs()
                with self.assertRaises(ValueError):
                    self.convert(output=Path(self.temporary.name) / name)

    def test_reject_missing_or_wrong_shape(self):
        self.tensors.pop("model.layers.0.self_attn.k_proj.weight")
        self.write_inputs()
        with self.assertRaises(ValueError):
            self.convert()
        self.tensors = original_tensors()
        self.tensors["model.layers.0.mlp.gate_proj.weight"]["shape"] = [8, 12]
        self.write_inputs()
        with self.assertRaises(ValueError):
            self.convert()

    def test_reject_malformed_safetensors_header_dtype_offsets_metadata(self):
        original_header, payload = encode_safetensors(self.tensors)
        cases = []
        header = copy.deepcopy(original_header); header["model.norm.weight"]["dtype"] = "I8"; cases.append(("dtype", header))
        header = copy.deepcopy(original_header); header["model.norm.weight"]["shape"] = [0]; cases.append(("shape", header))
        header = copy.deepcopy(original_header); header["model.norm.weight"]["data_offsets"] = [-1, 31]; cases.append(("offset", header))
        header = copy.deepcopy(original_header); header["lm_head.weight"]["data_offsets"] = header["model.embed_tokens.weight"]["data_offsets"]; cases.append(("overlap", header))
        header = copy.deepcopy(original_header); header["__metadata__"] = ["bad"]; cases.append(("metadata_shape", header))
        header = copy.deepcopy(original_header); header["__metadata__"] = {"format": 123}; cases.append(("metadata_type", header))
        cases.append(("header_not_object", []))
        for name, header in cases:
            with self.subTest(name=name):
                write_safetensors(self.folder / "model.safetensors", header, payload)
                with self.assertRaises(ValueError):
                    self.convert(output=Path(self.temporary.name) / name)
        for malformed in (b"", b"SIB", struct.pack("<Q", 2**63) + b"{}"):
            (self.folder / "model.safetensors").write_bytes(malformed)
            with self.assertRaises(ValueError):
                self.convert()

    def test_reject_duplicate_json_tensor_keys_and_duplicate_shard_tensors(self):
        header, payload = encode_safetensors(self.tensors)
        text = json.dumps(header)
        duplicated = text[:-1] + ',"model.norm.weight":' + json.dumps(header["model.norm.weight"]) + "}"
        encoded = duplicated.encode()
        (self.folder / "model.safetensors").write_bytes(struct.pack("<Q", len(encoded)) + encoded + payload)
        with self.assertRaises(ValueError):
            self.convert()
        self.write_inputs()
        write_safetensors(self.folder / "duplicate.safetensors", header, payload)
        with self.assertRaises(ValueError):
            self.convert()

    def test_reject_nonfinite_later_row_and_remove_partial_output(self):
        self.tensors["model.embed_tokens.weight"]["values"][9] = float("nan")
        self.write_inputs()
        with self.assertRaises(ValueError):
            self.convert()
        self.assertFalse(self.output.exists(), "failed conversion must not publish partial artifacts")

    def test_reject_non_bytelevel_bpe_and_remove_partial_output(self):
        self.tokenizer["pre_tokenizer"] = {"type": "Whitespace"}
        self.write_inputs()
        with self.assertRaises(ValueError):
            self.convert()
        self.assertFalse(self.output.exists(), "unsupported tokenizer must not leave a partial output")

    def test_int8_zero_and_subnormal_rows_keep_positive_scales(self):
        values = self.tensors["model.embed_tokens.weight"]["values"]
        values[0:8] = [0.0] * 8
        values[8:16] = [2**-149] * 8
        self.write_inputs()
        output = self.convert(quantize=True)
        self.native("info", output / "model.sbm")


if __name__ == "__main__":
    unittest.main()
