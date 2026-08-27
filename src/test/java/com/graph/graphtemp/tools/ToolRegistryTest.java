package com.graph.graphtemp.tools;

import com.graph.graphtemp.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ToolRegistry registry = new ToolRegistry(
            List.of(new CalculatorTool(MAPPER), new HttpGetTool(MAPPER), new WebSearchTool(MAPPER)),
            MAPPER);

    @Test
    @DisplayName("이름으로 툴을 찾는다")
    void findsByName() {
        assertThat(registry.find("calculator")).isPresent();
        assertThat(registry.find("http_get")).isPresent();
        assertThat(registry.find("web_search")).isPresent();
        assertThat(registry.find("nope")).isEmpty();
    }

    @Test
    @DisplayName("모르는 툴은 사용 가능한 목록과 함께 400으로 거절한다")
    void rejectsUnknown() {
        assertThatThrownBy(() -> registry.resolve(List.of("calculator", "nope")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unknown tool: nope")
                .hasMessageContaining("calculator");
    }

    @Test
    @DisplayName("describe() 가 이름/설명/파라미터 스키마를 준다 — GET /api/tools 용")
    void describesEveryTool() {
        List<ToolInfo> infos = registry.describe();

        assertThat(infos).extracting(ToolInfo::name)
                .containsExactlyInAnyOrder("calculator", "http_get", "web_search");
        assertThat(infos).allSatisfy(info -> {
            assertThat(info.description()).isNotBlank();
            assertThat(info.parametersSchema().get("type").asString()).isEqualTo("object");
        });
    }

    @Test
    @DisplayName("web_search 는 스텁이지만 인터페이스는 진짜다")
    void webSearchIsAWiredStub() {
        String result = registry.find("web_search").orElseThrow().call("{\"query\":\"광주 날씨\"}");
        assertThat(result).contains("광주 날씨");
    }
}
