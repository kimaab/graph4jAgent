package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.codegen.AgentSource;
import com.graph.graphtemp.codegen.CodeGenerator;
import com.graph.graphtemp.tools.CalculatorTool;
import com.graph.graphtemp.tools.HttpGetTool;
import com.graph.graphtemp.tools.WebSearchTool;
import com.graph.graphtemp.tools.ToolRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.streaming.StreamingOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.prebuilt.MessagesState.MESSAGES_STATE;

/** Covers what the SSE endpoint reads off the graph: token chunks and tool activity. */
class StreamingGraphTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ToolRegistry registry() {
        return new ToolRegistry(
                List.of(new CalculatorTool(MAPPER), new HttpGetTool(MAPPER), new WebSearchTool(MAPPER)),
                MAPPER);
    }

    /** Same path production takes: generate the source, compile it, run what came out. */
    private static AgentGraphs graphs(StubChatModel model) {
        CodeGenerator generator =
                new CodeGenerator(registry(), "http://gateway.example/v1", "test-key",
                "jdbc:postgresql://db.example:5432/postgres", "postgres", "secret");
        return new AgentGraphs(model, new AgentSource(id -> Optional.empty(), generator),
                new AgentCodeCompiler());
    }

    private static AgentSpec reactSpec(List<String> tools) {
        return new AgentSpec(UUID.randomUUID(), "streamer", "", "test-model",
                "you stream", tools, GraphType.REACT, List.of(), 10);
    }

    private static List<NodeOutput<MessagesState<Message>>> drain(
            CompiledGraph<MessagesState<Message>> graph, String input) {
        List<NodeOutput<MessagesState<Message>>> collected = new ArrayList<>();
        for (NodeOutput<MessagesState<Message>> output : graph.stream(
                Map.of(MESSAGES_STATE, new UserMessage(input)),
                RunnableConfig.builder().threadId("t-" + UUID.randomUUID()).build())) {
            collected.add(output);
        }
        return collected;
    }

    @Test
    @DisplayName("스트리밍 그래프는 토큰 조각을 StreamingOutput 으로 흘리고 합치면 원문이 된다")
    void emitsTokenChunks() {
        AgentGraphs builder = graphs(StubChatModel.replying("셋 으로 나뉘는 답"));

        List<NodeOutput<MessagesState<Message>>> outputs =
                drain(builder.buildStreaming(reactSpec(List.of())), "안녕");

        List<String> chunks = outputs.stream()
                .filter(StreamingOutput.class::isInstance)
                .map(o -> ((StreamingOutput<MessagesState<Message>>) o).chunk())
                .filter(c -> c != null && !c.isEmpty())
                .toList();

        assertThat(chunks).isNotEmpty();
        assertThat(String.join("", chunks)).isEqualTo("셋 으로 나뉘는 답");
    }

    @Test
    @DisplayName("非스트리밍 그래프에서는 StreamingOutput 이 나오지 않는다")
    void nonStreamingEmitsNoChunks() {
        AgentGraphs builder = graphs(StubChatModel.replying("한 방에"));

        List<NodeOutput<MessagesState<Message>>> outputs =
                drain(builder.build(reactSpec(List.of())), "안녕");

        assertThat(outputs).noneMatch(StreamingOutput.class::isInstance);
    }

    @Test
    @DisplayName("스트리밍 중에도 tool_call / tool_result 를 상태에서 읽어낼 수 있다")
    void toolActivityIsVisibleWhileStreaming() {
        AssistantMessage wantsTool = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "calculator",
                        "{\"expression\":\"6*7\"}")))
                .build();
        AgentGraphs builder = graphs(new StubChatModel(List.of(wantsTool, new AssistantMessage("답은 42"))));

        List<NodeOutput<MessagesState<Message>>> outputs =
                drain(builder.buildStreaming(reactSpec(List.of("calculator"))), "6*7 은?");

        MessagesState<Message> finalState = outputs.getLast().state();

        assertThat(finalState.messages())
                .anySatisfy(m -> assertThat(m).isInstanceOf(ToolResponseMessage.class));

        ToolResponseMessage toolMessage = finalState.messages().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst().orElseThrow();

        assertThat(toolMessage.getResponses().getFirst().name()).isEqualTo("calculator");
        assertThat(toolMessage.getResponses().getFirst().responseData()).isEqualTo("42");
    }
}
