package dev.project516.frcdevwasm.shim.vision.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Parses and dumps the small JSON documents cscore uses for source and sink configuration. */
final class MiniJson {
  private final String s;
  private int pos;

  private MiniJson(String s) {
    this.s = s;
  }

  /** Returns Map, List, String, Double, Long, Boolean or null. Throws IllegalArgumentException. */
  static Object parse(String text) {
    MiniJson p = new MiniJson(text);
    Object v = p.value();
    p.skipWs();
    if (p.pos != text.length()) {
      throw p.error();
    }
    return v;
  }

  private IllegalArgumentException error() {
    return new IllegalArgumentException("bad JSON at " + pos);
  }

  private void skipWs() {
    while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
      pos++;
    }
  }

  private Object value() {
    skipWs();
    if (pos >= s.length()) {
      throw error();
    }
    char c = s.charAt(pos);
    if (c == '{') {
      return object();
    }
    if (c == '[') {
      return array();
    }
    if (c == '"') {
      return string();
    }
    if (s.startsWith("true", pos)) {
      pos += 4;
      return Boolean.TRUE;
    }
    if (s.startsWith("false", pos)) {
      pos += 5;
      return Boolean.FALSE;
    }
    if (s.startsWith("null", pos)) {
      pos += 4;
      return null;
    }
    return number();
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new TreeMap<>();
    pos++;
    skipWs();
    if (peek() == '}') {
      pos++;
      return m;
    }
    while (true) {
      skipWs();
      if (peek() != '"') {
        throw error();
      }
      String k = string();
      skipWs();
      if (peek() != ':') {
        throw error();
      }
      pos++;
      m.put(k, value());
      skipWs();
      char c = peek();
      pos++;
      if (c == '}') {
        return m;
      }
      if (c != ',') {
        throw error();
      }
    }
  }

  private List<Object> array() {
    List<Object> l = new ArrayList<>();
    pos++;
    skipWs();
    if (peek() == ']') {
      pos++;
      return l;
    }
    while (true) {
      l.add(value());
      skipWs();
      char c = peek();
      pos++;
      if (c == ']') {
        return l;
      }
      if (c != ',') {
        throw error();
      }
    }
  }

  private char peek() {
    if (pos >= s.length()) {
      throw error();
    }
    return s.charAt(pos);
  }

  private String string() {
    StringBuilder b = new StringBuilder();
    pos++;
    while (true) {
      char c = peek();
      pos++;
      if (c == '"') {
        return b.toString();
      }
      if (c != '\\') {
        b.append(c);
        continue;
      }
      char e = peek();
      pos++;
      switch (e) {
        case 'n' -> b.append('\n');
        case 't' -> b.append('\t');
        case 'r' -> b.append('\r');
        case 'b' -> b.append('\b');
        case 'f' -> b.append('\f');
        case 'u' -> {
          if (pos + 4 > s.length()) {
            throw error();
          }
          b.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
          pos += 4;
        }
        default -> b.append(e);
      }
    }
  }

  private Object number() {
    int start = pos;
    while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
      pos++;
    }
    String t = s.substring(start, pos);
    try {
      if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
        return Double.valueOf(t);
      }
      return Long.valueOf(t);
    } catch (NumberFormatException ex) {
      pos = start;
      throw error();
    }
  }

  /** Dumps with 4-space indentation like wpi::json::dump(4). Maps must be sorted. */
  static String dump(Object v) {
    StringBuilder b = new StringBuilder();
    dump(b, v, 0);
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static void dump(StringBuilder b, Object v, int depth) {
    if (v instanceof Map<?, ?> m) {
      if (m.isEmpty()) {
        b.append("{}");
        return;
      }
      b.append("{\n");
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
        if (!first) {
          b.append(",\n");
        }
        first = false;
        indent(b, depth + 1);
        quote(b, e.getKey());
        b.append(": ");
        dump(b, e.getValue(), depth + 1);
      }
      b.append('\n');
      indent(b, depth);
      b.append('}');
    } else if (v instanceof List<?> l) {
      if (l.isEmpty()) {
        b.append("[]");
        return;
      }
      b.append("[\n");
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) {
          b.append(",\n");
        }
        indent(b, depth + 1);
        dump(b, l.get(i), depth + 1);
      }
      b.append('\n');
      indent(b, depth);
      b.append(']');
    } else if (v instanceof String str) {
      quote(b, str);
    } else {
      b.append(v);
    }
  }

  private static void indent(StringBuilder b, int depth) {
    for (int i = 0; i < depth * 4; i++) {
      b.append(' ');
    }
  }

  private static void quote(StringBuilder b, String str) {
    b.append('"');
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    b.append('"');
  }
}
