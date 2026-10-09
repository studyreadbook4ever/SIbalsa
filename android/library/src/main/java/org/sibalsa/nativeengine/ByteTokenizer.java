// SPDX-License-Identifier: Unlicense
package org.sibalsa.nativeengine;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import org.sibalsa.core.Tokenizer;

/** For a model explicitly trained with raw UTF-8 byte token IDs 0..255. Not interchangeable with BPE. */
public final class ByteTokenizer implements Tokenizer {
    @Override public int[] encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] ids = new int[bytes.length];
        for (int i = 0; i < ids.length; i++) ids[i] = bytes[i] & 255;
        return ids;
    }
    @Override public String decode(int[] ids) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int id : ids) if (id >= 0 && id <= 255) bytes.write(id);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
