package dev.project516.frcdevwasm.test;

import dev.project516.frcdevwasm.vendor.NativeStubber;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;

/** The vendor jar transform on a library that calls C through java.lang.foreign. */
public final class NativeStubberTest {
  private NativeStubberTest() {}

  private static final String BINDING =
      """
      package com.acme.ffi;

      import java.lang.foreign.FunctionDescriptor;
      import java.lang.foreign.Linker;
      import java.lang.foreign.MemorySegment;
      import java.lang.foreign.SymbolLookup;
      import java.lang.foreign.ValueLayout;
      import java.lang.invoke.MethodHandle;
      import java.lang.invoke.MethodHandles;
      import java.lang.invoke.MethodType;

      public class AcmeNative {
        protected static final SymbolLookup library = SymbolLookup.loaderLookup();
        private static final MethodHandle twice_handle;
        private static final MethodHandle unimplemented_handle;
        private static final MethodHandle point_handle;

        static {
          System.loadLibrary("acme");
          twice_handle = create_twice_handle();
          unimplemented_handle = create_unimplemented_handle();
          point_handle = create_point_handle();
        }

        public static int twice(int x) {
          try {
            return (int) twice_handle.invokeExact(x);
          } catch (Throwable t) {
            throw new AssertionError("This should never happen", t);
          }
        }

        private static MethodHandle create_twice_handle() {
          Linker linker = Linker.nativeLinker();
          MemorySegment address = library.findOrThrow("twice");
          FunctionDescriptor descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
          return linker.downcallHandle(address, descriptor);
        }

        public static double unimplemented(long a, MemorySegment b) {
          try {
            return (double) unimplemented_handle.invokeExact(a, b);
          } catch (Throwable t) {
            throw new AssertionError("This should never happen", t);
          }
        }

        private static MethodHandle create_unimplemented_handle() {
          Linker linker = Linker.nativeLinker();
          MemorySegment address = library.findOrThrow("unimplemented");
          return linker.downcallHandle(
              address, FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        }

        public static MemorySegment point(int x) {
          try {
            return (MemorySegment) point_handle.invokeExact(x);
          } catch (Throwable t) {
            throw new AssertionError("This should never happen", t);
          }
        }

        private static MethodHandle create_point_handle() {
          Linker linker = Linker.nativeLinker();
          MemorySegment address = library.findOrThrow("point");
          return linker.downcallHandle(address, FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        }

        public static MemorySegment makeStub() throws Throwable {
          MethodHandle target = MethodHandles.lookup().findStatic(AcmeNative.class, "inc", MethodType.methodType(int.class, int.class));
          return Linker.nativeLinker().upcallStub(target, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT), java.lang.foreign.Arena.global());
        }

        public static String describe() {
          return "kept";
        }

        public static int viaOwnHandle(int x) throws Throwable {
          MethodHandle inc = MethodHandles.lookup().findStatic(AcmeNative.class, "inc", MethodType.methodType(int.class, int.class));
          return (int) inc.invokeExact(x);
        }

        static int inc(int x) {
          return x + 1;
        }
      }
      """;

  private static final String PLAIN =
      """
      package com.acme.plain;

      import java.lang.invoke.MethodHandle;
      import java.lang.invoke.MethodHandles;
      import java.lang.invoke.MethodType;

      public class Plain {
        public static int viaHandle(int x) throws Throwable {
          MethodHandle inc = MethodHandles.lookup().findStatic(Plain.class, "inc", MethodType.methodType(int.class, int.class));
          return (int) inc.invokeExact(x);
        }

        static int inc(int x) {
          return x + 1;
        }
      }
      """;

  private static final String IMPL =
      """
      package dev.project516.frcdevwasm.shim.vendor.acme.ffi;

      public class AcmeNativeImpl {
        public static int twice(int x) {
          return x * 2;
        }
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

  private static Path write(Path dir, String name, String source) throws IOException {
    Path file = dir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
    return file;
  }

  private static void jar(Path classes, Path file) {
    int status =
        ToolProvider.findFirst("jar")
            .orElseThrow()
            .run(System.out, System.err, "--create", "--file", file.toString(), "-C", classes.toString(), ".");
    Tests.eq(0, status, "jar status");
  }

  public static void run(Tests t) {
    t.test("downcall wrappers call an Impl class or return defaults, and handles are not created", () -> {
      Path dir = Files.createTempDirectory("stubber-ffi");
      Path raw = Files.createDirectories(dir.resolve("raw"));
      javac(
          raw,
          null,
          write(dir, "src/com/acme/ffi/AcmeNative.java", BINDING).toString(),
          write(dir, "src/com/acme/plain/Plain.java", PLAIN).toString());
      Path impl = Files.createDirectories(dir.resolve("impl"));
      javac(impl, raw, write(dir, "implsrc/AcmeNativeImpl.java", IMPL).toString());

      Path in = dir.resolve("in.jar");
      Path out = dir.resolve("out.jar");
      jar(raw, in);
      new NativeStubber(impl).transformJar(in, out);

      try {
        Class.forName("com.acme.ffi.AcmeNative", true, new URLClassLoader(new URL[] {in.toUri().toURL()}, NativeStubberTest.class.getClassLoader()));
        Tests.check(false, "the original jar should fail to load its library");
      } catch (UnsatisfiedLinkError | ExceptionInInitializerError expected) {
        // no library to load or to find symbols in
      }

      URL[] urls = {out.toUri().toURL(), impl.toUri().toURL()};
      ClassLoader loader = new URLClassLoader(urls, NativeStubberTest.class.getClassLoader());
      Class<?> binding = Class.forName("com.acme.ffi.AcmeNative", true, loader);
      Tests.eq(10, binding.getMethod("twice", int.class).invoke(null, 5), "downcall calls Impl");
      Tests.eq(
          0.0,
          binding.getMethod("unimplemented", long.class, java.lang.foreign.MemorySegment.class).invoke(null, 1L, java.lang.foreign.MemorySegment.NULL),
          "double default");
      Tests.eq(java.lang.foreign.MemorySegment.NULL, binding.getMethod("point", int.class).invoke(null, 1), "MemorySegment default");
      Tests.eq("kept", binding.getMethod("describe").invoke(null), "other methods stay");
      Tests.eq(8, binding.getMethod("viaOwnHandle", int.class).invoke(null, 7), "a handle the library made itself still works");

      MemorySegment stub = (MemorySegment) binding.getMethod("makeStub").invoke(null);
      java.lang.invoke.MethodHandle target = dev.project516.frcdevwasm.shim.vendor.Upcalls.target(stub);
      Tests.check(target != null, "an upcall stub is recorded");
      int called;
      try {
        called = (int) target.invoke(41);
      } catch (Throwable e) {
        throw new AssertionError(e);
      }
      Tests.eq(42, called, "the Impl can call the Java target of a stub");
      Tests.eq(null, dev.project516.frcdevwasm.shim.vendor.Upcalls.target(java.lang.foreign.MemorySegment.NULL), "no target for a null stub");

      // invokeExact in a class that binds no native library is the library's own business
      Class<?> plain = Class.forName("com.acme.plain.Plain", true, loader);
      Tests.eq(8, plain.getMethod("viaHandle", int.class).invoke(null, 7), "a plain class keeps its method handles");
      Path untouched = dir.resolve("plain.jar");
      Path plainClasses = Files.createDirectories(dir.resolve("plainclasses/com/acme/plain"));
      Files.copy(raw.resolve("com/acme/plain/Plain.class"), plainClasses.resolve("Plain.class"));
      jar(dir.resolve("plainclasses"), untouched);
      Tests.check(
          !NativeStubber.fromIndex(new java.io.ByteArrayInputStream(new byte[0]))
              .transform(Files.newInputStream(untouched), java.io.OutputStream.nullOutputStream(), "plain.jar"),
          "a jar without natives or bindings is unchanged");
    });
  }
}
