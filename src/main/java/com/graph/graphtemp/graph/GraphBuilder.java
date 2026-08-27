package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.Step;
import com.graph.graphtemp.tools.ToolRegistry;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.async.AsyncGenerator;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.spring.ai.serializer.std.SpringAIStateSerializer;
import org.bsc.langgraph4j.spring.ai.generators.StreamingChatGenerator;
import org.bsc.langgraph4j.spring.ai.tool.SpringAIToolService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;

/**
 * Turns an {@link AgentSpec} into a runnable graph.
 * <p>
 * Compiled graphs are cached on the spec's own content, so editing a spec
 * transparently produces a new graph while an unchanged spec reuses the old one.
 */
@Component
public class GraphBuilder {

    static final String AGENT_NODE = "agent";
    static final String TOOLS_NODE = "tools";

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final MemorySaver checkpointSaver = new MemorySaver();
    private final Map<CacheKey, CompiledGraph<AgentGraphState>> cache = new ConcurrentHashMap<>();

    /** Streaming changes how the agent node emits, so it is part of the graph identity. */
    private record CacheKey(int specHash, boolean streaming) {}

    public GraphBuilder(ChatModel chatModel, ToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
    }

    /** Cached by spec content; a changed spec rebuilds. Agent replies arrive whole. */
    public CompiledGraph<AgentGraphState> build(AgentSpec spec) {
        return build(spec, false);
    }

    /** Agent replies arrive as {@code StreamingOutput} chunks, for SSE token events. */
    public CompiledGraph<AgentGraphState> buildStreaming(AgentSpec spec) {
        return build(spec, true);
    }

    private CompiledGraph<AgentGraphState> build(AgentSpec spec, boolean streaming) {
        return cache.computeIfAbsent(new CacheKey(spec.hashCode(), streaming),
                key -> compile(spec, streaming));
    }

    public void evict(AgentSpec spec) {
        cache.remove(new CacheKey(spec.hashCode(), false));
        cache.remove(new CacheKey(spec.hashCode(), true));
    }

    private CompiledGraph<AgentGraphState> compile(AgentSpec spec, boolean streaming) {
        try {
            StateGraph<AgentGraphState> graph = switch (spec.graphType()) {
                case REACT -> reactGraph(spec, streaming);
                case LINEAR -> linearGraph(spec, streaming);
            };
            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .recursionLimit(recursionLimitFor(spec))
                    .build());
        } catch (GraphStateException e) {
            throw new GraphBuildException("failed to build graph for agent " + spec.name(), e);
        }
    }

    /** agent -> (tool calls? tools -> agent : END) */
    private StateGraph<AgentGraphState> reactGraph(AgentSpec spec, boolean streaming)
            throws GraphStateException {
        List<ToolCallback> callbacks = toolRegistry.resolve(spec.tools());
        SpringAIToolService toolService = new SpringAIToolService(callbacks);

        return newGraph()
                .addNode(AGENT_NODE, agentNode(spec, callbacks, streaming, AGENT_NODE))
                .addNode(TOOLS_NODE, (AsyncNodeAction<AgentGraphState>) state -> {
                    AssistantMessage last = lastAssistant(state);
                    return toolService.executeFunctions(last.getToolCalls(), Map.of())
                            .thenApply(result -> Map.of(
                                    MessagesState.MESSAGES_STATE,
                                    (Object) ToolResponseMessage.builder()
                                            .responses(result.toolResponses())
                                            .build()));
                })
                .addEdge(StateGraph.START, AGENT_NODE)
                .addConditionalEdges(AGENT_NODE,
                        edge_async(state -> lastAssistant(state).hasToolCalls() ? TOOLS_NODE : StateGraph.END),
                        Map.of(TOOLS_NODE, TOOLS_NODE, StateGraph.END, StateGraph.END))
                .addEdge(TOOLS_NODE, AGENT_NODE);
    }

    /** step0 -> step1 -> ... -> END; each step sees everything produced before it. */
    private StateGraph<AgentGraphState> linearGraph(AgentSpec spec, boolean streaming)
            throws GraphStateException {
        StateGraph<AgentGraphState> graph = newGraph();
        List<Step> steps = spec.steps();
        List<String> nodeNames = new ArrayList<>(steps.size());

        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            String nodeName = "step_" + i + "_" + sanitise(step.name());
            nodeNames.add(nodeName);
            graph.addNode(nodeName, stepNode(spec, step, streaming, nodeName));
        }

        graph.addEdge(StateGraph.START, nodeNames.getFirst());
        for (int i = 0; i < nodeNames.size() - 1; i++) {
            graph.addEdge(nodeNames.get(i), nodeNames.get(i + 1));
        }
        graph.addEdge(nodeNames.getLast(), StateGraph.END);
        return graph;
    }

    /**
     * The agent turn. Streaming hands the graph a generator that emits one
     * {@link org.bsc.langgraph4j.streaming.StreamingOutput} per model chunk and
     * folds the finished reply into the state; otherwise the reply lands whole.
     */
    private AsyncNodeAction<AgentGraphState> agentNode(
            AgentSpec spec, List<ToolCallback> callbacks, boolean streaming, String nodeName) {
        return state -> {
            List<Message> messages = promptMessages(spec, state.messages());
            if (!streaming) {
                return completedFuture(Map.of(MessagesState.MESSAGES_STATE,
                        callModel(spec, callbacks, messages)));
            }
            return completedFuture(Map.of(MessagesState.MESSAGES_STATE,
                    streamModel(spec, callbacks, messages, state, nodeName)));
        };
    }

    /** One step of a linear graph: append its prompt, then the model's answer. */
    private AsyncNodeAction<AgentGraphState> stepNode(
            AgentSpec spec, Step step, boolean streaming, String nodeName) {
        return state -> {
            UserMessage stepPrompt = new UserMessage(step.prompt());
            List<Message> history = new ArrayList<>(state.messages());
            history.add(stepPrompt);
            List<Message> messages = promptMessages(spec, history);

            if (!streaming) {
                return completedFuture(Map.of(MessagesState.MESSAGES_STATE,
                        List.of(stepPrompt, callModel(spec, List.of(), messages))));
            }
            // The prompt has to be in the state before the generator folds in the reply,
            // otherwise the two updates race and the prompt is lost.
            return completedFuture(Map.of(MessagesState.MESSAGES_STATE,
                    List.of(stepPrompt, streamModel(spec, List.of(), messages, state, nodeName))));
        };
    }

    private AsyncGenerator<? extends org.bsc.langgraph4j.NodeOutput<AgentGraphState>> streamModel(
            AgentSpec spec, List<ToolCallback> callbacks, List<Message> messages,
            AgentGraphState state, String nodeName) {
        Flux<ChatResponse> flux = chatModel.stream(new Prompt(messages, optionsFor(spec, callbacks)));
        return StreamingChatGenerator.<AgentGraphState>builder()
                .startingNode(nodeName)
                .startingState(state)
                .mapResult(response -> Map.of(
                        MessagesState.MESSAGES_STATE, response.getResult().getOutput()))
                .build(flux);
    }

    /** Prepends the system prompt, when the spec has one. */
    private static List<Message> promptMessages(AgentSpec spec, List<Message> history) {
        List<Message> messages = new ArrayList<>();
        if (!spec.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(spec.systemPrompt()));
        }
        messages.addAll(history);
        return messages;
    }

    private StateGraph<AgentGraphState> newGraph() {
        return new StateGraph<>(MessagesState.SCHEMA,
                new SpringAIStateSerializer<>(AgentGraphState::new));
    }

    private AssistantMessage callModel(AgentSpec spec, List<ToolCallback> callbacks, List<Message> messages) {
        return chatModel.call(new Prompt(messages, optionsFor(spec, callbacks)))
                .getResult()
                .getOutput();
    }

    /**
     * Starts from the model's own options so provider defaults survive, then layers the
     * spec on top. Spring AI 2.0 does not execute tools inside {@code ChatModel.call},
     * so attaching callbacks is enough for the graph to keep control of the loop.
     */
    private ChatOptions optionsFor(AgentSpec spec, List<ToolCallback> callbacks) {
        ToolCallingChatOptions.Builder<?> builder =
                chatModel.getOptions() instanceof ToolCallingChatOptions current
                        ? current.mutate()
                        : ToolCallingChatOptions.builder();
        return builder.model(spec.model()).toolCallbacks(callbacks).build();
    }

    /**
     * {@code recursionLimit} caps node transitions, which is not the same unit as the
     * spec's agent turns: a react turn costs an agent hop plus a tools hop, and the
     * runtime also spends transitions on entry and exit.
     * <p>
     * A linear graph is a fixed chain that cannot loop, so its limit is only a
     * backstop and gets generous slack. A react graph can loop forever, so its limit
     * is the real enforcement of {@code max_iterations} — see the cap test in
     * {@code GraphBuilderTest}.
     */
    private static int recursionLimitFor(AgentSpec spec) {
        return switch (spec.graphType()) {
            case REACT -> 2 * spec.maxIterations() + 4;
            case LINEAR -> 2 * spec.steps().size() + 8;
        };
    }

    private static AssistantMessage lastAssistant(AgentGraphState state) {
        return state.lastMessage()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .orElseThrow(() -> new IllegalStateException(
                        "expected the agent node to leave an assistant message"));
    }

    /** Node ids feed graph rendering, so keep them to a safe alphabet. */
    private static String sanitise(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9_]", "_");
        return cleaned.isBlank() ? "step" : cleaned;
    }
}
