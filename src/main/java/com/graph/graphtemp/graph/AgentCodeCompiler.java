package com.graph.graphtemp.graph;

import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles an agent's Java source in memory and hands back the loaded class.
 * <p>
 * Results are cached on the source's digest, so an unchanged file is compiled once
 * however many times it runs, and saving an edit produces a genuinely new class rather
 * than a stale one.
 * <p>
 * The generated file is compiled against this server's own classpath, which is what
 * makes the exported agent and the studio run the same langgraph4j and Spring AI. That
 * relies on {@code java.class.path} being the real classpath: it is when the app runs
 * from an IDE or an exploded classpath, but not from a Spring Boot fat jar, where the
 * dependencies live inside the archive and javac cannot see them.
 */
@Component
public class AgentCodeCompiler {

    /** Set this when the launcher hides the real classpath from java.class.path. */
    static final String CLASSPATH_OVERRIDE = "agent.compile-classpath";

    private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();

    /** @param digest identifies the source; see {@code AgentSource}. */
    public Class<?> compile(String className, String source, String digest) {
        return cache.computeIfAbsent(digest, key -> load(className, source));
    }

    private Class<?> load(String className, String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new CodeCompilationException(
                    "no Java compiler available: the server is running on a JRE, but compiling "
                            + "an agent's source needs a JDK",
                    List.of());
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Map<String, ByteArrayOutputStream> classes = new HashMap<>();

        try (StandardJavaFileManager standard =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
             InMemoryFileManager files = new InMemoryFileManager(standard, classes)) {

            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("-classpath", classpath()),
                    null,
                    List.of(new SourceFile(className, source))).call();

            if (!ok) {
                throw new CodeCompilationException(
                        "the agent's source does not compile", errors(diagnostics));
            }
        } catch (IOException e) {
            throw new CodeCompilationException("failed to compile the agent's source: " + e.getMessage(),
                    List.of());
        }

        Map<String, byte[]> bytecode = new HashMap<>();
        classes.forEach((name, bytes) -> bytecode.put(name, bytes.toByteArray()));

        try {
            return new ByteArrayClassLoader(bytecode, getClass().getClassLoader())
                    .loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new CodeCompilationException(
                    "compiled fine but produced no class named " + className
                            + "; the public class must match the agent's name",
                    List.of());
        }
    }

    /**
     * What javac compiles the agent against: this server's own classpath, so the studio
     * and the exported file share one langgraph4j and one Spring AI.
     * <p>
     * {@code java.class.path} can be empty — a launcher that shortens a long classpath,
     * or a module-path launch, leaves it unset. Reading it blindly used to produce a
     * {@code NullPointerException} with no message from {@code List.of}, which said
     * nothing about the real problem. {@code agent.compile-classpath} is the escape
     * hatch when the launcher hides it.
     */
    private static String classpath() {
        String override = System.getProperty(CLASSPATH_OVERRIDE);
        if (override != null && !override.isBlank()) {
            return override;
        }
        String property = System.getProperty("java.class.path");
        if (property != null && !property.isBlank()) {
            return property;
        }
        throw new CodeCompilationException(
                "the server cannot tell what its own classpath is, so it cannot compile the "
                        + "agent. This happens when the JVM is launched with a shortened or "
                        + "module-only classpath. Set -D" + CLASSPATH_OVERRIDE + "=<classpath> "
                        + "to point the compiler at the same jars this server runs on",
                List.of());
    }

    private static List<String> errors(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                errors.add("line " + d.getLineNumber() + ": " + d.getMessage(null));
            }
        }
        return List.copyOf(errors);
    }

    /** The source handed to javac, held as a string rather than written to disk. */
    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String className, String source) {
            super(URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /** Collects javac's output as byte arrays instead of .class files. */
    private static final class InMemoryFileManager
            extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, ByteArrayOutputStream> classes;

        InMemoryFileManager(StandardJavaFileManager delegate, Map<String, ByteArrayOutputStream> classes) {
            super(delegate);
            this.classes = classes;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            classes.put(className, bytes);
            return new SimpleJavaFileObject(
                    URI.create("bytes:///" + className + kind.extension), kind) {
                @Override
                public OutputStream openOutputStream() {
                    return bytes;
                }
            };
        }
    }

    /**
     * Loads the freshly compiled classes and nothing else, delegating everything the
     * generated file imports to the server's own loader so both sides share one
     * langgraph4j and one Spring AI.
     */
    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> bytecode;

        ByteArrayClassLoader(Map<String, byte[]> bytecode, ClassLoader parent) {
            super(parent);
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = bytecode.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
