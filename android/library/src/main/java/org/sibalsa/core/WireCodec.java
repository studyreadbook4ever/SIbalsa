// SPDX-License-Identifier: Unlicense
package org.sibalsa.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict JSON for protocol v1. Request schemas cannot contain prompts, responses or feedback. */
public final class WireCodec {
    private WireCodec() {}
    public static String encodeLease(AdapterSnapshot base) {
        return "{\"model_id\":\"" + base.model.modelId + "\",\"adapter_spec\":\"" + base.model.adapterSpec +
                "\",\"base_version\":" + base.baseVersion + ",\"dimension\":" + base.model.dimension + "}";
    }
    public static String encodeUpdate(UpdatePayload update) {
        StringBuilder out = new StringBuilder();
        out.append("{\"lease_id\":\"").append(update.lease.leaseId).append("\",\"nonce\":\"")
           .append(update.lease.nonce).append("\",\"proof\":\"").append(update.proof)
           .append("\",\"sample_count\":").append(update.sampleCount).append(",\"steps\":").append(update.steps)
           .append(",\"delta\":[");
        double[] delta = update.delta();
        for (int i = 0; i < delta.length; i++) {
            if (i != 0) out.append(',');
            out.append(Double.toString(delta[i]));
        }
        return out.append("]}").toString();
    }
    public static ServerPolicy decodePolicy(String json) {
        Map<String, Object> o = object(new Parser(json).parse());
        if (integer(o, "protocol_version") != 1) throw new IllegalArgumentException("Unsupported protocol");
        AdapterSnapshot snapshot = snapshot(o);
        return new ServerPolicy(snapshot, limits(object(required(o, "limits"))),
                intValue(o, "pow_bits"), integer(o, "lease_seconds"));
    }
    public static Lease decodeLease(String json, ModelSpec model) {
        Map<String, Object> o = object(new Parser(json).parse());
        return new Lease(string(o, "lease_id"), string(o, "nonce"), integer(o, "expires_at"),
                new AdapterSnapshot(model, integer(o, "base_version"), numbers(required(o, "adapter"), model.dimension)),
                limits(object(required(o, "limits"))), intValue(o, "pow_bits"));
    }
    public static AdapterSnapshot decodeAdapter(String json) { return snapshot(object(new Parser(json).parse())); }
    public static UpdateReceipt decodeReceipt(String json) {
        Map<String, Object> o = object(new Parser(json).parse());
        if (!Boolean.TRUE.equals(required(o, "accepted"))) throw new IllegalArgumentException("Update not accepted");
        Object candidate = required(o, "candidate_version");
        Long version = candidate == null ? null : integer(o, "candidate_version");
        return new UpdateReceipt(integer(o, "base_version"), intValue(o, "round_size"), version);
    }
    private static AdapterSnapshot snapshot(Map<String, Object> o) {
        ModelSpec model = new ModelSpec(string(o, "model_id"), string(o, "adapter_spec"), intValue(o, "dimension"));
        return new AdapterSnapshot(model, integer(o, "base_version"), numbers(required(o, "adapter"), model.dimension));
    }
    private static ServerLimits limits(Map<String, Object> o) {
        return new ServerLimits(intValue(o, "max_samples"), intValue(o, "max_steps"), number(o, "max_l2"),
                number(o, "max_coordinate"));
    }
    private static Object required(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) throw new IllegalArgumentException("Missing " + key);
        return map.get(key);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }
    private static String string(Map<String, Object> o, String key) {
        Object value = required(o, key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected string " + key);
        return (String) value;
    }
    private static long integer(Map<String, Object> o, String key) {
        Object value = required(o, key);
        if (!(value instanceof Numeric)) throw new IllegalArgumentException("Expected integer " + key);
        try { return Long.parseLong(((Numeric) value).text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer " + key, e); }
    }
    private static int intValue(Map<String, Object> o, String key) {
        long value = integer(o, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("Integer overflow");
        return (int) value;
    }
    private static double number(Map<String, Object> o, String key) {
        return number(required(o, key));
    }
    private static double number(Object value) {
        if (!(value instanceof Numeric)) throw new IllegalArgumentException("Expected JSON number");
        double result = Double.parseDouble(((Numeric) value).text);
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Nonfinite JSON number");
        return result;
    }
    private static double[] numbers(Object value, int dimension) {
        if (!(value instanceof List) || ((List<?>) value).size() != dimension)
            throw new IllegalArgumentException("Adapter dimension mismatch");
        List<?> values = (List<?>) value;
        double[] result = new double[dimension];
        for (int i = 0; i < dimension; i++) result[i] = number(values.get(i));
        return result;
    }
    private static final class Numeric {
        final String text;
        Numeric(String text) { this.text = text; }
    }
    private static final class Parser {
        private final String input;
        private int at;
        Parser(String input) {
            if (input == null || input.length() > 32 * 1024 * 1024) throw new IllegalArgumentException("Oversized JSON");
            this.input = input;
        }
        Object parse() {
            Object value = value(0);
            space();
            if (at != input.length()) fail();
            return value;
        }
        private Object value(int depth) {
            if (depth > 24) throw new IllegalArgumentException("JSON too deep");
            space();
            if (at >= input.length()) fail();
            char c = input.charAt(at);
            if (c == '{') return object(depth + 1);
            if (c == '[') return array(depth + 1);
            if (c == '"') return string();
            if (c == 't') { literal("true"); return Boolean.TRUE; }
            if (c == 'f') { literal("false"); return Boolean.FALSE; }
            if (c == 'n') { literal("null"); return null; }
            return numeric();
        }
        private Map<String, Object> object(int depth) {
            at++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            space();
            if (consume('}')) return result;
            do {
                space();
                if (at >= input.length() || input.charAt(at) != '"') fail();
                String key = string();
                space();
                if (!consume(':') || result.containsKey(key)) fail();
                result.put(key, value(depth));
                if (result.size() > 128) throw new IllegalArgumentException("Too many JSON fields");
                space();
                if (consume('}')) return result;
            } while (consume(','));
            fail();
            return result;
        }
        private List<Object> array(int depth) {
            at++;
            List<Object> result = new ArrayList<>();
            space();
            if (consume(']')) return result;
            do {
                result.add(value(depth));
                if (result.size() > 1_000_000) throw new IllegalArgumentException("Oversized JSON array");
                space();
                if (consume(']')) return result;
            } while (consume(','));
            fail();
            return result;
        }
        private String string() {
            at++;
            StringBuilder out = new StringBuilder();
            while (at < input.length()) {
                char c = input.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 32) fail();
                if (c == '\\') {
                    if (at >= input.length()) fail();
                    char escape = input.charAt(at++);
                    switch (escape) {
                        case '"': case '\\': case '/': c = escape; break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (at + 4 > input.length()) fail();
                            for (int i = 0; i < 4; i++) {
                                char hex = input.charAt(at + i);
                                if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') ||
                                        (hex >= 'A' && hex <= 'F'))) fail();
                            }
                            try { c = (char) Integer.parseInt(input.substring(at, at + 4), 16); }
                            catch (NumberFormatException e) { fail(); }
                            at += 4;
                            break;
                        default: fail();
                    }
                }
                out.append(c);
                if (out.length() > 65536) throw new IllegalArgumentException("Oversized JSON string");
            }
            fail();
            return "";
        }
        private Numeric numeric() {
            int start = at;
            consume('-');
            if (at >= input.length()) fail();
            if (!consume('0')) {
                if (input.charAt(at) < '1' || input.charAt(at) > '9') fail();
                digits();
            }
            if (consume('.')) {
                int previous = at;
                digits();
                if (at == previous) fail();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) consume('-');
                int previous = at;
                digits();
                if (at == previous) fail();
            }
            if (at - start > 64) throw new IllegalArgumentException("Oversized JSON number");
            return new Numeric(input.substring(start, at));
        }
        private void digits() {
            while (at < input.length() && input.charAt(at) >= '0' && input.charAt(at) <= '9') at++;
        }
        private void literal(String value) {
            if (!input.startsWith(value, at)) fail();
            at += value.length();
        }
        private boolean consume(char c) {
            if (at < input.length() && input.charAt(at) == c) { at++; return true; }
            return false;
        }
        private void space() {
            while (at < input.length()) {
                char c = input.charAt(at);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
                at++;
            }
        }
        private void fail() { throw new IllegalArgumentException("Invalid JSON at " + at); }
    }
}
