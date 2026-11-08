package dev.project516.frcdevwasm.shim.math.jni;

import edu.wpi.first.math.trajectory.TrajectoryUtil;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Trajectory JSON in the layout of the C++ wpimath: one object per state, keys sorted. */
public final class TrajectoryUtilJNIImpl {
  private static final int STATE_SIZE = 7;

  private TrajectoryUtilJNIImpl() {}

  public static double[] fromPathweaverJson(String path) throws IOException {
    String text;
    try (FileInputStream in = new FileInputStream(path)) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IOException("Cannot open file: " + path);
    }
    try {
      return elementsFrom(new Parser(text).parseDocument());
    } catch (JsonError e) {
      throw new IOException(e.getMessage());
    }
  }

  public static void toPathweaverJson(double[] elements, String path) throws IOException {
    String json = serialize(elements);
    try (FileOutputStream out = new FileOutputStream(path)) {
      out.write(json.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IOException("Cannot open file: " + path);
    }
  }

  public static double[] deserializeTrajectory(String json) {
    try {
      return elementsFrom(new Parser(json).parseDocument());
    } catch (JsonError e) {
      throw new TrajectoryUtil.TrajectorySerializationException(e.getMessage());
    }
  }

  public static String serializeTrajectory(double[] elements) {
    return serialize(elements);
  }

  private static String serialize(double[] e) {
    StringBuilder sb = new StringBuilder(e.length * 40);
    sb.append('[');
    for (int i = 0; i + STATE_SIZE <= e.length; i += STATE_SIZE) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"acceleration\":").append(Fmt.json(e[i + 2]));
      sb.append(",\"curvature\":").append(Fmt.json(e[i + 6]));
      sb.append(",\"pose\":{\"rotation\":{\"radians\":").append(Fmt.json(e[i + 5]));
      sb.append("},\"translation\":{\"x\":").append(Fmt.json(e[i + 3]));
      sb.append(",\"y\":").append(Fmt.json(e[i + 4]));
      sb.append("}},\"time\":").append(Fmt.json(e[i]));
      sb.append(",\"velocity\":").append(Fmt.json(e[i + 1])).append('}');
    }
    return sb.append(']').toString();
  }

  private static double[] elementsFrom(Object doc) {
    List<?> states = array(doc);
    double[] out = new double[states.size() * STATE_SIZE];
    int o = 0;
    for (Object state : states) {
      Object pose = at(state, "pose");
      Object translation = at(pose, "translation");
      double x = number(at(translation, "x"));
      double y = number(at(translation, "y"));
      double radians = number(at(at(pose, "rotation"), "radians"));
      double time = number(at(state, "time"));
      double velocity = number(at(state, "velocity"));
      double acceleration = number(at(state, "acceleration"));
      double curvature = number(at(state, "curvature"));
      out[o++] = time;
      out[o++] = velocity;
      out[o++] = acceleration;
      out[o++] = x;
      out[o++] = y;
      out[o++] = radians;
      out[o++] = curvature;
    }
    return out;
  }

  private static String typeName(Object v) {
    if (v == null) {
      return "null";
    }
    if (v instanceof Map) {
      return "object";
    }
    if (v instanceof List) {
      return "array";
    }
    if (v instanceof String) {
      return "string";
    }
    if (v instanceof Boolean) {
      return "boolean";
    }
    return "number";
  }

  private static List<?> array(Object v) {
    if (v instanceof List<?> l) {
      return l;
    }
    throw new JsonError("[json.exception.type_error.302] type must be array, but is " + typeName(v));
  }

  private static double number(Object v) {
    if (v instanceof Double d) {
      return d;
    }
    throw new JsonError("[json.exception.type_error.302] type must be number, but is " + typeName(v));
  }

  private static Object at(Object v, String key) {
    if (!(v instanceof Map<?, ?> m)) {
      throw new JsonError("[json.exception.type_error.304] cannot use at() with " + typeName(v));
    }
    if (!m.containsKey(key)) {
      throw new JsonError("[json.exception.out_of_range.403] key '" + key + "' not found");
    }
    return m.get(key);
  }

  private static final class JsonError extends RuntimeException {
    JsonError(String message) {
      super(message, null, false, false);
    }
  }

  /** Strict JSON parser: objects become TreeMaps, arrays Lists, numbers Doubles. */
  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    Object parseDocument() {
      skipWhitespace();
      Object v = parseValue();
      skipWhitespace();
      if (pos < s.length()) {
        throw error("syntax error while parsing value - unexpected " + describe() + "; expected end of input");
      }
      return v;
    }

    private JsonError error(String what) {
      int line = 1;
      int col = 0;
      int end = Math.min(pos + 1, s.length());
      for (int i = 0; i < end; i++) {
        if (s.charAt(i) == '\n') {
          line++;
          col = 0;
        } else {
          col++;
        }
      }
      if (s.isEmpty()) {
        col = 1;
      }
      return new JsonError(
          "[json.exception.parse_error.101] parse error at line " + line + ", column " + col + ": " + what);
    }

    private String describe() {
      if (pos >= s.length()) {
        return "end of input";
      }
      char c = s.charAt(pos);
      return c == '"' ? "string literal" : "'" + c + "'";
    }

    private void skipWhitespace() {
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }

    private Object parseValue() {
      if (pos >= s.length()) {
        throw error(
            "syntax error while parsing value - unexpected end of input; expected '[', '{', or a literal");
      }
      char c = s.charAt(pos);
      switch (c) {
        case '{':
          return parseObject();
        case '[':
          return parseArray();
        case '"':
          return parseString();
        case 't':
          return literal("true", Boolean.TRUE);
        case 'f':
          return literal("false", Boolean.FALSE);
        case 'n':
          return literal("null", null);
        default:
          if (c == '-' || (c >= '0' && c <= '9')) {
            return parseNumber();
          }
          throw error("syntax error while parsing value - invalid literal; last read: '" + c + "'");
      }
    }

    private Object literal(String word, Object value) {
      if (!s.startsWith(word, pos)) {
        throw error("syntax error while parsing value - invalid literal; last read: '" + s.charAt(pos) + "'");
      }
      pos += word.length();
      return value;
    }

    private Object parseNumber() {
      int start = pos;
      if (s.charAt(pos) == '-') {
        pos++;
      }
      int intStart = pos;
      while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
        pos++;
      }
      if (pos == intStart || (s.charAt(intStart) == '0' && pos - intStart > 1)) {
        throw error("syntax error while parsing value - invalid number; expected digit after '-'");
      }
      if (pos < s.length() && s.charAt(pos) == '.') {
        pos++;
        int f = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
          pos++;
        }
        if (pos == f) {
          throw error("syntax error while parsing value - invalid number; expected digit after '.'");
        }
      }
      if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
        pos++;
        if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
          pos++;
        }
        int x = pos;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
          pos++;
        }
        if (pos == x) {
          throw error("syntax error while parsing value - invalid number; expected digit after exponent");
        }
      }
      return Double.parseDouble(s.substring(start, pos));
    }

    private String parseString() {
      pos++;
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) {
          throw error("syntax error while parsing value - invalid string: missing closing quote");
        }
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (pos >= s.length()) {
            throw error("syntax error while parsing value - invalid string: missing closing quote");
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
                throw error("syntax error while parsing value - invalid string: '\\u' must be followed by 4 hex digits");
              }
              try {
                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
              } catch (NumberFormatException ex) {
                throw error("syntax error while parsing value - invalid string: '\\u' must be followed by 4 hex digits");
              }
              pos += 4;
            }
            default -> throw error("syntax error while parsing value - invalid string: forbidden character after backslash");
          }
        } else if (c < 0x20) {
          throw error("syntax error while parsing value - invalid string: control character must be escaped");
        } else {
          sb.append(c);
        }
      }
    }

    private List<Object> parseArray() {
      pos++;
      List<Object> list = new ArrayList<>();
      skipWhitespace();
      if (pos < s.length() && s.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (true) {
        skipWhitespace();
        list.add(parseValue());
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == ',') {
          pos++;
        } else if (pos < s.length() && s.charAt(pos) == ']') {
          pos++;
          return list;
        } else {
          throw error("syntax error while parsing array - unexpected " + describe() + "; expected ',' or ']'");
        }
      }
    }

    private Map<String, Object> parseObject() {
      pos++;
      Map<String, Object> map = new TreeMap<>();
      skipWhitespace();
      if (pos < s.length() && s.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        if (pos >= s.length() || s.charAt(pos) != '"') {
          throw error("syntax error while parsing object key - unexpected " + describe() + "; expected string literal");
        }
        String key = parseString();
        skipWhitespace();
        if (pos >= s.length() || s.charAt(pos) != ':') {
          throw error("syntax error while parsing object separator - unexpected " + describe() + "; expected ':'");
        }
        pos++;
        skipWhitespace();
        map.put(key, parseValue());
        skipWhitespace();
        if (pos < s.length() && s.charAt(pos) == ',') {
          pos++;
        } else if (pos < s.length() && s.charAt(pos) == '}') {
          pos++;
          return map;
        } else {
          throw error("syntax error while parsing object - unexpected " + describe() + "; expected '}'");
        }
      }
    }
  }
}
