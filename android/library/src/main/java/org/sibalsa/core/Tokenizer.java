// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

public interface Tokenizer {
    int[] encode(String text);
    String decode(int[] tokens);
}
