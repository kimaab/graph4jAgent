package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.codegen.AgentSource;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs an agent by compiling the very source its code tab shows.
 * <p>
 * This replaced a builder that assembled the graph from the spec directly. Two paths to
 * one behaviour meant the exported file and the studio could drift, and twice they did;
 * now there is only the file.
 * <p>
 * The generated class is reached by reflection rather than through a shared interface on
 * purpose: an interface would have to be on its classpath, which would stop the exported
 * file from running standalone under JBang.
 */
@Component
public class AgentGraphs {

    /** The method every generated agent exposes; see {@code templates/graph-*.template}. */
    private static final String ENTRY_POINT = "buildGraph";

    private final ChatModel chatModel;
    private final AgentSource source;
    private final AgentCodeCompiler compiler;

    /**
     * Keyed by source digest and streaming mode, not by agent id: a compiled graph owns
     * its own MemorySaver, so reusing the instance is what keeps a thread's history
     * alive between turns. Editing the code deliberately starts a fresh conversation.
     */
    private final Map<Key, CompiledGraph<MessagesState<Message>>> graphs = new ConcurrentHashMap<>();

    private record Key(String digest, boolean streaming) {}

    public AgentGraphs(ChatModel chatModel, AgentSource source, AgentCodeCompiler compiler) {
        this.chatModel = chatModel;
        this.source = source;
        this.compiler = compiler;
    }

    /** Agent replies arrive whole. */
    public CompiledGraph<MessagesState<Message>> build(AgentSpec spec) {
        return build(spec, false);
    }

    /** Agent replies arrive as {@code StreamingOutput} chunks, for SSE token events. */
    public CompiledGraph<MessagesState<Message>> buildStreaming(AgentSpec spec) {
        return build(spec, true);
    }

    private CompiledGraph<MessagesState<Message>> build(AgentSpec spec, boolean streaming) {
        AgentSource.Source src = source.of(spec);
        Class<?> agent = compiler.compile(src.className(), src.code(), src.digest());
        return graphs.computeIfAbsent(new Key(src.digest(), streaming),
                key -> invokeEntryPoint(agent, streaming));
    }

    @SuppressWarnings("unchecked")
    private CompiledGraph<MessagesState<Message>> invokeEntryPoint(Class<?> agent, boolean streaming) {
        Method entry;
        try {
            entry = agent.getDeclaredMethod(ENTRY_POINT, ChatModel.class, boolean.class);
        } catch (NoSuchMethodException e) {
            throw new GraphBuildException(
                    "the agent's source has no `static CompiledGraph<State> " + ENTRY_POINT
                            + "(ChatModel, boolean)`; the studio calls that method to run it", e);
        }
        // Generated agents declare the entry point package-private, and this caller is
        // not in their (unnamed) package.
        entry.setAccessible(true);

        try {
            // Safe by construction: every template returns CompiledGraph over a State
            // that extends MessagesState<Message>, and callers only read messages off it.
            return (CompiledGraph<MessagesState<Message>>) entry.invoke(null, chatModel, streaming);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new GraphBuildException("the agent's " + ENTRY_POINT + " threw: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new GraphBuildException("cannot call the agent's " + ENTRY_POINT, e);
        }
    }
}
