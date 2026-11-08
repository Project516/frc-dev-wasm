package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.bridge.BridgeLoop;
import dev.project516.frcdevwasm.bridge.Json;
import dev.project516.frcdevwasm.bridge.LocalTransport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The JSON codec and the message loop, on the in-process transport. */
public final class BridgeTest {
  private BridgeTest() {}

  private static boolean waitFor(java.util.function.BooleanSupplier c) throws Exception {
    long end = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < end) {
      if (c.getAsBoolean()) {
        return true;
      }
      Thread.sleep(5);
    }
    return c.getAsBoolean();
  }

  @SuppressWarnings("unchecked")
  public static void run(Tests t) {
    LocalTransport transport = BridgeLoop.local();
    BridgeLoop.start();

    t.test("json parses and writes values", () -> {
      Map<String, Object> m = (Map<String, Object>) Json.parse(
          "{\"a\": [1, 2.5, -3e2, true, null, \"x\\n\\u00e9\\\"\"], \"b\": {\"c\": 9007199254740993}}");
      List<Object> a = (List<Object>) m.get("a");
      Tests.eq(1L, a.get(0), "integer is a Long");
      Tests.eq(2.5, a.get(1), "decimal is a Double");
      Tests.eq(-300.0, a.get(2), "exponent is a Double");
      Tests.eq(true, a.get(3), "true");
      Tests.eq(null, a.get(4), "null");
      Tests.eq("x\né\"", a.get(5), "string escapes");
      Tests.eq(9007199254740993L, ((Map<String, Object>) m.get("b")).get("c"), "large integer keeps precision");
      Tests.eq(Json.write(m), Json.write(Json.parse(Json.write(m))), "round trip");
      Tests.eq("[0.5,1,\"a\\\\b\",null]", Json.write(java.util.Arrays.asList(0.5, 1, "a\\b", null)), "writer escapes");
      Tests.eq("null", Json.write(Double.NaN), "NaN is written as null");
      Tests.eq("[1.5,2.5]", Json.write(new double[] {1.5, 2.5}), "primitive arrays");
      try {
        Json.parse("{\"a\": }");
        throw new AssertionError("expected a parse error");
      } catch (IllegalArgumentException expected) {
        Tests.check(expected.getMessage().startsWith("JSON"), expected.getMessage());
      }
    });

    t.test("standard input is empty, as in a Gradle JavaExec task", () -> {
      Tests.eq(-1, System.in.read(), "read returns end of input");
    });

    t.test("typed and prefix handlers receive page messages", () -> {
      List<String> seen = new CopyOnWriteArrayList<>();
      BridgeLoop.on("bridgetest.", (type, device, data) -> seen.add(type + "/" + device + "/" + data.get("n")));
      BridgeLoop.on("bridgetest", (type, device, data) -> seen.add("exact/" + device));
      transport.inject("bridgetest.alpha", "d1", map("n", 1));
      transport.inject("bridgetest", "d2", map("n", 2));
      Tests.check(waitFor(() -> seen.size() == 2), "both messages dispatched: " + seen);
      Tests.check(seen.contains("bridgetest.alpha/d1/1"), "prefix handler: " + seen);
      Tests.check(seen.contains("exact/d2"), "exact handler: " + seen);
    });

    t.test("unknown types go to the fallback handlers, bad input is ignored", () -> {
      List<String> seen = new CopyOnWriteArrayList<>();
      BridgeLoop.onUnknown((type, device, data) -> seen.add(type));
      transport.inject("some.other.type", "", map());
      transport.inject("{\"type\": 5}");
      transport.inject("{\"device\": \"x\"}");
      transport.inject("[1, 2, \"junk\"]");
      transport.inject("{\"type\":\"bridgetest\",\"device\":\"after-junk\",\"data\":{\"n\":3}}");
      Tests.check(waitFor(() -> seen.contains("some.other.type")), "fallback got the unknown type");
      Tests.check(!seen.contains("bridgetest"), "typed messages do not reach the fallback");
    });

    t.test("deltas to one device merge and ordering is kept", () -> {
      transport.clearSent();
      BridgeLoop.sendDelta("DeltaTest", "0", "a", 1);
      BridgeLoop.sendDelta("DeltaTest", "0", "b", 2);
      BridgeLoop.sendDelta("DeltaTest", "0", "a", 3);
      BridgeLoop.sendJson("{\"type\":\"RawTest\",\"device\":\"\",\"data\":{\"x\":1}}");
      BridgeLoop.send("OrderTest", "", map("i", 1));
      BridgeLoop.send("OrderTest", "", map("i", 2));
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m -> "OrderTest".equals(m.get("type")) && ((Map<String, Object>) m.get("data")).get("i").equals(2L))), "messages sent");
      Map<String, Object> merged = new LinkedHashMap<>();
      List<Long> order = new ArrayList<>();
      boolean raw = false;
      for (Map<String, Object> m : transport.sent()) {
        if ("DeltaTest".equals(m.get("type"))) {
          merged.putAll((Map<String, Object>) m.get("data"));
        } else if ("RawTest".equals(m.get("type"))) {
          raw = true;
        } else if ("OrderTest".equals(m.get("type"))) {
          order.add((Long) ((Map<String, Object>) m.get("data")).get("i"));
        }
      }
      Tests.eq(3L, merged.get("a"), "latest value wins");
      Tests.eq(2L, merged.get("b"), "other key kept");
      Tests.check(raw, "raw JSON message sent");
      Tests.eq(List.of(1L, 2L), order, "send order");
    });

    t.test("console output keeps stream order", () -> {
      transport.clearSent();
      System.out.println("first-out");
      System.err.println("then-err");
      System.out.println("last-out");
      Tests.check(waitFor(() -> transport.sent().stream().anyMatch(m -> "console".equals(m.get("type")) && String.valueOf(((Map<String, Object>) m.get("data")).get("text")).contains("last-out"))), "console sent");
      List<String> streams = new ArrayList<>();
      for (Map<String, Object> m : transport.sent()) {
        if ("console".equals(m.get("type"))) {
          String text = String.valueOf(((Map<String, Object>) m.get("data")).get("text"));
          if (text.contains("first-out") || text.contains("then-err") || text.contains("last-out")) {
            streams.add((String) ((Map<String, Object>) m.get("data")).get("stream"));
          }
        }
      }
      Tests.eq(List.of("out", "err", "out"), streams, "stream order");
    });
  }

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
