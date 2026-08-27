package com.graph.graphtemp.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool(JsonMapper.builder().build());

    private String eval(String expression) {
        return tool.call("{\"expression\":\"" + expression + "\"}");
    }

    @ParameterizedTest
    @CsvSource({
            "'1 + 1', 2",
            "'(3 + 4) * 2', 14",
            "'10 / 4', 2.5",
            "'-5 + 3', -2",
            "'2 * -3', -6",
            "'7 % 3', 1",
            "'((1 + 2) * (3 + 4))', 21",
            "'1.5 * 2', 3"
    })
    @DisplayName("사칙연산과 괄호, 단항 부호를 계산한다")
    void evaluates(String expression, String expected) {
        assertThat(eval(expression)).isEqualTo(expected);
    }

    @Test
    @DisplayName("0으로 나누면 에러 문자열을 돌려준다 (예외를 던지지 않는다)")
    void divisionByZero() {
        assertThat(eval("1 / 0")).isEqualTo("error: division by zero");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "System.exit(0)",
            "1 + foo",
            "__import__('os')",
            "1 +",
            "(1 + 2"
    })
    @DisplayName("코드나 식별자는 파싱되지 않는다 — 문법이 숫자와 연산자로 닫혀 있다")
    void rejectsAnythingThatIsNotArithmetic(String hostile) {
        assertThat(eval(hostile)).startsWith("error:");
    }

    @Test
    @DisplayName("입력이 JSON이 아니거나 expression 이 없으면 에러를 돌려준다")
    void rejectsBadInput() {
        assertThat(tool.call("not json")).startsWith("error:");
        assertThat(tool.call("{}")).isEqualTo("error: 'expression' is required");
    }

    @Test
    @DisplayName("툴 정의가 이름과 JSON 스키마를 노출한다")
    void exposesDefinition() {
        assertThat(tool.name()).isEqualTo("calculator");
        assertThat(tool.inputSchema()).contains("expression");
    }
}
