package dev.project516.frcdevwasm.vendor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Makes a vendor jar run without native code. Each native method calls the static method of the
 * same name in a handwritten Impl class when one exists, and otherwise returns a default. Calls
 * that load native libraries are removed.
 *
 * <p>Libraries that call C through {@code java.lang.foreign} get the same treatment: a static method
 * whose body is a downcall ({@code MethodHandle.invokeExact}) is replaced like a native, and the
 * methods that create the downcall handles return null, since there is no library to look up.
 *
 * <p>The Impl class of {@code com/ctre/phoenix6/jni/StatusSignalJNI} is {@code
 * dev/project516/frcdevwasm/shim/vendor/ctre/phoenix6/jni/StatusSignalJNIImpl}. An instance native
 * takes the receiver as an extra first parameter.
 *
 * <p>Usage: NativeStubber --impl implClassesDir [--report file] in.jar out.jar...; {@code @file}
 * lists one jar path per line. {@code NativeStubber --index implClassesDir out.txt} writes the
 * list of Impl methods that the browser transform reads instead of the classes.
 */
public final class NativeStubber {
  private static final String IMPL_ROOT = "dev/project516/frcdevwasm/shim/vendor/";
  private static final Pattern SIGNATURE = Pattern.compile("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
  private static final Set<String> TLDS = Set.of("com", "org", "net", "io", "dev");
  private static final Set<String> LOADERS =
      Set.of(
          "java/lang/System",
          "edu/wpi/first/util/RuntimeLoader",
          "edu/wpi/first/util/CombinedRuntimeLoader");

  // JDK calls the browser JVM cannot run, mapped to a static method with the same name and descriptor.
  private static final String NET = "dev/project516/frcdevwasm/shim/net/UnavailableHttpClient";
  private static final Map<String, String> REDIRECTS =
      Map.of(
          "java/net/http/HttpClient.newHttpClient()Ljava/net/http/HttpClient;", NET,
          "java/net/http/HttpClient.newBuilder()Ljava/net/http/HttpClient$Builder;", NET);

  // JDK instance methods the browser JVM does not implement, mapped to a static method with the same
  // name that takes the receiver first.
  private static final String RECORDS = "dev/project516/frcdevwasm/shim/lang/Records";
  private static final String UPCALLS = "dev/project516/frcdevwasm/shim/vendor/Upcalls";
  private static final Map<String, String> INSTANCE_REDIRECTS =
      Map.of(
          "java/lang/Class.isRecord()Z", RECORDS,
          "java/lang/Class.getRecordComponents()[Ljava/lang/reflect/RecordComponent;", RECORDS,
          "java/lang/foreign/Linker.upcallStub(Ljava/lang/invoke/MethodHandle;Ljava/lang/foreign/FunctionDescriptor;"
              + "Ljava/lang/foreign/Arena;[Ljava/lang/foreign/Linker$Option;)Ljava/lang/foreign/MemorySegment;",
          UPCALLS);

  private static final String METHOD_HANDLE = "java/lang/invoke/MethodHandle";
  private static final String LINKER = "java/lang/foreign/Linker";
  private static final String HANDLE_FACTORY_DESC = "()Ljava/lang/invoke/MethodHandle;";

  // Defaulted waits would return at once and spin the library's polling thread, so they sleep first.
  private static final String IDLE = "dev/project516/frcdevwasm/shim/vendor/NativeStubs";

  private final Function<String, Set<String>> impls;
  private final Map<String, Set<String>> implMethods = new HashMap<>();
  private final TreeSet<String> report = new TreeSet<>();

  /** Looks up the static methods ({@code name+descriptor}) of an Impl class, empty if there is none. */
  public NativeStubber(Function<String, Set<String>> impls) {
    this.impls = impls;
  }

  /** Reads Impl classes from a directory of class files. */
  public NativeStubber(Path implDir) {
    this(implClass -> staticMethods(implDir.resolve(implClass + ".class")));
  }

  /** Reads the index that {@code --index} writes: one {@code implClass name+descriptor} per line. */
  public static NativeStubber fromIndex(InputStream index) throws IOException {
    Map<String, Set<String>> methods = new HashMap<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(index, StandardCharsets.UTF_8))) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        int space = line.indexOf(' ');
        if (space > 0) {
          methods.computeIfAbsent(line.substring(0, space), k -> new HashSet<>()).add(line.substring(space + 1));
        }
      }
    }
    return new NativeStubber(implClass -> methods.getOrDefault(implClass, Set.of()));
  }

  /** Writes the Impl method index of a directory of Impl classes. */
  public static void writeIndex(Path implDir, Path out) throws IOException {
    TreeSet<String> lines = new TreeSet<>();
    try (Stream<Path> files = Files.walk(implDir.resolve(IMPL_ROOT))) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
        String implClass = implDir.relativize(file).toString().replace('\\', '/');
        implClass = implClass.substring(0, implClass.length() - ".class".length());
        for (String method : staticMethods(file)) {
          lines.add(implClass + " " + method);
        }
      }
    }
    Files.write(out, lines);
  }

  public static void main(String[] args) throws IOException {
    if (args.length == 3 && args[0].equals("--index")) {
      writeIndex(Path.of(args[1]), Path.of(args[2]));
      return;
    }
    Path impl = null;
    Path report = null;
    List<String> jars = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--impl" -> impl = Path.of(args[++i]);
        case "--report" -> report = Path.of(args[++i]);
        default -> {
          if (args[i].startsWith("@")) {
            for (String line : Files.readAllLines(Path.of(args[i].substring(1)))) {
              jars.add(line.strip().replaceAll("^\"|\"$", ""));
            }
          } else {
            jars.add(args[i]);
          }
        }
      }
    }
    if (impl == null || jars.size() % 2 != 0) {
      throw new IllegalArgumentException(
          "usage: NativeStubber --impl dir [--report file] in.jar out.jar ...");
    }
    NativeStubber stubber = new NativeStubber(impl);
    for (int i = 0; i < jars.size(); i += 2) {
      stubber.transformJar(Path.of(jars.get(i)), Path.of(jars.get(i + 1)));
    }
    if (report != null) {
      Files.write(report, stubber.report);
    }
  }

  /** Writes {@code out} with natives replaced. A jar with nothing to replace is copied as is. */
  public void transformJar(Path in, Path out) throws IOException {
    Files.createDirectories(out.getParent());
    Path part = out.resolveSibling(out.getFileName() + ".part");
    boolean changed;
    try (InputStream is = Files.newInputStream(in);
        OutputStream os = Files.newOutputStream(part)) {
      changed = transform(is, os, in.getFileName().toString());
    }
    if (changed) {
      Files.move(part, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } else {
      Files.delete(part);
      Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * Writes a copy of the jar with natives replaced and returns whether anything was replaced. The
   * copy is only useful when the result is true. The streams are not closed.
   */
  public boolean transform(InputStream in, OutputStream out, String jarName) throws IOException {
    boolean changed = false;
    ZipInputStream zis = new ZipInputStream(in);
    ZipOutputStream zos = new ZipOutputStream(out);
    for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
      if (SIGNATURE.matcher(entry.getName()).matches()) {
        continue; // a rewritten class would fail its digest
      }
      byte[] data = zis.readAllBytes();
      if (entry.getName().endsWith(".class")) {
        byte[] rewritten = transformClass(data, jarName);
        if (rewritten != null) {
          data = rewritten;
          changed = true;
        }
      }
      ZipEntry copy = new ZipEntry(entry.getName());
      copy.setTime(entry.getTime());
      zos.putNextEntry(copy);
      zos.write(data);
      zos.closeEntry();
    }
    zos.finish();
    return changed;
  }

  /** Returns the rewritten class, or null when it has no natives and loads no libraries. */
  byte[] transformClass(byte[] data, String jarName) {
    ClassReader reader = new ClassReader(data);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    boolean[] changed = {false};
    Set<String> downcalls = new HashSet<>();
    Set<String> handleFactories = new HashSet<>();
    if (mentions(data, LINKER)) {
      scanForeignCalls(reader, downcalls, handleFactories);
    }
    if (handleFactories.isEmpty()) {
      downcalls.clear(); // invokeExact outside a foreign binding is the library's own business
    }
    reader.accept(
        new ClassVisitor(Opcodes.ASM9, writer) {
          String owner;

          @Override
          public void visit(
              int version,
              int access,
              String name,
              String signature,
              String superName,
              String[] interfaces) {
            owner = name;
            super.visit(version, access, name, signature, superName, interfaces);
          }

          @Override
          public MethodVisitor visitMethod(
              int access, String name, String desc, String signature, String[] exceptions) {
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            boolean isDowncall = isStatic && downcalls.contains(name + desc);
            boolean isNative = (access & Opcodes.ACC_NATIVE) != 0 || isDowncall;
            if (isStatic && handleFactories.contains(name + desc)) {
              changed[0] = true;
              MethodVisitor factory = super.visitMethod(access, name, desc, signature, exceptions);
              return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitEnd() {
                  factory.visitCode();
                  factory.visitInsn(Opcodes.ACONST_NULL);
                  factory.visitInsn(Opcodes.ARETURN);
                  factory.visitMaxs(0, 0);
                  factory.visitEnd();
                }
              };
            }
            MethodVisitor mv =
                super.visitMethod(access & ~Opcodes.ACC_NATIVE, name, desc, signature, exceptions);
            if (isNative) {
              changed[0] = true;
              // A downcall has code of its own, which the replacement drops
              MethodVisitor target = mv;
              return new MethodVisitor(Opcodes.ASM9, isDowncall ? null : target) {
                @Override
                public void visitEnd() {
                  emitBody(target, owner, isStatic, name, desc, jarName);
                  target.visitEnd();
                }
              };
            }
            return new MethodVisitor(Opcodes.ASM9, mv) {
              @Override
              public void visitMethodInsn(
                  int opcode, String insnOwner, String insnName, String insnDesc, boolean itf) {
                String redirect = REDIRECTS.get(insnOwner + "." + insnName + insnDesc);
                String instanceRedirect = INSTANCE_REDIRECTS.get(insnOwner + "." + insnName + insnDesc);
                if (instanceRedirect != null && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE)) {
                  changed[0] = true;
                  String staticDesc = "(L" + insnOwner + ";" + insnDesc.substring(1);
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, instanceRedirect, insnName, staticDesc, false);
                } else if (isLibraryLoad(insnOwner, insnName, insnDesc)) {
                  changed[0] = true;
                  popArguments(mv, opcode, insnDesc);
                } else if (redirect != null && opcode == Opcodes.INVOKESTATIC) {
                  changed[0] = true;
                  super.visitMethodInsn(Opcodes.INVOKESTATIC, redirect, insnName, insnDesc, false);
                } else {
                  super.visitMethodInsn(opcode, insnOwner, insnName, insnDesc, itf);
                }
              }
            };
          }
        },
        0);
    return changed[0] ? writer.toByteArray() : null;
  }

  /**
   * Finds the static methods that make a downcall (a call of {@code MethodHandle.invokeExact} on a
   * handle in a static field of the class) and the methods that create downcall handles.
   */
  private static void scanForeignCalls(ClassReader reader, Set<String> downcalls, Set<String> handleFactories) {
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String desc, String signature, String[] exceptions) {
            if ((access & Opcodes.ACC_STATIC) == 0) {
              return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
              boolean usesStaticHandle;

              @Override
              public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDesc) {
                if (opcode == Opcodes.GETSTATIC && fieldDesc.equals("L" + METHOD_HANDLE + ";")) {
                  usesStaticHandle = true;
                }
              }

              @Override
              public void visitMethodInsn(
                  int opcode, String owner, String insnName, String insnDesc, boolean itf) {
                if (owner.equals(METHOD_HANDLE) && insnName.equals("invokeExact") && usesStaticHandle) {
                  downcalls.add(name + desc);
                } else if (owner.equals(LINKER) && insnName.equals("downcallHandle") && desc.equals(HANDLE_FACTORY_DESC)) {
                  handleFactories.add(name + desc);
                }
              }
            };
          }
        },
        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
  }

  /** Whether the class file holds the name, which saves parsing the classes that cannot use it. */
  private static boolean mentions(byte[] data, String name) {
    byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
    for (int i = 0; i + needle.length <= data.length; i++) {
      int j = 0;
      while (j < needle.length && data[i + j] == needle[j]) {
        j++;
      }
      if (j == needle.length) {
        return true;
      }
    }
    return false;
  }

  private static boolean isLibraryLoad(String owner, String name, String desc) {
    return LOADERS.contains(owner)
        && name.startsWith("load")
        && Type.getReturnType(desc).getSort() == Type.VOID;
  }

  private static void popArguments(MethodVisitor mv, int opcode, String desc) {
    Type[] args = Type.getArgumentTypes(desc);
    for (int i = args.length - 1; i >= 0; i--) {
      mv.visitInsn(args[i].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
    }
    if (opcode != Opcodes.INVOKESTATIC) {
      mv.visitInsn(Opcodes.POP);
    }
  }

  static String implClass(String owner) {
    String[] parts = owner.split("/");
    int skip = parts.length > 1 && TLDS.contains(parts[0]) ? 1 : 0;
    String path = String.join("/", java.util.Arrays.copyOfRange(parts, skip, parts.length));
    return IMPL_ROOT + path.replace('$', '_') + "Impl";
  }

  private static Set<String> staticMethods(Path classFile) {
    Set<String> methods = new HashSet<>();
    if (Files.exists(classFile)) {
      try {
        new ClassReader(Files.readAllBytes(classFile))
            .accept(
                new ClassVisitor(Opcodes.ASM9) {
                  @Override
                  public MethodVisitor visitMethod(
                      int access, String name, String desc, String sig, String[] ex) {
                    if ((access & Opcodes.ACC_STATIC) != 0) {
                      methods.add(name + desc);
                    }
                    return null;
                  }
                },
                ClassReader.SKIP_CODE);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    return methods;
  }

  private Set<String> methodsOf(String implClass) {
    return implMethods.computeIfAbsent(implClass, impls);
  }

  private void emitBody(
      MethodVisitor mv, String owner, boolean isStatic, String name, String desc, String jar) {
    Type[] args = Type.getArgumentTypes(desc);
    Type ret = Type.getReturnType(desc);
    String implOwner = implClass(owner);
    String implDesc = isStatic ? desc : "(L" + owner + ";" + desc.substring(1);
    boolean delegate = methodsOf(implOwner).contains(name + implDesc);
    report.add(
        (delegate ? "impl    " : "default ")
            + jar
            + " "
            + owner.replace('/', '.')
            + "."
            + name
            + desc);
    mv.visitCode();
    if (!delegate && name.toLowerCase().contains("wait")) {
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDLE, "idle", "()V", false);
    }
    if (delegate) {
      int slot = 0;
      if (!isStatic) {
        mv.visitVarInsn(Opcodes.ALOAD, slot++);
      }
      for (Type t : args) {
        mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), slot);
        slot += t.getSize();
      }
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, implOwner, name, implDesc, false);
    } else {
      pushDefault(mv, ret);
    }
    mv.visitInsn(ret.getOpcode(Opcodes.IRETURN));
    mv.visitMaxs(0, 0);
  }

  /** Leaves 0, false, an empty string, an empty array or buffer, or null on the stack. */
  private static void pushDefault(MethodVisitor mv, Type type) {
    switch (type.getSort()) {
      case Type.VOID -> {}
      case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
      case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
      case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
      case Type.ARRAY -> {
        mv.visitInsn(Opcodes.ICONST_0);
        Type component = Type.getType(type.getDescriptor().substring(1));
        switch (component.getSort()) {
          case Type.BOOLEAN -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN);
          case Type.CHAR -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR);
          case Type.BYTE -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
          case Type.SHORT -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT);
          case Type.INT -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
          case Type.LONG -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
          case Type.FLOAT -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
          case Type.DOUBLE -> mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
          default -> mv.visitTypeInsn(Opcodes.ANEWARRAY, component.getInternalName());
        }
      }
      case Type.OBJECT -> {
        if (type.getInternalName().equals("java/lang/String")) {
          mv.visitLdcInsn("");
        } else if (type.getInternalName().equals("java/lang/foreign/MemorySegment")) {
          mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/foreign/MemorySegment", "NULL", "Ljava/lang/foreign/MemorySegment;");
        } else if (type.getInternalName().equals("java/nio/ByteBuffer")) {
          mv.visitInsn(Opcodes.ICONST_0);
          mv.visitMethodInsn(
              Opcodes.INVOKESTATIC, "java/nio/ByteBuffer", "allocate", "(I)Ljava/nio/ByteBuffer;", false);
        } else {
          mv.visitInsn(Opcodes.ACONST_NULL);
        }
      }
      default -> mv.visitInsn(Opcodes.ICONST_0);
    }
  }
}
