package dev.project516.frcdevwasm.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Compiles a zip of Java sources to a jar. Runs on the JVM's own javac. */
public final class Compiler {
  private Compiler() {}

  /** Returns {"success":bool,"diagnostics":[...]}. Never throws. */
  public static String compile(
      String sourcesZipPath, String classpath, String processorPath, String outJarPath) {
    // A thread per compile: processors that keep state in a ThreadLocal (avaje-jsonb) would
    // otherwise keep every earlier compile and its class loader alive.
    StringBuilder noResult = new StringBuilder();
    appendDiagnostic(noResult, "ERROR", "", 0, 0, "Internal compiler error: the compile thread ended without a result");
    String[] result = {"{\"success\":false,\"diagnostics\":[" + noResult + "]}"};
    Thread thread = new Thread(() -> result[0] = compileHere(sourcesZipPath, classpath, processorPath, outJarPath), "javac");
    thread.start();
    boolean interrupted = false;
    while (thread.isAlive()) {
      try {
        thread.join();
      } catch (InterruptedException e) {
        // keep waiting: the compile thread owns the output jar until it ends
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    return result[0];
  }

  private static String compileHere(
      String sourcesZipPath, String classpath, String processorPath, String outJarPath) {
    List<JavaFileObject> sources = new ArrayList<>();
    Map<String, byte[]> outputs = new LinkedHashMap<>();
    StringBuilder diagnostics = new StringBuilder();
    boolean success = false;
    File scratch = processorPath.isEmpty() ? null : new File(outJarPath + ".proc").getAbsoluteFile();
    try {
      if (scratch != null) {
        // A compile stopped by removing its JVM leaves the folder behind.
        delete(scratch);
        scratch.mkdirs();
      }
      readZip(sourcesZipPath, sources, outputs);
      JavaCompiler compiler = findCompiler();
      StandardJavaFileManager standard = compiler.getStandardFileManager(null, Locale.ENGLISH, StandardCharsets.UTF_8);
      try (OutputFileManager manager = new OutputFileManager(standard, outputs, scratch)) {
        DiagnosticListener<JavaFileObject> listener = diagnostic -> appendDiagnostic(diagnostics, diagnostic);
        List<String> options = new ArrayList<>(List.of("-classpath", classpath, "-encoding", "UTF-8", "-g", "-XDstringConcat=inline"));
        if (processorPath.isEmpty()) {
          options.add("-proc:none");
        } else {
          options.add("-processorpath");
          options.add(processorPath);
        }
        success = compiler.getTask(new StringWriter(), manager, listener, options, null, sources).call();
      }
      if (success) {
        writeJar(outJarPath, outputs);
      }
    } catch (Throwable t) {
      success = false;
      appendDiagnostic(diagnostics, "ERROR", "", 0, 0, "Internal compiler error: " + t);
    } finally {
      if (scratch != null) {
        delete(scratch);
      }
    }
    return "{\"success\":" + success + ",\"diagnostics\":[" + diagnostics + "]}";
  }

  public static void main(String[] args) {
    if (args.length < 3 || args.length > 4) {
      System.err.println("usage: Compiler <sources.zip> <classpath> <out.jar> [processorPath]");
      System.exit(2);
    }
    String json = compile(args[0], args[1], args.length == 4 ? args[3] : "", args[2]);
    System.out.println(json);
    System.exit(json.startsWith("{\"success\":true") ? 0 : 1);
  }

  private static JavaCompiler findCompiler() {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("no Java compiler available");
    }
    return compiler;
  }

  private static void readZip(String path, List<JavaFileObject> sources, Map<String, byte[]> outputs)
      throws IOException, URISyntaxException {
    byte[] buffer = new byte[16384];
    try (ZipInputStream zip = new ZipInputStream(new FileInputStream(path))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (entry.isDirectory()) {
          continue;
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int n = zip.read(buffer); n >= 0; n = zip.read(buffer)) {
          data.write(buffer, 0, n);
        }
        String name = entry.getName();
        if (name.endsWith(".java")) {
          sources.add(new Source(name, data.toByteArray()));
        } else {
          outputs.put(name, data.toByteArray());
        }
      }
    }
  }

  private static void delete(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        delete(child);
      }
    }
    file.delete();
  }

  private static void writeJar(String path, Map<String, byte[]> outputs) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    File parent = new File(path).getAbsoluteFile().getParentFile();
    if (parent != null) parent.mkdirs();
    try (OutputStream file = new FileOutputStream(path);
        JarOutputStream jar = new JarOutputStream(file, manifest)) {
      Set<String> directories = new HashSet<>();
      for (Map.Entry<String, byte[]> output : outputs.entrySet()) {
        String name = output.getKey();
        for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1)) {
          String directory = name.substring(0, slash + 1);
          if (directories.add(directory)) {
            jar.putNextEntry(new JarEntry(directory));
            jar.closeEntry();
          }
        }
        jar.putNextEntry(new JarEntry(name));
        jar.write(output.getValue());
        jar.closeEntry();
      }
    }
  }

  private static void appendDiagnostic(StringBuilder out, Diagnostic<? extends JavaFileObject> d) {
    JavaFileObject file = d.getSource();
    String name = file instanceof Source ? ((Source) file).name : "";
    appendDiagnostic(out, kind(d.getKind()), name, Math.max(0, d.getLineNumber()),
        Math.max(0, d.getColumnNumber()), d.getMessage(Locale.ENGLISH));
  }

  private static void appendDiagnostic(StringBuilder out, String kind, String file, long line, long column,
      String message) {
    if (out.length() > 0) {
      out.append(',');
    }
    out.append("{\"kind\":\"").append(kind).append("\",\"file\":");
    quote(out, file);
    out.append(",\"line\":").append(line).append(",\"column\":").append(column).append(",\"message\":");
    quote(out, message);
    out.append('}');
  }

  private static String kind(Diagnostic.Kind kind) {
    switch (kind) {
      case ERROR:
        return "ERROR";
      case WARNING:
      case MANDATORY_WARNING:
        return "WARNING";
      default:
        return "NOTE";
    }
  }

  // Non-ASCII is escaped so the JSON survives any stdout or string encoding.
  private static void quote(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        default:
          if (c < 0x20 || c > 0x7e) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    out.append('"');
  }

  private static final class Source extends SimpleJavaFileObject {
    final String name;
    private final byte[] bytes;

    Source(String name, byte[] bytes) throws URISyntaxException {
      super(new URI("mem", "", "/" + name, null), Kind.SOURCE);
      this.name = name;
      this.bytes = bytes;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  // Class files, generated sources and generated resources, kept in memory by jar entry name.
  private static final class Output extends SimpleJavaFileObject {
    private final String entryName;
    private final Map<String, byte[]> outputs;

    Output(URI uri, String entryName, Kind kind, Map<String, byte[]> outputs) {
      super(uri, kind);
      this.entryName = entryName;
      this.outputs = outputs;
    }

    @Override
    public OutputStream openOutputStream() {
      return new ByteArrayOutputStream() {
        @Override
        public void close() {
          outputs.put(entryName, toByteArray());
        }
      };
    }

    @Override
    public Writer openWriter() {
      return new OutputStreamWriter(openOutputStream(), StandardCharsets.UTF_8);
    }

    @Override
    public InputStream openInputStream() throws IOException {
      byte[] bytes = outputs.get(entryName);
      if (bytes == null) {
        throw new FileNotFoundException(entryName);
      }
      return new ByteArrayInputStream(bytes);
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
      return new String(openInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static final class OutputFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private final Map<String, byte[]> outputs;
    private final File scratch;

    // With a scratch folder, output URIs are file: URIs under it, which processors can turn into a Path.
    OutputFileManager(StandardJavaFileManager delegate, Map<String, byte[]> outputs, File scratch) {
      super(delegate);
      this.outputs = outputs;
      this.scratch = scratch;
    }

    @Override
    public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
        JavaFileObject.Kind kind, FileObject sibling) throws IOException {
      return output(location, className.replace('.', '/') + kind.extension, kind);
    }

    @Override
    public FileObject getFileForOutput(JavaFileManager.Location location, String packageName,
        String relativeName, FileObject sibling) throws IOException {
      String dir = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
      return output(location, dir + relativeName, JavaFileObject.Kind.OTHER);
    }

    private Output output(JavaFileManager.Location location, String entryName, JavaFileObject.Kind kind)
        throws IOException {
      try {
        URI uri = scratch == null
            ? new URI("mem", "", "/" + entryName, null)
            // Not File.toURI(): it checks the disk for every output, which is slow on CheerpJ's /files/.
            : new URI("file", null, scratch.getPath() + (location == StandardLocation.SOURCE_OUTPUT ? "/generated/" : "/classes/")
                + entryName, null);
        return new Output(uri, entryName, kind, outputs);
      } catch (URISyntaxException e) {
        throw new IOException(e);
      }
    }
  }
}
