package dev.project516.frcdevwasm.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON reader and writer. Objects parse to LinkedHashMap, arrays to ArrayList, integers to
 * Long, other numbers to Double. Avoids loading Jackson in the browser JVM.
 */
public final class Json {
  private final String s;
  private int pos;

  private Json(String s) {
    this.s = s;
  }

  public static Object parse(String text) {
    Json p = new Json(text);
    p.skipWs();
    Object v = p.value();
    p.skipWs();
    if (p.pos != text.length()) {
      throw p.error("trailing characters");
    }
    return v;
  }

  private IllegalArgumentException error(String msg) {
    return new IllegalArgumentException("JSON " + msg + " at " + pos);
  }

  private void skipWs() {
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
        break;
      }
      pos++;
    }
  }

  private Object value() {
    if (pos >= s.length()) {
      throw error("unexpected end");
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
        expect("true");
        return Boolean.TRUE;
      case 'f':
        expect("false");
        return Boolean.FALSE;
      case 'n':
        expect("null");
        return null;
      default:
        return number();
    }
  }

  private void expect(String word) {
    if (!s.startsWith(word, pos)) {
      throw error("unexpected token");
    }
    pos += word.length();
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<>();
    pos++;
    skipWs();
    if (s.charAt(pos) == '}') {
      pos++;
      return m;
    }
    while (true) {
      skipWs();
      if (s.charAt(pos) != '"') {
        throw error("expected key");
      }
      String k = string();
      skipWs();
      if (s.charAt(pos++) != ':') {
        throw error("expected ':'");
      }
      skipWs();
      m.put(k, value());
      skipWs();
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
    if (s.charAt(pos) == ']') {
      pos++;
      return l;
    }
    while (true) {
      skipWs();
      l.add(value());
      skipWs();
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
    pos++;
    StringBuilder sb = null;
    int start = pos;
    while (true) {
      if (pos >= s.length()) {
        throw error("unterminated string");
      }
      char c = s.charAt(pos);
      if (c == '"') {
        String out = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
        pos++;
        return out;
      }
      if (c == '\\') {
        if (sb == null) {
          sb = new StringBuilder();
        }
        sb.append(s, start, pos);
        char e = s.charAt(pos + 1);
        pos += 2;
        switch (e) {
          case 'n' -> sb.append('\n');
          case 't' -> sb.append('\t');
          case 'r' -> sb.append('\r');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'u' -> {
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> sb.append(e);
        }
        start = pos;
      } else {
        pos++;
      }
    }
  }

  private Object number() {
    int start = pos;
    boolean integer = true;
    while (pos < s.length()) {
      char c = s.charAt(pos);
      if (c == '.' || c == 'e' || c == 'E') {
        integer = false;
      } else if (!(c >= '0' && c <= '9') && c != '-' && c != '+') {
        break;
      }
      pos++;
    }
    if (start == pos) {
      throw error("unexpected character");
    }
    String t = s.substring(start, pos);
    if (integer) {
      try {
        return Long.parseLong(t);
      } catch (NumberFormatException e) {
        return Double.parseDouble(t);
      }
    }
    return Double.parseDouble(t);
  }

  public static String write(Object v) {
    StringBuilder sb = new StringBuilder();
    write(sb, v);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  public static void write(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String str) {
      quote(sb, str);
    } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long
        || v instanceof Short || v instanceof Byte) {
      sb.append(v);
    } else if (v instanceof Number n) {
      double d = n.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        sb.append("null");
      } else {
        sb.append(d);
      }
    } else if (v instanceof Map<?, ?> m) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        quote(sb, e.getKey());
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (v instanceof Iterable<?> it) {
      sb.append('[');
      boolean first = true;
      for (Object o : it) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(sb, o);
      }
      sb.append(']');
    } else if (v instanceof double[] a) {
      sb.append('[');
      for (int i = 0; i < a.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        write(sb, a[i]);
      }
      sb.append(']');
    } else if (v instanceof float[] a) {
      sb.append('[');
      for (int i = 0; i < a.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        write(sb, (double) a[i]);
      }
      sb.append(']');
    } else if (v instanceof int[] a) {
      sb.append('[');
      for (int i = 0; i < a.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(a[i]);
      }
      sb.append(']');
    } else if (v instanceof long[] a) {
      sb.append('[');
      for (int i = 0; i < a.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(a[i]);
      }
      sb.append(']');
    } else if (v instanceof boolean[] a) {
      sb.append('[');
      for (int i = 0; i < a.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append(a[i]);
      }
      sb.append(']');
    } else if (v instanceof String[] a) {
      sb.append('[');
      for (int i = 0; i < a.length; i++) {
        if (i > 0) {
          sb.append(',');
        }
        quote(sb, a[i]);
      }
      sb.append(']');
    } else {
      throw new IllegalArgumentException("cannot write " + v.getClass());
    }
  }

  private static void quote(StringBuilder sb, String str) {
    sb.append('"');
    for (int i = 0; i < str.length(); i++) {
      char c = str.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
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

  /** Reads a JSON number-like value as double. */
  public static double toDouble(Object v) {
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    if (v instanceof Boolean b) {
      return b ? 1 : 0;
    }
    return Double.NaN;
  }

  public static long toLong(Object v) {
    if (v instanceof Number n) {
      return n.longValue();
    }
    if (v instanceof Boolean b) {
      return b ? 1 : 0;
    }
    return 0;
  }

  public static boolean toBool(Object v) {
    if (v instanceof Boolean b) {
      return b;
    }
    if (v instanceof Number n) {
      return n.doubleValue() != 0;
    }
    return false;
  }
}
