// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

final class Checks {
    private Checks() {}
    static int range(String name, int value, int min, int max) {
        if (value < min || value > max) throw new IllegalArgumentException(name + " out of range");
        return value;
    }
    static double finite(String name, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(name + " out of range");
        return value;
    }
    static String text(String name, String value, int max, boolean empty) {
        if (value == null || value.length() > max || (!empty && value.isEmpty()))
            throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
