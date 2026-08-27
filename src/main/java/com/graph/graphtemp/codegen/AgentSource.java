package com.graph.graphtemp.codegen;

import com.graph.graphtemp.agent.AgentSpec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The one answer to "what is this agent's source?", shared by the code endpoint and the
 * runner so the tab a user edits is provably the file that runs.
 */
@Component
public class AgentSource {

    private final EditedCodeStore store;
    private final CodeGenerator generator;

    public AgentSource(EditedCodeStore store, CodeGenerator generator) {
        this.store = store;
        this.generator = generator;
    }

    /**
     * @param edited true once someone has saved an edit, meaning the spec form no
     *               longer decides what this agent does
     * @param digest identifies the source itself, so a cache can tell one revision from
     *               the next without holding the whole file as a key
     */
    public record Source(String code, String className, boolean edited, String digest) {}

    public Source of(AgentSpec spec) {
        return store.findCode(spec.id())
                .map(stored -> source(spec, stored, true))
                .orElseGet(() -> source(spec, generator.generate(spec), false));
    }

    private Source source(AgentSpec spec, String code, boolean edited) {
        return new Source(code, CodeGenerator.classNameFor(spec.name()), edited, digest(code));
    }

    private static String digest(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
