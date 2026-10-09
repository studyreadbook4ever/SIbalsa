// SPDX-License-Identifier: Unlicense
package org.sibalsa.example;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sibalsa.core.ModelSpec;
import org.sibalsa.core.Tokenizer;
import org.sibalsa.nativeengine.ByteBpeTokenizer;
import org.sibalsa.nativeengine.ByteTokenizer;

/** Integrator-owned files, never assets in the public APK. Fixed filenames prevent ZIP traversal. */
final class ModelBundle {
    final File directory, model;
    final ModelSpec spec;
    final Tokenizer tokenizer;
    final int rank, context, bos;
    final int[] eos;
    final String prefix, suffix;
    final long seed;
    final float beta, clip;
    ModelBundle(File directory) throws Exception {
        this.directory = directory;
        JSONObject config = new JSONObject(read(new File(directory, "integration.json"), 65536));
        if (config.getInt("format_version") != 1 || !"model.sbm".equals(config.getString("model_file")))
            throw new IllegalArgumentException("Unsupported model integration manifest");
        model = new File(directory, "model.sbm");
        String digest = sha256(model);
        if (!digest.equals(config.getString("model_id"))) throw new IllegalArgumentException("Model SHA256 mismatch");
        rank = config.getInt("rank"); context = config.getInt("max_context"); bos = config.optInt("bos_token_id", -1);
        if (rank < 1 || rank > 64 || context < 2 || context > 32768) throw new IllegalArgumentException("Invalid rank/context");
        JSONArray ends = config.getJSONArray("eos_token_ids");
        eos = new int[ends.length()]; for (int i = 0; i < eos.length; i++) eos[i] = ends.getInt(i);
        prefix = config.optString("prompt_prefix", ""); suffix = config.optString("prompt_suffix", "");
        if (prefix.length() + suffix.length() > 32768) throw new IllegalArgumentException("Prompt template too large");
        seed = config.optLong("seed", 42); beta = (float) config.optDouble("dpo_beta", 0.1); clip = (float) config.optDouble("gradient_clip", 1.0);
        spec = new ModelSpec(digest, config.getString("adapter_spec"), config.getInt("dimension"));
        String type = config.getString("tokenizer_type");
        if ("byte".equals(type)) tokenizer = new ByteTokenizer();
        else if ("byte_bpe".equals(type) && "tokenizer.json".equals(config.getString("tokenizer_file")))
            tokenizer = new ByteBpeTokenizer(new JSONObject(read(new File(directory, "tokenizer.json"), 64 * 1024 * 1024)));
        else throw new IllegalArgumentException("Supply a custom Tokenizer for this model");
    }
    static File importZip(Context context, Uri uri) throws Exception {
        File stage = new File(context.getFilesDir(), "model-import-" + System.nanoTime());
        if (!stage.mkdir()) throw new IllegalStateException("Cannot create private import directory");
        Set<String> found = new HashSet<>();
        long total = 0;
        try (InputStream source = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !("model.sbm".equals(name) || "tokenizer.json".equals(name) || "integration.json".equals(name)) || !found.add(name))
                    throw new IllegalArgumentException("Unexpected or duplicate bundle entry");
                long limit = "model.sbm".equals(name) ? 8L * 1024 * 1024 * 1024 : 64L * 1024 * 1024;
                long written = 0;
                try (FileOutputStream output = new FileOutputStream(new File(stage, name))) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        written += count; total += count;
                        if (written > limit || total > 9L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Bundle too large");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
            }
            if (!found.contains("model.sbm") || !found.contains("integration.json")) throw new IllegalArgumentException("Incomplete model bundle");
            new ModelBundle(stage); // Validate before changing the active integration.
            return stage;
        } catch (Exception failure) { delete(stage); throw failure; }
    }
    static String read(File file, int maximum) throws Exception {
        if (!file.isFile() || file.length() > maximum) throw new IllegalArgumentException("Missing/oversized integration file");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int size;
            while ((size = input.read(block)) != -1) { if (output.size() + size > maximum) throw new IllegalArgumentException("Integration file too large"); output.write(block, 0, size); }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }
    static void delete(File directory) {
        File[] contents = directory.listFiles();
        if (contents != null) for (File file : contents) { if (file.isDirectory()) delete(file); else file.delete(); }
        directory.delete();
    }
}
