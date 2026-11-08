package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.vendor.NativeStubber;
import dev.project516.frcdevwasm.vendor.VendorTransform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;

/** The vendor jar transform: natives call an Impl class or return defaults, library loads vanish. */
public final class NativeStubberTest {
  private NativeStubberTest() {}

  private static final String THING =
      """
      package com.acme.lib;
      public class Thing {
        static { System.loadLibrary("acme"); }
        public int field = 5;
        public static native int twice(int x);
        public native int addField(long extra);
        public static native int unimplemented(long a, double b);
        public static native long wide();
        public static native boolean flag();
        public static native String name();
        public static native byte[] bytes();
        public static native double[][] grid();
        public static native Object object();
        public static native java.nio.ByteBuffer buffer();
        public static native int waitForMessage(int millis);
        public static native void nothing(String s);
        public static Object client() { return java.net.http.HttpClient.newHttpClient(); }
        public static Object builtClient() { return java.net.http.HttpClient.newBuilder().build(); }
        public record Pose(double x, String name) {}
        public static boolean isRecord(Class<?> c) { return c.isRecord(); }
        public static Object components(Class<?> c) { return c.getRecordComponents(); }
      }
      """;

  private static final String IMPL =
      """
      package dev.project516.frcdevwasm.shim.vendor.acme.lib;
      public class ThingImpl {
        public static int twice(int x) { return x * 2; }
        public static int addField(com.acme.lib.Thing self, long extra) { return self.field + (int) extra; }
      }
      """;

  private static void javac(Path out, Path classpath, String... files) {
    List<String> args = new java.util.ArrayList<>(List.of("-d", out.toString(), "-proc:none"));
    if (classpath != null) {
      args.addAll(List.of("-cp", classpath.toString()));
    }
    args.addAll(List.of(files));
    int status =
        ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err, args.toArray(new String[0]));
    Tests.eq(0, status, "javac status");
  }

  public static void run(Tests t) {
    t.test("natives become Java methods and library loads are removed", () -> {
      Path dir = Files.createTempDirectory("stubber");
      Path src = dir.resolve("src");
      Files.createDirectories(src.resolve("com/acme/lib"));
      Files.writeString(src.resolve("com/acme/lib/Thing.java"), THING);
      Path raw = dir.resolve("raw");
      Files.createDirectories(raw);
      javac(raw, null, src.resolve("com/acme/lib/Thing.java").toString());

      Path implSrc = dir.resolve("implsrc/ThingImpl.java");
      Files.createDirectories(implSrc.getParent());
      Files.writeString(implSrc, IMPL);
      Path impl = dir.resolve("impl");
      Files.createDirectories(impl);
      javac(impl, raw, implSrc.toString());

      Path in = dir.resolve("in.jar");
      Path out = dir.resolve("out.jar");
      jar(raw, in);
      new NativeStubber(impl).transformJar(in, out);

      try {
        Class.forName("com.acme.lib.Thing", true, new URLClassLoader(new URL[] {in.toUri().toURL()}, NativeStubberTest.class.getClassLoader()));
        Tests.check(false, "the original jar should fail to load its library");
      } catch (UnsatisfiedLinkError expected) {
        // the unpatched class cannot load its library
      }

      URL[] urls = {out.toUri().toURL(), impl.toUri().toURL()};
      Class<?> thing = Class.forName("com.acme.lib.Thing", true, new URLClassLoader(urls, NativeStubberTest.class.getClassLoader()));
      Tests.eq(10, thing.getMethod("twice", int.class).invoke(null, 5), "static native calls Impl");
      Object instance = thing.getConstructor().newInstance();
      Tests.eq(12, thing.getMethod("addField", long.class).invoke(instance, 7L), "instance native passes the receiver");
      Tests.eq(0, thing.getMethod("unimplemented", long.class, double.class).invoke(null, 1L, 2.0), "int default");
      Tests.eq(0L, thing.getMethod("wide").invoke(null), "long default");
      Tests.eq(false, thing.getMethod("flag").invoke(null), "boolean default");
      Tests.eq("", thing.getMethod("name").invoke(null), "String default");
      Tests.eq(0, ((byte[]) thing.getMethod("bytes").invoke(null)).length, "byte[] default");
      Tests.eq(0, ((double[][]) thing.getMethod("grid").invoke(null)).length, "double[][] default");
      Tests.eq(null, thing.getMethod("object").invoke(null), "Object default");
      Tests.eq(0, ((java.nio.ByteBuffer) thing.getMethod("buffer").invoke(null)).capacity(), "ByteBuffer default");
      long start = System.nanoTime();
      Tests.eq(0, thing.getMethod("waitForMessage", int.class).invoke(null, 5), "wait default");
      Tests.check(System.nanoTime() - start >= 10_000_000L, "a defaulted wait should sleep instead of spinning");
      thing.getMethod("nothing", String.class).invoke(null, "x");
      for (String factory : new String[] {"client", "builtClient"}) {
        java.net.http.HttpClient client = (java.net.http.HttpClient) thing.getMethod(factory).invoke(null);
        try {
          client.send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://10.0.0.1/")).build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
          Tests.check(false, "requests should fail");
        } catch (java.io.IOException expected) {
          // no network in the browser JVM
        }
      }
      Class<?> pose = Class.forName("com.acme.lib.Thing$Pose", true, thing.getClassLoader());
      Tests.eq(true, thing.getMethod("isRecord", Class.class).invoke(null, pose), "isRecord through the shim");
      Object[] components = (Object[]) thing.getMethod("components", Class.class).invoke(null, pose);
      Tests.eq(
          "[x, name]",
          java.util.Arrays.toString(
              java.util.Arrays.stream(components).map(c -> ((java.lang.reflect.RecordComponent) c).getName()).toArray()),
          "record components through the shim");
      try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(out.toFile())) {
        String bytes =
            new String(zip.getInputStream(zip.getEntry("com/acme/lib/Thing.class")).readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
        Tests.check(bytes.contains("dev/project516/frcdevwasm/shim/lang/Records"), "record reflection calls go to the shim");
      }
      Tests.check(
          !java.lang.reflect.Modifier.isNative(thing.getMethod("twice", int.class).getModifiers()),
          "no native modifier remains");

      // The browser reads the Impl index instead of the classes, and streams instead of paths
      Path index = dir.resolve("impls.txt");
      NativeStubber.writeIndex(impl, index);
      Tests.check(
          Files.readString(index).contains("dev/project516/frcdevwasm/shim/vendor/acme/lib/ThingImpl twice(I)I"),
          "the index lists the Impl methods");
      Path streamed = dir.resolve("streamed.jar");
      boolean changed;
      try (InputStream idx = Files.newInputStream(index);
          InputStream is = Files.newInputStream(in);
          OutputStream os = Files.newOutputStream(streamed)) {
        changed = NativeStubber.fromIndex(idx).transform(is, os, "in.jar");
      }
      Tests.check(changed, "a jar with natives changes");
      URL[] streamedUrls = {streamed.toUri().toURL(), impl.toUri().toURL()};
      Class<?> again = Class.forName("com.acme.lib.Thing", true, new URLClassLoader(streamedUrls, NativeStubberTest.class.getClassLoader()));
      Tests.eq(10, again.getMethod("twice", int.class).invoke(null, 5), "the streamed jar calls Impl");
      Path plain = dir.resolve("plain.jar");
      jar(impl, plain);
      try (InputStream idx = Files.newInputStream(index);
          InputStream is = Files.newInputStream(plain);
          OutputStream os = OutputStream.nullOutputStream()) {
        Tests.check(!NativeStubber.fromIndex(idx).transform(is, os, "plain.jar"), "a jar without natives is unchanged");
      }

      // The browser entry point writes its output only when something changed
      Path viaTool = dir.resolve("via-tool.jar");
      Tests.eq("changed", VendorTransform.transform(in.toString(), viaTool.toString()), "tool result for a jar with natives");
      Tests.check(Files.size(viaTool) > 0, "the tool wrote the transformed jar");
      Path untouched = dir.resolve("untouched.jar");
      Tests.eq("unchanged", VendorTransform.transform(plain.toString(), untouched.toString()), "tool result for a plain jar");
      Tests.check(!Files.exists(untouched), "the tool writes nothing for a plain jar");
    });
  }

  private static void jar(Path classes, Path file) throws IOException {
    int status =
        ToolProvider.findFirst("jar")
            .orElseThrow()
            .run(System.out, System.err, "--create", "--file", file.toString(), "-C", classes.toString(), ".");
    Tests.eq(0, status, "jar status");
  }
}
