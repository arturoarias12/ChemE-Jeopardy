/*
 * File: Json.java
 * Description: Small JSON parser and serializer used to avoid external dependencies.
 * Author: Arturo Arias
 * Last updated: 2026-05-04
 */
package com.chemejeopardy.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON utility for the data shapes used by the game definition and API payloads.
 *
 * <p>The nested Parser is a recursive-descent parser: each method is responsible
 * for one JSON grammar rule, such as object, array, string, literal, or number.</p>
 */
public final class Json {
    /** Utility class constructor kept private to prevent instantiation. */
    private Json() {
    }

    /**
     * Serializes Java Maps, Lists, Strings, Numbers, Booleans, and null to JSON.
     */
    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value);
        return builder.toString();
    }

    /**
     * Parses JSON text into Java Maps, Lists, Strings, Numbers, Booleans, and null.
     */
    public static Object parse(String text) {
        return new Parser(text).parse();
    }

    /**
     * Safely casts a parsed value to an object map.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    /**
     * Safely casts a parsed value to a list.
     */
    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : new ArrayList<>();
    }

    /**
     * Reads a string with a fallback for missing values.
     */
    public static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    /**
     * Reads an integer with a fallback for missing or malformed values.
     */
    public static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Reads a long with a fallback for missing or malformed values.
     */
    public static long asLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Reads a boolean with a fallback for missing or malformed values.
     */
    public static boolean asBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        return fallback;
    }

    /**
     * Dispatches serialization based on runtime value type.
     */
    private static void writeValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String text) {
            writeString(builder, text);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeString(builder, String.valueOf(entry.getKey()));
                builder.append(':');
                writeValue(builder, entry.getValue());
            }
            builder.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                writeValue(builder, item);
            }
            builder.append(']');
            return;
        }
        writeString(builder, String.valueOf(value));
    }

    /**
     * Serializes and escapes a JSON string.
     */
    private static void writeString(StringBuilder builder, String text) {
        builder.append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 32) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }

    /**
     * Parser object stores input text and current cursor, encapsulating parse state.
     */
    private static final class Parser {
        /** Source JSON text. */
        private final String text;

        /** Current character offset inside text. */
        private int index;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        /**
         * Parses one complete JSON document.
         */
        private Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("Unexpected trailing JSON content at index " + index);
            }
            return value;
        }

        /**
         * Parses any JSON value.
         */
        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char current = text.charAt(index);
            return switch (current) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        /**
         * Parses a JSON object into insertion-ordered map form.
         */
        private Map<String, Object> parseObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    break;
                }
                expect(',');
            }
            return object;
        }

        /**
         * Parses a JSON array into list form.
         */
        private List<Object> parseArray() {
            List<Object> array = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    break;
                }
                expect(',');
            }
            return array;
        }

        /**
         * Parses a quoted JSON string with escape handling.
         */
        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current == '\\') {
                    if (index >= text.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence");
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> builder.append(escaped);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (index + 4 > text.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = text.substring(index, index + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported escape sequence: \\" + escaped);
                    }
                } else {
                    builder.append(current);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        /**
         * Parses a known JSON literal such as true, false, or null.
         */
        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected literal " + literal + " at index " + index);
            }
            index += literal.length();
            return value;
        }

        /**
         * Parses an integer or decimal number.
         */
        private Number parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                decimal = true;
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String raw = text.substring(start, index);
            if (raw.isBlank()) {
                throw new IllegalArgumentException("Invalid JSON number at index " + start);
            }
            try {
                if (decimal) {
                    return Double.parseDouble(raw);
                }
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid JSON number: " + raw, ex);
            }
        }

        /**
         * Consumes the expected character or fails with context.
         */
        private void expect(char expected) {
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at index " + index);
            }
            index++;
        }

        /**
         * Peeks at the current character without consuming it.
         */
        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        /**
         * Advances past whitespace between JSON tokens.
         */
        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
