package com.codex.lockertest.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/** A dependency-free JSON parser that preserves number tokens and rejects ambiguity. */
public final class StrictJson {
    private static final int MAX_COMPOSITE_DEPTH = 32;

    private StrictJson() { }

    public static Object parse(String json) {
        if (json == null) throw JsonContractException.invalidJson();
        Parser parser = new Parser(json);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) throw JsonContractException.invalidJson();
        return value;
    }

    private static final class Parser {
        private final String source;
        private int index;

        Parser(String source) {
            this.source = source;
        }

        Object parseValue(int parentDepth) {
            skipWhitespace();
            if (atEnd()) throw JsonContractException.invalidJson();
            char character = source.charAt(index);
            if (character == '{') return parseObject(nextDepth(parentDepth));
            if (character == '[') return parseArray(nextDepth(parentDepth));
            if (character == '"') return parseString();
            if (character == 't') return parseLiteral("true", Boolean.TRUE);
            if (character == 'f') return parseLiteral("false", Boolean.FALSE);
            if (character == 'n') return parseLiteral("null", null);
            if (character == '-' || isDigit(character)) return parseNumber();
            throw JsonContractException.invalidJson();
        }

        private LinkedHashMap<String, Object> parseObject(int depth) {
            index++;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (consume('}')) return result;
            while (true) {
                skipWhitespace();
                if (atEnd() || source.charAt(index) != '"') {
                    throw JsonContractException.invalidJson();
                }
                String key = parseString();
                if (result.containsKey(key)) throw JsonContractException.invalidJson();
                skipWhitespace();
                require(':');
                Object value = parseValue(depth);
                result.put(key, value);
                skipWhitespace();
                if (consume('}')) return result;
                require(',');
            }
        }

        private ArrayList<Object> parseArray(int depth) {
            index++;
            ArrayList<Object> result = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return result;
            while (true) {
                result.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) return result;
                require(',');
            }
        }

        private String parseString() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char character = source.charAt(index++);
                if (character == '"') return result.toString();
                if (character < 0x20) throw JsonContractException.invalidJson();
                if (character == '\\') {
                    appendEscaped(result);
                } else if (Character.isHighSurrogate(character)) {
                    appendRawSurrogatePair(result, character);
                } else if (Character.isLowSurrogate(character)) {
                    throw JsonContractException.invalidJson();
                } else {
                    result.append(character);
                }
            }
            throw JsonContractException.invalidJson();
        }

        private void appendEscaped(StringBuilder result) {
            if (atEnd()) throw JsonContractException.invalidJson();
            char escape = source.charAt(index++);
            switch (escape) {
                case '"': result.append('"'); return;
                case '\\': result.append('\\'); return;
                case '/': result.append('/'); return;
                case 'b': result.append('\b'); return;
                case 'f': result.append('\f'); return;
                case 'n': result.append('\n'); return;
                case 'r': result.append('\r'); return;
                case 't': result.append('\t'); return;
                case 'u':
                    char unicode = parseUnicodeCodeUnit();
                    if (Character.isHighSurrogate(unicode)) {
                        result.append(unicode);
                        result.append(parseRequiredLowSurrogate());
                        return;
                    }
                    if (Character.isLowSurrogate(unicode)) {
                        throw JsonContractException.invalidJson();
                    }
                    result.append(unicode);
                    return;
                default:
                    throw JsonContractException.invalidJson();
            }
        }

        private void appendRawSurrogatePair(StringBuilder result, char high) {
            if (atEnd()) throw JsonContractException.invalidJson();
            char low;
            if (source.charAt(index) == '\\') {
                index++;
                if (atEnd() || source.charAt(index++) != 'u') {
                    throw JsonContractException.invalidJson();
                }
                low = parseUnicodeCodeUnit();
            } else {
                low = source.charAt(index++);
            }
            if (!Character.isLowSurrogate(low)) throw JsonContractException.invalidJson();
            result.append(high).append(low);
        }

        private char parseRequiredLowSurrogate() {
            if (atEnd()) throw JsonContractException.invalidJson();
            char low;
            if (source.charAt(index) == '\\') {
                index++;
                if (atEnd() || source.charAt(index++) != 'u') {
                    throw JsonContractException.invalidJson();
                }
                low = parseUnicodeCodeUnit();
            } else {
                low = source.charAt(index++);
            }
            if (!Character.isLowSurrogate(low)) throw JsonContractException.invalidJson();
            return low;
        }

        private char parseUnicodeCodeUnit() {
            if (source.length() - index < 4) throw JsonContractException.invalidJson();
            int value = 0;
            for (int count = 0; count < 4; count++) {
                int hex = hexValue(source.charAt(index++));
                if (hex < 0) throw JsonContractException.invalidJson();
                value = (value << 4) | hex;
            }
            return (char) value;
        }

        private JsonNumber parseNumber() {
            int start = index;
            if (consume('-') && atEnd()) throw JsonContractException.invalidJson();
            if (consume('0')) {
                if (!atEnd() && isDigit(source.charAt(index))) {
                    throw JsonContractException.invalidJson();
                }
            } else {
                if (atEnd() || source.charAt(index) < '1' || source.charAt(index) > '9') {
                    throw JsonContractException.invalidJson();
                }
                while (!atEnd() && isDigit(source.charAt(index))) index++;
            }
            if (consume('.')) {
                if (atEnd() || !isDigit(source.charAt(index))) {
                    throw JsonContractException.invalidJson();
                }
                while (!atEnd() && isDigit(source.charAt(index))) index++;
            }
            if (!atEnd() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (!atEnd() && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                    index++;
                }
                if (atEnd() || !isDigit(source.charAt(index))) {
                    throw JsonContractException.invalidJson();
                }
                while (!atEnd() && isDigit(source.charAt(index))) index++;
            }
            return new JsonNumber(source.substring(start, index));
        }

        private Object parseLiteral(String literal, Object value) {
            if (source.length() - index < literal.length()
                    || !source.regionMatches(index, literal, 0, literal.length())) {
                throw JsonContractException.invalidJson();
            }
            index += literal.length();
            return value;
        }

        private int nextDepth(int parentDepth) {
            int depth = parentDepth + 1;
            if (depth > MAX_COMPOSITE_DEPTH) throw JsonContractException.invalidJson();
            return depth;
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char character = source.charAt(index);
                if (character != ' ' && character != '\t'
                        && character != '\n' && character != '\r') return;
                index++;
            }
        }

        boolean atEnd() {
            return index == source.length();
        }

        private boolean consume(char expected) {
            if (!atEnd() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) throw JsonContractException.invalidJson();
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }

        private static int hexValue(char character) {
            if (character >= '0' && character <= '9') return character - '0';
            if (character >= 'a' && character <= 'f') return character - 'a' + 10;
            if (character >= 'A' && character <= 'F') return character - 'A' + 10;
            return -1;
        }
    }
}
