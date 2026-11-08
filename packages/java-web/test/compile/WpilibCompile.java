import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import javax.tools.DiagnosticListener;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

// WpilibCompile CLASSPATH PROCESSORPATH|- SRCDIR OUTDIR: compiles robot code with the options the IDE uses,
// printing diagnostics and a checksum of each output file. Output goes to a directory because annotation
// processors (avaje) turn Filer URIs into Paths.
public class WpilibCompile {
  public static void main(String[] args) throws Exception {
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager std = javac.getStandardFileManager(null, Locale.ROOT, null);
    Path out = Path.of(args[3]);
    Files.createDirectories(out.resolve("gen"));
    std.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(out));
    std.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(out.resolve("gen")));
    JavaFileManager fm = new ForwardingJavaFileManager<>(std) {};

    List<String> options = new ArrayList<>(List.of("-classpath", args[0], "-encoding", "UTF-8", "-g", "-XDstringConcat=inline"));
    if (args[1].equals("-")) options.add("-proc:none");
    else options.addAll(List.of("-processorpath", args[1]));
    List<Path> sources;
    try (var files = Files.walk(Path.of(args[2]))) {
      sources = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }

    DiagnosticListener<JavaFileObject> listener = d -> System.out.println(d.getKind() + " "
        + (d.getSource() == null ? "-" : d.getSource().getName()) + ":" + d.getLineNumber() + ":" + d.getColumnNumber()
        + " " + d.getCode() + ": " + d.getMessage(Locale.ROOT));
    boolean ok = javac.getTask(null, fm, listener, options, null, std.getJavaFileObjectsFromPaths(sources)).call();
    System.out.println("compiled: " + ok);
    try (var files = Files.walk(out)) {
      for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
        byte[] b = Files.readAllBytes(p);
        CRC32 crc = new CRC32();
        crc.update(b);
        System.out.printf("%s %d %08x%n", out.relativize(p), b.length, crc.getValue());
      }
    }
  }
}
