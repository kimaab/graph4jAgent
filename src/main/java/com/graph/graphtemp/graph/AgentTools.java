package com.graph.graphtemp.graph;

import org.springframework.ai.tool.ToolCallback;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reads the tool names an agent's compiled source really registers, by calling the
 * {@code tools()} the template declares.
 * <p>
 * This is what makes a tool deleted from the code disappear from the studio: the spec's
 * own tool list does not move when someone edits the file, so anything derived from it
 * would keep showing a tool that no longer runs.
 */
final class AgentTools {

    /** The generated file's tool list; see {@code templates/agent.java.template}. */
    private static final String TOOLS = "tools";

    private AgentTools() {}

    static List<String> namesOf(Class<?> agent) {
        Method method;
        try {
            method = agent.getDeclaredMethod(TOOLS);
        } catch (NoSuchMethodException e) {
            // An edited file may drop the method entirely; it just has no tools.
            return List.of();
        }
        method.setAccessible(true);

        try {
            return callbacks(method).stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList();
        } catch (InvocationTargetException | IllegalAccessException | ClassCastException e) {
            throw new GraphBuildException("the agent's " + TOOLS + "() could not be read: " + e, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCallback> callbacks(Method method)
            throws InvocationTargetException, IllegalAccessException {
        return (List<ToolCallback>) method.invoke(null);
    }
}
