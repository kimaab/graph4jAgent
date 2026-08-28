package com.graph.graphtemp.codegen;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.agent.Step;
import com.graph.graphtemp.tools.CalculatorTool;
import com.graph.graphtemp.tools.DocumentSearchTool;
import com.graph.graphtemp.tools.HttpGetTool;
import com.graph.graphtemp.tools.ToolRegistry;
import com.graph.graphtemp.tools.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles what the generator emits, with a real compiler, against the real
 * dependencies. String assertions cannot catch a broken escape or a constructor that
 * does not exist; this can, and both have happened.
 * <p>
 * Actually talking to a model is left to a manual run — see the README — since it needs
 * a reachable gateway.
 */
class GeneratedCodeCompilesTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static CodeGenerator generator() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new CalculatorTool(MAPPER), new HttpGetTool(MAPPER), new WebSearchTool(MAPPER),
                        new DocumentSearchTool()),
                MAPPER);
        return new CodeGenerator(registry, "http://gateway.example/v1", "test-key",
                "jdbc:postgresql://db.example:5432/postgres", "postgres", "secret");
    }

    @Test
    @DisplayName("react 에이전트로 생성한 코드가 실제로 컴파일된다")
    void reactAgentCompiles(@TempDir Path dir) throws IOException {
        AgentSpec spec = new AgentSpec(UUID.randomUUID(), "calc bot", "", "test-model",
                "너는 계산을 도와주는 조수다. \"따옴표\"와 역슬래시\\도 넣어본다.",
                List.of("calculator", "http_get", "web_search"),
                GraphType.REACT, List.of(), 5);

        assertCompiles(dir, "CalcBot.java", generator().generate(spec));
    }

    @Test
    @DisplayName("document_search 를 쓰는 에이전트가 컴파일되고 툴이 요구한 //DEPS 가 붙는다")
    void documentSearchAgentCompiles(@TempDir Path dir) throws IOException {
        AgentSpec spec = new AgentSpec(UUID.randomUUID(), "doc bot", "", "test-model",
                "첨부된 문서에서 근거를 찾아 답하라.", List.of("document_search"),
                GraphType.REACT, List.of(), 5);

        String source = generator().generate(spec);

        // The tool asks for the JDBC driver; without the //DEPS line a standalone run
        // would compile here and then fail to find a driver at runtime.
        assertThat(source).contains("//DEPS org.postgresql:postgresql:");
        assertThat(source).contains("static final String AGENT_ID = \"" + spec.id() + "\"");

        assertCompiles(dir, "DocBot.java", source);
    }

    @Test
    @DisplayName("linear 에이전트로 생성한 코드가 실제로 컴파일된다")
    void linearAgentCompiles(@TempDir Path dir) throws IOException {
        AgentSpec spec = new AgentSpec(UUID.randomUUID(), "summary chain", "", "test-model",
                "너는 간결한 조수다.", List.of(), GraphType.LINEAR,
                List.of(new Step("추출", "핵심 숫자만 뽑아라. \"이렇게\""),
                        new Step("설명", "한 문장으로 설명하라.")), 5);

        assertCompiles(dir, "SummaryChain.java", generator().generate(spec));
    }

    /** Writes the source out and runs javac over it, failing with the diagnostics. */
    private static void assertCompiles(Path dir, String fileName, String source) throws IOException {
        Path file = dir.resolve(fileName);
        Files.writeString(file, source, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("a JDK is required to run this test, not just a JRE")
                .isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            boolean ok = compiler.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"),
                            "-d", dir.resolve("classes").toString()),
                    null,
                    files.getJavaFileObjects(file)).call();

            String errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> "  line " + d.getLineNumber() + ": " + d.getMessage(null))
                    .collect(Collectors.joining("\n"));

            assertThat(ok).as("generated %s failed to compile:%n%s", fileName, errors).isTrue();
        }
    }
}
