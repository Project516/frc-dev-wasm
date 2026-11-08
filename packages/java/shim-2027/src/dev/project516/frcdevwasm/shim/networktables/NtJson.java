package dev.project516.frcdevwasm.shim.networktables;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Small JSON reader and writer. Objects are sorted maps, integers are Long and
 * other numbers Double, JSON null is {@link #NULL}. dump() matches wpi::json.
 */
final class NtJson {
  static final Object NULL = new Object() {
    @Override
    public String toString() {
      return "null";
    }
  };

  static final class ParseException extends RuntimeException {
    ParseException(String msg) {
      super(msg);
    }
  }

  private final String s;
  private int pos;

  private NtJson(String s) {
    this.s = s;
  }

  static Object parse(String text) {
    NtJson p = new NtJson(text);
    p.skipWs();
    Object v = p.value();
    p.skipWs();
    if (p.pos != text.length()) {
      throw p.error("unexpected trailing characters");
    }
    return v;
  }

  private ParseException error(String msg) {
    return new ParseException(msg + " at position " + pos);
  }

  private void skipWs() {
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        pos++;
      } else {
        break;
      }
    }
  }

  private Object value() {
    if (pos >= s.length()) {
      throw error("unexpected end of input");
    }
    char c = s.charAt(pos);
    switch (c) {
      case '{':
        return object();
      case '[':
        return array();
      case '"':
        return string();
      case 't':
        return literal("true", Boolean.TRUE);
      case 'f':
        return literal("false", Boolean.FALSE);
      case 'n':
        return literal("null", NULL);
      default:
        return number();
    }
  }

  private Object literal(String word, Object result) {
    if (!s.startsWith(word, pos)) {
      throw error("invalid literal");
    }
    pos += word.length();
    return result;
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new TreeMap<>();
    pos++;
    skipWs();
    if (pos < s.length() && s.charAt(pos) == '}') {
      pos++;
      return m;
    }
    while (true) {
      skipWs();
      if (pos >= s.length() || s.charAt(pos) != '"') {
        throw error("expected string key");
      }
      String key = string();
      skipWs();
      if (pos >= s.length() || s.charAt(pos) != ':') {
        throw error("expected ':'");
      }
      pos++;
      skipWs();
      m.put(key, value());
      skipWs();
      if (pos >= s.length()) {
        throw error("unexpected end of input");
      }
      char c = s.charAt(pos++);
      if (c == '}') {
        return m;
      }
      if (c != ',') {
        throw error("expected ',' or '}'");
      }
    }
  }

  private List<Object> array() {
    List<Object> l = new ArrayList<>();
    pos++;
    skipWs();
    if (pos < s.length() && s.charAt(pos) == ']') {
      pos++;
      return l;
    }
    while (true) {
      skipWs();
      l.add(value());
      skipWs();
      if (pos >= s.length()) {
        throw error("unexpected end of input");
      }
      char c = s.charAt(pos++);
      if (c == ']') {
        return l;
      }
      if (c != ',') {
        throw error("expected ',' or ']'");
      }
    }
  }

  private String string() {
    StringBuilder sb = new StringBuilder();
    pos++;
    while (true) {
      if (pos >= s.length()) {
        throw error("unterminated string");
      }
      char c = s.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      if (pos >= s.length()) {
        throw error("unterminated string");
      }
      char e = s.charAt(pos++);
      switch (e) {
        case '"', '\\', '/' -> sb.append(e);
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 't' -> sb.append('\t');
        case 'u' -> {
          if (pos + 4 > s.length()) {
            throw error("bad unicode escape");
          }
          try {
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
          } catch (NumberFormatException ex) {
            throw error("bad unicode escape");
          }
          pos += 4;
        }
        default -> throw error("bad escape");
      }
    }
  }

  private Object number() {
    int start = pos;
    boolean isInt = true;
    if (pos < s.length() && s.charAt(pos) == '-') {
      pos++;
    }
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c >= '0' && c <= '9') {
        pos++;
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        isInt = false;
        pos++;
      } else {
        break;
      }
    }
    String t = s.substring(start, pos);
    if (t.isEmpty() || t.equals("-")) {
      throw error("invalid value");
    }
    try {
      if (isInt) {
        try {
          return Long.parseLong(t);
        } catch (NumberFormatException ex) {
          return Double.parseDouble(t);
        }
      }
      return Double.parseDouble(t);
    } catch (NumberFormatException ex) {
      pos = start;
      throw error("invalid number");
    }
  }

  static String dump(Object v) {
    StringBuilder sb = new StringBuilder();
    dump(v, sb);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  static void dump(Object v, StringBuilder sb) {
    if (v == null || v == NULL) {
      sb.append("null");
    } else if (v instanceof String str) {
      quote(str, sb);
    } else if (v instanceof Double dv) {
      sb.append(number(dv));
    } else if (v instanceof Float fv) {
      sb.append(number(fv.doubleValue()));
    } else if (v instanceof Number || v instanceof Boolean) {
      sb.append(v);
    } else if (v instanceof Map<?, ?> m) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        quote(e.getKey(), sb);
        sb.append(':');
        dump(e.getValue(), sb);
      }
      sb.append('}');
    } else if (v instanceof List<?> l) {
      sb.append('[');
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        dump(l.get(i), sb);
      }
      sb.append(']');
    } else {
      throw new IllegalArgumentException("not a JSON value: " + v.getClass());
    }
  }

  /** Double text like wpi::json: "1.0", "1.5", "1e-05". Non-finite becomes null. */
  static String number(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      return "null";
    }
    String t = Double.toString(d);
    int e = t.indexOf('E');
    if (e < 0) {
      return t;
    }
    int exp = Integer.parseInt(t.substring(e + 1));
    if (exp >= 0 && exp < 15) {
      String plain = new BigDecimal(t).toPlainString();
      return plain.contains(".") ? plain : plain + ".0";
    }
    String mant = t.substring(0, e);
    if (mant.endsWith(".0")) {
      mant = mant.substring(0, mant.length() - 2);
    }
    int abs = Math.abs(exp);
    return mant + "e" + (exp < 0 ? "-" : "+") + (abs < 10 ? "0" : "") + abs;
  }

  static void quote(String str, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }
}
