package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.agent.Step;
import com.graph.graphtemp.codegen.AgentSource;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a spec back out of an agent's source, so an edited file can be applied onto the
 * 정의 form instead of the form only ever overwriting the file.
 * <p>
 * It reflects over the compiled class rather than parsing the text. The values it wants
 * are the constants the template declares — {@code MODEL}, {@code SYSTEM_PROMPT},
 * {@code MAX_ITERATIONS}, {@code STEP_PROMPTS} — plus whatever {@code tools()} returns,
 * and reading them off a loaded class costs nothing beyond the compile the flow tab
 * already pays for, while a parser would have to re-implement Java string literals.
 * <p>
 * What it cannot recover is recorded in {@link SourceSpec}.
 */
@Component
public class SourceSpecReader {

    private static final String MODEL = "MODEL";
    private static final String SYSTEM_PROMPT = "SYSTEM_PROMPT";
    private static final String MAX_ITERATIONS = "MAX_ITERATIONS";
    /** Present only in the linear template, which is how graph type is told apart. */
    private static final String STEP_PROMPTS = "STEP_PROMPTS";

    private final AgentSource source;
    private final AgentCodeCompiler compiler;

    public SourceSpecReader(AgentSource source, AgentCodeCompiler compiler) {
        this.source = source;
        this.compiler = compiler;
    }

    public SourceSpec read(AgentSpec spec) {
        AgentSource.Source src = source.of(spec);
        Class<?> agent = compiler.compile(src.className(), src.code(), src.digest());

        List<String> prompts = stepPrompts(agent);
        // The linear template is the only one that declares STEP_PROMPTS, so its
        // presence identifies the graph type more reliably than inspecting the nodes.
        GraphType graphType = prompts == null ? GraphType.REACT : GraphType.LINEAR;

        return new SourceSpec(
                string(agent, MODEL),
                string(agent, SYSTEM_PROMPT),
                integer(agent, MAX_ITERATIONS),
                AgentTools.namesOf(agent),
                graphType,
                steps(prompts, spec));
    }

    /**
     * Pairs each recovered prompt with a name. Names are not in the file, so the current
     * spec's name at the same position is reused where there is one — renaming steps is
     * common and losing those names on every apply would be its own annoyance.
     */
    private static List<Step> steps(List<String> prompts, AgentSpec spec) {
        if (prompts == null) {
            return List.of();
        }
        List<Step> steps = new ArrayList<>(prompts.size());
        for (int i = 0; i < prompts.size(); i++) {
            String name = i < spec.steps().size() ? spec.steps().get(i).name() : null;
            steps.add(new Step(name == null || name.isBlank() ? "단계 " + (i + 1) : name,
                    prompts.get(i)));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stepPrompts(Class<?> agent) {
        Object value = field(agent, STEP_PROMPTS);
        return value instanceof List<?> list ? List.copyOf((List<String>) list) : null;
    }

    private static String string(Class<?> agent, String name) {
        return field(agent, name) instanceof String s ? s : null;
    }

    private static Integer integer(Class<?> agent, String name) {
        return field(agent, name) instanceof Integer i ? i : null;
    }

    /** Null when an edited file no longer declares the constant, which is allowed. */
    private static Object field(Class<?> agent, String name) {
        try {
            Field field = agent.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
