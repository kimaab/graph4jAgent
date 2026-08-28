package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.codegen.CodeGenerator;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Compiles one throwaway agent at boot, so a server that cannot build agents says so
 * immediately instead of on someone's first run.
 * <p>
 * The check earns its keep because of a coupling that is easy to miss: the server
 * compiles generated source against its own runtime classpath, so every library the
 * template imports has to be a runtime dependency — even ones the server itself never
 * calls. {@code openai-java-client-okhttp} was test-scoped, which left the whole test
 * suite green while every real run failed to compile, because tests run with test-scoped
 * jars on the classpath and the server does not.
 */
@Component
public class StartupCompileCheck {

    private final CodeGenerator generator;
    private final AgentCodeCompiler compiler;

    StartupCompileCheck(CodeGenerator generator, AgentCodeCompiler compiler) {
        this.generator = generator;
        this.compiler = compiler;
    }

    @PostConstruct
    void verifyGeneratedCodeCompiles() {
        // No tools: this is about the agent scaffold and its imports. A tool that drags
        // in its own library is covered by the same compile, once an agent selects it.
        AgentSpec probe = new AgentSpec(UUID.randomUUID(), "Startup Check", "",
                "startup-check-model", "", List.of(), GraphType.REACT, List.of(), 1);

        String source = generator.generate(probe);
        try {
            compiler.compile("StartupCheck", source, "startup-check:" + source.hashCode());
        } catch (CodeCompilationException e) {
            throw new IllegalStateException(
                    "the server cannot compile the agents it generates, so no agent could run. "
                            + "Every library the code template imports must be a runtime "
                            + "dependency of this server, not test-scoped.\n" + e.getMessage(), e);
        }
    }
}
