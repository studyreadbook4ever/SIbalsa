// SPDX-License-Identifier: Unlicense
package org.sibalsa.nativeengine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sibalsa.core.Tokenizer;

/** Original ByteLevel BPE implementation. Unsupported tokenizer features fail explicitly. */
public final class ByteBpeTokenizer implements Tokenizer {
    private static final String GPT_PATTERN = "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+";
    private final Map<String, Integer> vocabulary = new HashMap<>();
    private final Map<Integer, String> inverse = new HashMap<>();
    private final Map<String, Integer> merges = new HashMap<>();
    private final Map<String, Integer> special = new HashMap<>();
    private final List<String> specialOrder = new ArrayList<>();
    private final String[] byteToSymbol = new String[256];
    private final Map<Integer, Integer> symbolToByte = new HashMap<>();
    private final Pattern pattern;
    private final boolean addPrefixSpace;

    public ByteBpeTokenizer(JSONObject tokenizer) throws Exception {
        JSONObject model = tokenizer.getJSONObject("model");
        if (!"BPE".equals(model.getString("type")) || model.optBoolean("byte_fallback", false)
                || model.optDouble("dropout", 0) != 0 || !model.optString("continuing_subword_prefix", "").isEmpty()
                || !model.optString("end_of_word_suffix", "").isEmpty())
            throw new IllegalArgumentException("Only deterministic ByteLevel BPE is supported; supply another Tokenizer implementation");
        if (!tokenizer.isNull("normalizer") && tokenizer.has("normalizer"))
            throw new IllegalArgumentException("Tokenizer normalization requires a custom integration");
        JSONObject vocab = model.getJSONObject("vocab");
        for (Iterator<String> it = vocab.keys(); it.hasNext();) {
            String token = it.next();
            int id = vocab.getInt(token);
            if (id < 0 || inverse.put(id, token) != null) throw new IllegalArgumentException("Invalid vocabulary IDs");
            vocabulary.put(token, id);
        }
        JSONArray mergeArray = model.getJSONArray("merges");
        for (int i = 0; i < mergeArray.length(); i++) {
            Object entry = mergeArray.get(i);
            String left, right;
            if (entry instanceof JSONArray) {
                JSONArray pair = (JSONArray) entry;
                if (pair.length() != 2) throw new IllegalArgumentException("Invalid BPE merge");
                left = pair.getString(0); right = pair.getString(1);
            } else {
                String pair = (String) entry;
                int space = pair.indexOf(' ');
                if (space < 1) throw new IllegalArgumentException("Invalid BPE merge");
                left = pair.substring(0, space); right = pair.substring(space + 1);
            }
            if (merges.put(left + "\u0000" + right, i) != null) throw new IllegalArgumentException("Duplicate BPE merge");
        }
        int extra = 0;
        for (int value = 0; value < 256; value++) {
            boolean visible = (value >= 33 && value <= 126) || (value >= 161 && value <= 172) || value >= 174;
            int symbol = visible ? value : 256 + extra++;
            byteToSymbol[value] = new String(Character.toChars(symbol));
            symbolToByte.put(symbol, value);
        }
        JSONArray added = tokenizer.optJSONArray("added_tokens");
        if (added != null) for (int i = 0; i < added.length(); i++) {
            JSONObject entry = added.getJSONObject(i);
            if (entry.optBoolean("lstrip") || entry.optBoolean("rstrip") || entry.optBoolean("single_word"))
                throw new IllegalArgumentException("Added-token whitespace semantics require a custom integration");
            String token = entry.getString("content");
            int id = entry.getInt("id");
            if (token.isEmpty() || id < 0 || special.containsKey(token))
                throw new IllegalArgumentException("Invalid or duplicate added token");
            if (!entry.optBoolean("special", false))
                throw new IllegalArgumentException("Non-special added tokens require a custom integration");
            String existingToken = inverse.get(id);
            Integer existingId = vocabulary.get(token);
            if ((existingToken != null && !existingToken.equals(token))
                    || (existingId != null && existingId != id))
                throw new IllegalArgumentException("Added-token ID conflicts with vocabulary");
            special.put(token, id); inverse.put(id, token);
            specialOrder.add(token);
        }
        Collections.sort(specialOrder, (a, b) -> Integer.compare(b.length(), a.length()));
        JSONObject pre = tokenizer.getJSONObject("pre_tokenizer");
        String regex = GPT_PATTERN;
        boolean prefix = false;
        boolean byteLevel = false;
        JSONArray stages = "Sequence".equals(pre.getString("type")) ? pre.getJSONArray("pretokenizers") : new JSONArray().put(pre);
        if (stages.length() != 1 && stages.length() != 2)
            throw new IllegalArgumentException("Unsupported pre-tokenizer sequence");
        if (stages.length() == 1 && !"ByteLevel".equals(stages.getJSONObject(0).getString("type")))
            throw new IllegalArgumentException("A single ByteLevel stage is required");
        if (stages.length() == 2 && (!"Split".equals(stages.getJSONObject(0).getString("type"))
                || !"ByteLevel".equals(stages.getJSONObject(1).getString("type"))
                || stages.getJSONObject(1).optBoolean("use_regex", true)))
            throw new IllegalArgumentException("Only Split then ByteLevel(use_regex=false) is supported");
        for (int i = 0; i < stages.length(); i++) {
            JSONObject stage = stages.getJSONObject(i);
            if ("ByteLevel".equals(stage.getString("type"))) {
                byteLevel = true; prefix = stage.optBoolean("add_prefix_space", false);
                if (!stage.optBoolean("use_regex", true) && stages.length() == 1) regex = "(?s).+";
            } else if ("Split".equals(stage.getString("type")) && !stage.optBoolean("invert", false)
                    && "Isolated".equals(stage.getString("behavior"))) {
                regex = stage.getJSONObject("pattern").getString("Regex");
            } else throw new IllegalArgumentException("Unsupported pre-tokenizer: " + stage.getString("type"));
        }
        if (!byteLevel) throw new IllegalArgumentException("ByteLevel pre-tokenizer is required");
        // Android's ICU-backed regex uses Unicode character classes and rejects the JVM-only flag.
        pattern = Pattern.compile(regex);
        addPrefixSpace = prefix;
    }

    @Override public int[] encode(String text) {
        if (text.length() > 131072) throw new IllegalArgumentException("Tokenizer input too large");
        List<Integer> ids = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            String found = null;
            int position = text.length();
            for (String candidate : specialOrder) {
                int at = text.indexOf(candidate, start);
                if (at >= 0 && at < position) { found = candidate; position = at; }
            }
            if (position > start) encodeOrdinary(text.substring(start, position), ids);
            if (found == null) break;
            ids.add(special.get(found)); start = position + found.length();
        }
        int[] output = new int[ids.size()];
        for (int i = 0; i < output.length; i++) output[i] = ids.get(i);
        return output;
    }

    private void encodeOrdinary(String text, List<Integer> output) {
        if (addPrefixSpace && !text.startsWith(" ")) text = " " + text;
        Matcher match = pattern.matcher(text);
        int consumed = 0;
        while (match.find()) {
            if (match.start() != consumed) throw new IllegalArgumentException("Tokenizer regex left unmatched input");
            consumed = match.end();
            List<String> symbols = new ArrayList<>();
            for (byte value : match.group().getBytes(StandardCharsets.UTF_8)) symbols.add(byteToSymbol[value & 255]);
            while (symbols.size() > 1) {
                int best = Integer.MAX_VALUE, index = -1;
                for (int i = 0; i + 1 < symbols.size(); i++) {
                    Integer rank = merges.get(symbols.get(i) + "\u0000" + symbols.get(i + 1));
                    if (rank != null && rank < best) { best = rank; index = i; }
                }
                if (index < 0) break;
                symbols.set(index, symbols.get(index) + symbols.remove(index + 1));
            }
            for (String symbol : symbols) {
                Integer id = vocabulary.get(symbol);
                if (id == null) throw new IllegalArgumentException("Missing byte/BPE vocabulary entry");
                output.add(id);
            }
        }
        if (consumed != text.length()) throw new IllegalArgumentException("Tokenizer regex left unmatched input");
    }

    @Override public String decode(int[] tokens) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int token : tokens) {
            String symbols = inverse.get(token);
            if (symbols == null) throw new IllegalArgumentException("Unknown token ID");
            if (special.containsKey(symbols)) continue;
            for (int offset = 0; offset < symbols.length();) {
                int cp = symbols.codePointAt(offset); offset += Character.charCount(cp);
                Integer value = symbolToByte.get(cp);
                if (value == null) throw new IllegalArgumentException("Unsupported BPE decoder symbol");
                bytes.write(value);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
