package dev.project516.frcdevwasm.gen;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Rewrites WPILib classes that declare native methods or load native libraries. Every native
 * becomes a call to a handwritten dev.project516.frcdevwasm.shim.*Impl method, or a warning stub when the Impl
 * method does not exist. Everything else in the source is kept as is.
 *
 * <p>Usage: Generator [--root packagePrefix] --classpath cp --impl implClassesDir --out outDir
 * --report file src.jar... The root (default edu.wpi.first.) is dropped from a class's package to
 * name its Impl package.
 */
public final class Generator {
  private static final String IMPL_ROOT = "dev.project516.frcdevwasm.shim.";

  private record Edit(int start, int end, String text) {}

  private record Source(String path, String text) {}

  private record Result(String className, int total, List<String> missing) {}

  private static final class Mem extends SimpleJavaFileObject {
    private final String text;

    Mem(String path, String text) {
      super(URI.create("mem:///" + path), Kind.SOURCE);
      this.text = text;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return text;
    }
  }

  private final ClassLoader implLoader;
  private final String wpilibRoot;
  private final List<Result> results = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();

  private Generator(ClassLoader implLoader, String wpilibRoot) {
    this.implLoader = implLoader;
    this.wpilibRoot = wpilibRoot;
  }

  public static void main(String[] args) throws Exception {
    String classpath = "";
    String root = "edu.wpi.first.";
    Path impl = null;
    Path out = null;
    Path report = null;
    List<Path> jars = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--root" -> root = args[++i];
        case "--classpath" -> classpath = args[++i];
        case "--impl" -> impl = Path.of(args[++i]);
        case "--out" -> out = Path.of(args[++i]);
        case "--report" -> report = Path.of(args[++i]);
        default -> jars.add(Path.of(args[i]));
      }
    }
    List<URL> urls = new ArrayList<>();
    if (impl != null) {
      urls.add(impl.toUri().toURL());
    }
    for (String entry : classpath.split(File.pathSeparator)) {
      if (!entry.isEmpty()) {
        urls.add(new File(entry).toURI().toURL());
      }
    }
    ClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);
    Generator g = new Generator(loader, root);
    g.run(classpath, jars, out, report);
  }

  private void run(String classpath, List<Path> jars, Path out, Path report) throws IOException {
    List<Source> sources = new ArrayList<>();
    for (Path jar : jars) {
      try (ZipFile zf = new ZipFile(jar.toFile())) {
        for (Enumeration<? extends ZipEntry> en = zf.entries(); en.hasMoreElements(); ) {
          ZipEntry e = en.nextElement();
          String name = e.getName();
          if (!name.endsWith(".java") || name.endsWith("RuntimeLoader.java") || name.endsWith("module-info.java")) {
            continue;
          }
          String text = new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
          if (text.contains("native ") || text.contains("RuntimeLoader")) {
            sources.add(new Source(name, text));
          }
        }
      }
    }
    sources.sort(Comparator.comparing(Source::path));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
    StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null);
    List<JavaFileObject> units = new ArrayList<>();
    for (Source s : sources) {
      units.add(new Mem(s.path(), s.text()));
    }
    JavacTask task =
        (JavacTask)
            compiler.getTask(
                null,
                fm,
                diags,
                List.of("-proc:none", "-implicit:none", "-nowarn", "-classpath", classpath),
                null,
                units);
    Iterable<? extends CompilationUnitTree> trees = task.parse();
    task.analyze();
    boolean failed = false;
    for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
      if (d.getKind() == Diagnostic.Kind.ERROR) {
        System.err.println(d);
        failed = true;
      }
    }
    if (failed) {
      throw new IllegalStateException("analysis of WPILib sources failed");
    }

    int written = 0;
    for (CompilationUnitTree cu : trees) {
      String text = cu.getSourceFile().getCharContent(true).toString();
      Rewriter rw = new Rewriter(task, cu, text);
      rw.scan(cu, null);
      if (rw.edits.isEmpty()) {
        continue;
      }
      if (!rw.imports.isEmpty()) {
        int at = (int) rw.positions.getEndPosition(cu, cu.getPackage());
        StringBuilder sb = new StringBuilder("\n");
        for (String i : rw.imports) {
          sb.append("\nimport ").append(i).append(';');
        }
        rw.edits.add(new Edit(at, at, sb.toString()));
      }
      Path dest = out.resolve(cu.getSourceFile().toUri().getPath().substring(1));
      Files.createDirectories(dest.getParent());
      Files.writeString(dest, apply(text, rw.edits));
      written++;
    }

    int total = 0;
    int implemented = 0;
    StringBuilder sb = new StringBuilder();
    results.sort(Comparator.comparing(Result::className));
    for (Result r : results) {
      total += r.total();
      implemented += r.total() - r.missing().size();
      sb.append(r.className())
          .append(' ')
          .append(r.total() - r.missing().size())
          .append('/')
          .append(r.total())
          .append('\n');
      for (String m : r.missing()) {
        sb.append("  missing ").append(m).append('\n');
      }
    }
    for (String w : warnings) {
      sb.append("warning ").append(w).append('\n');
    }
    sb.append("TOTAL ").append(implemented).append('/').append(total).append('\n');
    if (report != null) {
      Files.createDirectories(report.toAbsolutePath().getParent());
      Files.writeString(report, sb.toString());
    }
    System.out.println(
        "generated "
            + written
            + " classes, "
            + implemented
            + " of "
            + total
            + " natives implemented");
  }

  private static String apply(String text, List<Edit> edits) {
    List<Edit> sorted = new ArrayList<>(edits);
    sorted.sort(Comparator.comparingInt(Edit::start).reversed());
    StringBuilder sb = new StringBuilder(text);
    for (Edit e : sorted) {
      sb.replace(e.start(), e.end(), e.text());
    }
    return sb.toString();
  }

  private final class Rewriter extends TreePathScanner<Void, Void> {
    final List<Edit> edits = new ArrayList<>();
    final java.util.Set<String> imports = new java.util.TreeSet<>();
    private final JavacTask task;
    private final CompilationUnitTree cu;
    private final String text;
    final SourcePositions positions;
    private final Trees trees;
    private final java.util.Map<String, List<String>> missingByClass = new TreeMap<>();
    private final java.util.Map<String, Integer> totalByClass = new TreeMap<>();

    Rewriter(JavacTask task, CompilationUnitTree cu, String text) {
      this.task = task;
      this.cu = cu;
      this.text = text;
      this.trees = Trees.instance(task);
      this.positions = trees.getSourcePositions();
    }

    @Override
    public Void scan(Tree tree, Void unused) {
      Void v = super.scan(tree, unused);
      if (tree == cu) {
        for (var e : totalByClass.entrySet()) {
          results.add(new Result(e.getKey(), e.getValue(), missingByClass.get(e.getKey())));
        }
      }
      return v;
    }

    private String slice(Tree t) {
      return text.substring(start(t), end(t));
    }

    private int start(Tree t) {
      return (int) positions.getStartPosition(cu, t);
    }

    private int end(Tree t) {
      return (int) positions.getEndPosition(cu, t);
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
      for (Tree member : node.getMembers()) {
        if (member instanceof BlockTree b && b.isStatic() && loadsLibrary(slice(b))) {
          edits.add(new Edit(start(b), end(b), ""));
        }
      }
      return super.visitClass(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
      if (node.getModifiers().getFlags().contains(Modifier.NATIVE)) {
        nativeMethod(node);
      } else if (node.getBody() != null
          && end(node.getBody()) >= 0
          && loadsLibrary(slice(node.getBody()))) {
        TreePath path = getCurrentPath();
        ExecutableElement e = (ExecutableElement) trees.getElement(path);
        if (e.getReturnType().getKind() != TypeKind.VOID) {
          warnings.add("cannot stub library loading in " + e);
        } else {
          edits.add(new Edit(start(node.getBody()), end(node.getBody()), "{}"));
        }
      }
      return null;
    }

    private boolean loadsLibrary(String code) {
      return code.contains("RuntimeLoader") || code.contains("loadLibrary");
    }

    private boolean hasHiddenType(TypeMirror t) {
      return t.getKind() == TypeKind.DECLARED
          && !task.getTypes().asElement(t).getModifiers().contains(Modifier.PUBLIC);
    }

    private void nativeMethod(MethodTree node) {
      TreePath path = getCurrentPath();
      ExecutableElement e = (ExecutableElement) trees.getElement(path);
      TypeElement owner = (TypeElement) e.getEnclosingElement();
      String binary = task.getElements().getBinaryName(owner).toString();
      String simple = owner.getSimpleName().toString();
      String implName = implName(binary);
      boolean isStatic = e.getModifiers().contains(Modifier.STATIC);

      List<String> paramTypes = new ArrayList<>();
      List<String> args = new ArrayList<>();
      if (!isStatic) {
        paramTypes.add(binary.replace('$', '.'));
        args.add("this");
      }
      int i = 0;
      for (VariableTree p : node.getParameters()) {
        paramTypes.add(task.getTypes().erasure(e.getParameters().get(i++).asType()).toString());
        args.add(p.getName().toString());
      }
      String returnType = task.getTypes().erasure(e.getReturnType()).toString();
      // An Impl cannot name a non-public return type, so it returns Object and the call casts.
      boolean hiddenReturn = hasHiddenType(e.getReturnType());
      boolean exists =
          implExists(
              implName,
              e.getSimpleName().toString(),
              paramTypes,
              hiddenReturn ? "java.lang.Object" : returnType);

      String body;
      boolean isVoid = e.getReturnType().getKind() == TypeKind.VOID;
      if (exists) {
        imports.add(implName);
        String call =
            implName.substring(implName.lastIndexOf('.') + 1)
                + "."
                + e.getSimpleName()
                + "("
                + String.join(", ", args)
                + ")";
        if (hiddenReturn) {
          call = "(" + returnType + ") " + call;
        }
        body = isVoid ? call + ";" : "return " + call + ";";
      } else {
        imports.add(IMPL_ROOT + "Unimplemented");
        body =
            "Unimplemented.warn(\""
                + simple
                + "."
                + e.getSimpleName()
                + "\");"
                + (isVoid ? "" : "\n    return " + defaultValue(e.getReturnType()) + ";");
      }

      String header = slice(node);
      header = header.substring(0, header.lastIndexOf(';'));
      header = header.replaceFirst("\\bnative\\b\\s*", "");
      edits.add(new Edit(start(node), end(node), header + " {\n    " + body + "\n  }"));

      totalByClass.merge(binary, 1, Integer::sum);
      missingByClass.computeIfAbsent(binary, k -> new ArrayList<>());
      if (!exists) {
        missingByClass
            .get(binary)
            .add(e.getSimpleName() + "(" + String.join(", ", paramTypes) + ")");
      }
    }
  }

  private static String defaultValue(TypeMirror t) {
    return switch (t.getKind()) {
      case BOOLEAN -> "false";
      case LONG -> "0L";
      case FLOAT -> "0f";
      case DOUBLE -> "0.0";
      case BYTE, SHORT, CHAR, INT -> "0";
      default -> "null";
    };
  }

  private String implName(String binaryName) {
    int dot = binaryName.lastIndexOf('.');
    String pkg = binaryName.substring(0, dot);
    String simple = binaryName.substring(dot + 1);
    if (pkg.startsWith(wpilibRoot)) {
      pkg = pkg.substring(wpilibRoot.length());
    }
    return IMPL_ROOT + pkg + "." + simple + "Impl";
  }

  private boolean implExists(
      String implName, String method, List<String> paramTypes, String returnType) {
    Class<?> impl;
    try {
      impl = Class.forName(implName, false, implLoader);
    } catch (ClassNotFoundException e) {
      return false;
    } catch (LinkageError e) {
      throw new IllegalStateException("cannot load " + implName, e);
    }
    for (java.lang.reflect.Method m : impl.getDeclaredMethods()) {
      int mod = m.getModifiers();
      if (!m.getName().equals(method)
          || !java.lang.reflect.Modifier.isPublic(mod)
          || !java.lang.reflect.Modifier.isStatic(mod)
          || m.getParameterCount() != paramTypes.size()
          || !m.getReturnType().getCanonicalName().equals(returnType)) {
        continue;
      }
      Class<?>[] ps = m.getParameterTypes();
      boolean same = true;
      for (int i = 0; i < ps.length && same; i++) {
        same = ps[i].getCanonicalName().equals(paramTypes.get(i));
      }
      if (same) {
        return true;
      }
    }
    return false;
  }
}
