package com.graph.graphtemp.graph;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.agent.Step;
import com.graph.graphtemp.codegen.AgentSource;
import com.graph.graphtemp.codegen.CodeGenerator;
import com.graph.graphtemp.tools.HttpGetTool;
import com.graph.graphtemp.tools.WebSearchTool;
import com.graph.graphtemp.tools.CalculatorTool;
import com.graph.graphtemp.tools.ToolRegistry;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.bsc.langgraph4j.prebuilt.MessagesState.MESSAGES_STATE;

class AgentGraphsTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static ToolRegistry registry() {
        return new ToolRegistry(
                List.of(new CalculatorTool(MAPPER), new HttpGetTool(MAPPER), new WebSearchTool(MAPPER)),
                MAPPER);
    }

    /**
     * The real path a run takes: render the spec to Java, compile it, load it, call its
     * entry point. Nothing here shortcuts to a hand-built graph, because in production
     * nothing does.
     */
    private static AgentGraphs graphs(StubChatModel model) {
        CodeGenerator generator =
                new CodeGenerator(registry(), "http://gateway.example/v1", "test-key",
                "jdbc:postgresql://db.example:5432/postgres", "postgres", "secret");
        // No stored edit: every spec here runs on freshly generated code.
        AgentSource source = new AgentSource(id -> Optional.empty(), generator);
        return new AgentGraphs(model, source, new AgentCodeCompiler());
    }

    private static AgentSpec spec(GraphType type, List<String> tools, List<Step> steps) {
        return new AgentSpec(UUID.randomUUID(), "test agent", "", "test-model",
                "you are a test", tools, type, steps, 10);
    }

    private static Optional<MessagesState<Message>> run(
            CompiledGraph<MessagesState<Message>> graph, String input) {
        return graph.invoke(
                Map.of(MESSAGES_STATE, new UserMessage(input)),
                RunnableConfig.builder().threadId("t-" + UUID.randomUUID()).build());
    }

    @Test
    @DisplayName("react: 툴 호출이 오면 툴을 실행하고 결과를 모델에 되돌려 루프를 한 번 더 돈다")
    void reactLoopsThroughToolAndBack() {
        AssistantMessage wantsTool = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "calculator",
                        "{\"expression\":\"(3 + 4) * 2\"}")))
                .build();
        AssistantMessage finalAnswer = new AssistantMessage("정답은 14입니다");

        StubChatModel model = new StubChatModel(List.of(wantsTool, finalAnswer));
        AgentGraphs builder = graphs(model);

        Optional<MessagesState<Message>> result =
                run(builder.build(spec(GraphType.REACT, List.of("calculator"), List.of())), "(3+4)*2 는?");

        assertThat(result).isPresent();
        List<Message> messages = result.get().messages();

        // user -> assistant(tool_call) -> tool_response -> assistant(final)
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(messages.get(3)).isInstanceOf(AssistantMessage.class);

        // 툴이 실제로 실행되어 계산 결과가 대화에 들어갔는지
        ToolResponseMessage toolMessage = (ToolResponseMessage) messages.get(2);
        assertThat(toolMessage.getResponses()).hasSize(1);
        assertThat(toolMessage.getResponses().getFirst().responseData()).isEqualTo("14");

        assertThat(((AssistantMessage) messages.getLast()).getText()).isEqualTo("정답은 14입니다");
        assertThat(model.callCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("react: 툴 호출이 없으면 첫 응답에서 바로 끝난다")
    void reactStopsWhenNoToolCall() {
        StubChatModel model = StubChatModel.replying("바로 답합니다");
        AgentGraphs builder = graphs(model);

        Optional<MessagesState<Message>> result =
                run(builder.build(spec(GraphType.REACT, List.of("calculator"), List.of())), "안녕");

        assertThat(result).isPresent();
        assertThat(result.get().messages()).hasSize(2);
        assertThat(model.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("linear: steps 를 순서대로 실행하고 각 단계가 이전 출력을 이어받는다")
    void linearChainsStepsInOrder() {
        StubChatModel model = StubChatModel.replying("추출 결과", "압축 결과");
        AgentGraphs builder = graphs(model);

        AgentSpec spec = spec(GraphType.LINEAR, List.of(), List.of(
                new Step("추출", "핵심 문장을 뽑아라"),
                new Step("압축", "3문장으로 줄여라")));

        Optional<MessagesState<Message>> result = run(builder.build(spec), "원문입니다");

        assertThat(result).isPresent();
        assertThat(model.callCount()).isEqualTo(2);

        // 두 번째 스텝의 프롬프트에 첫 스텝의 출력이 포함되어야 이어받은 것
        List<Message> secondPrompt = model.promptAt(1);
        assertThat(secondPrompt).extracting(Message::getText).contains("추출 결과");
        assertThat(secondPrompt).extracting(Message::getText).contains("3문장으로 줄여라");

        assertThat(result.get().messages().getLast().getText()).isEqualTo("압축 결과");
    }

    @Test
    @DisplayName("linear: 시스템 프롬프트가 매 스텝의 첫 메시지로 들어간다")
    void linearPassesSystemPrompt() {
        StubChatModel model = StubChatModel.replying("a", "b");
        AgentGraphs builder = graphs(model);

        AgentSpec spec = spec(GraphType.LINEAR, List.of(), List.of(
                new Step("one", "p1"), new Step("two", "p2")));
        run(builder.build(spec), "input");

        assertThat(model.promptAt(0).getFirst().getMessageType()).isEqualTo(MessageType.SYSTEM);
        assertThat(model.promptAt(1).getFirst().getMessageType()).isEqualTo(MessageType.SYSTEM);
    }

    @Test
    @DisplayName("같은 스펙은 그래프를 재사용하고, 스펙이 바뀌면 새로 빌드한다")
    void cachesOnSpecContent() {
        AgentGraphs builder = graphs(StubChatModel.replying("x"));
        AgentSpec original = spec(GraphType.REACT, List.of("calculator"), List.of());

        CompiledGraph<MessagesState<Message>> first = builder.build(original);
        assertThat(builder.build(original)).isSameAs(first);

        AgentSpec edited = new AgentSpec(original.id(), original.name(), original.description(),
                original.model(), "다른 시스템 프롬프트", original.tools(), original.graphType(),
                original.steps(), original.maxIterations());
        assertThat(builder.build(edited)).isNotSameAs(first);
    }

    @Test
    @DisplayName("react: 툴 호출이 끝없이 이어져도 max_iterations 에서 멈춘다")
    void reactStopsAtMaxIterations() {
        // 모델이 절대 멈추지 않는다: 상한이 없으면 무한 루프가 된다.
        // tool call id 는 매번 달라야 한다 - 상태의 appender 채널이 동일 메시지를 중복으로 보지 않도록.
        List<AssistantMessage> runaway = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-" + i, "function", "calculator", "{\"expression\":\"1+1\"}")))
                        .build())
                .toList();
        StubChatModel model = new StubChatModel(runaway);
        AgentGraphs builder = graphs(model);

        AgentSpec spec = new AgentSpec(UUID.randomUUID(), "runaway", "", "test-model",
                "", List.of("calculator"), GraphType.REACT, List.of(), 2);
        CompiledGraph<MessagesState<Message>> graph = builder.build(spec);

        assertThatThrownBy(() -> run(graph, "돌아라"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("Maximum number of iterations");

        // 무한히 돌지 않고 상한 근처에서 멈췄는지
        assertThat(model.callCount()).isLessThanOrEqualTo(spec.maxIterations() + 2);
    }

    @Test
    @DisplayName("알 수 없는 툴 이름은 그래프 빌드 시점에 거부된다")
    void rejectsUnknownTool() {
        AgentGraphs builder = graphs(StubChatModel.replying("x"));
        AgentSpec spec = spec(GraphType.REACT, List.of("nope"), List.of());

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> builder.build(spec)).getMessage())
                .contains("unknown tool: nope");
    }
}
