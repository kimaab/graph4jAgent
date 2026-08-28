package com.graph.graphtemp.codegen;

import com.graph.graphtemp.agent.AgentSpec;
import com.graph.graphtemp.agent.GraphType;
import com.graph.graphtemp.agent.Step;
import com.graph.graphtemp.error.ApiException;
import com.graph.graphtemp.tools.CalculatorTool;
import com.graph.graphtemp.tools.HttpGetTool;
import com.graph.graphtemp.tools.ToolRegistry;
import com.graph.graphtemp.tools.WebSearchTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGeneratorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static CodeGenerator generator() {
        ToolRegistry registry = new ToolRegistry(
                List.of(new CalculatorTool(MAPPER), new HttpGetTool(MAPPER), new WebSearchTool(MAPPER)),
                MAPPER);
        return new CodeGenerator(registry, "http://gateway.example/v1", "secret-key",
                "jdbc:postgresql://db.example:5432/postgres", "postgres", "secret");
    }

    private static AgentSpec spec(GraphType type, List<String> tools, List<Step> steps,
                                  String systemPrompt) {
        return new AgentSpec(UUID.randomUUID(), "계산 도우미", "desc", "google/gemma-4-31B-it",
                systemPrompt, tools, type, steps, 5);
    }

    @Test
    @DisplayName("react 스펙이 JBang 헤더와 툴 클래스를 갖춘 파일로 나온다")
    void generatesReactAgent() {
        String code = generator().generate(spec(GraphType.REACT, List.of("calculator"), List.of(), "너는 조수다"));

        assertThat(code).startsWith("///usr/bin/env jbang");
        assertThat(code).contains("//DEPS org.bsc.langgraph4j:langgraph4j-core:1.8.24");
        assertThat(code).contains("//DEPS com.openai:openai-java-client-okhttp:4.49.0");
        assertThat(code).contains("static final class Calculator extends Tool");
        assertThat(code).contains("return List.of(new Calculator());");
        assertThat(code).contains("addConditionalEdges(\"agent\"");
        assertThat(code).doesNotContain("__");
    }

    @Test
    @DisplayName("linear 스펙은 steps 를 순서대로 담은 그래프를 낸다")
    void generatesLinearAgent() {
        String code = generator().generate(spec(GraphType.LINEAR, List.of(),
                List.of(new Step("추출", "핵심을 뽑아라"), new Step("압축", "3문장으로")), ""));

        assertThat(code).contains("STEP_PROMPTS");
        assertThat(code).contains("\"핵심을 뽑아라\"");
        assertThat(code).contains("\"3문장으로\"");
        assertThat(code).doesNotContain("addConditionalEdges");
        assertThat(code).doesNotContain("__");
    }

    @Test
    @DisplayName("선택한 툴만 코드에 포함된다")
    void onlyIncludesSelectedTools() {
        String code = generator().generate(
                spec(GraphType.REACT, List.of("web_search"), List.of(), ""));

        assertThat(code).contains("class WebSearch extends Tool");
        assertThat(code).doesNotContain("class Calculator extends Tool");
        assertThat(code).doesNotContain("class HttpGet extends Tool");
    }

    @Test
    @DisplayName("따옴표와 줄바꿈이 든 시스템 프롬프트가 리터럴을 깨지 않는다")
    void escapesSystemPrompt() {
        String nasty = "그는 \"안녕\"이라 말했다\n두 번째 줄\\끝";
        String code = generator().generate(spec(GraphType.REACT, List.of(), List.of(), nasty));

        assertThat(code).contains("\\\"안녕\\\"");
        assertThat(code).contains("\\n");
        assertThat(code).contains("\\\\끝");
        // 리터럴이 한 줄 안에서 닫혀야 한다
        String line = code.lines()
                .filter(l -> l.contains("SYSTEM_PROMPT ="))
                .findFirst().orElseThrow();
        assertThat(line).endsWith(";");
    }

    @Test
    @DisplayName("한글 이름은 클래스명으로 못 쓰므로 Agent 로 떨어진다")
    void classNameFallsBackForNonAscii() {
        assertThat(CodeGenerator.classNameFor("계산 도우미")).isEqualTo("Agent");
        assertThat(CodeGenerator.classNameFor("my cool agent")).isEqualTo("MyCoolAgent");
        assertThat(CodeGenerator.classNameFor("2fast")).isEqualTo("Agent");
    }

    @Test
    @DisplayName("모르는 툴은 코드를 뱉기 전에 거부한다")
    void rejectsUnknownTool() {
        assertThatThrownBy(() -> generator().generate(
                spec(GraphType.REACT, List.of("nope"), List.of(), "")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unknown tool: nope");
    }
}
