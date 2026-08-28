package com.graph.graphtemp.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Covers the one piece of {@link AgentSpecRepository} that does not need a database.
 * <p>
 * It exists because of a real outage: every agent in the database had a NULL {@code code}
 * column — the correct state for one nobody has edited — and the lookup called
 * {@code findFirst()} before filtering, so it threw a NullPointerException with no
 * message on every run and every code view.
 */
class AgentSpecRepositoryTest {

    /** What JdbcTemplate hands back for a NULL column: a list holding one null. */
    private static List<String> rowsHolding(String value) {
        List<String> rows = new ArrayList<>();
        rows.add(value);
        return rows;
    }

    @Test
    @DisplayName("code 컬럼이 NULL 이면 터지지 않고 '없음'으로 돌아온다")
    void nullCodeIsAbsentNotAnError() {
        assertThatCode(() -> AgentSpecRepository.firstUsable(rowsHolding(null)))
                .doesNotThrowAnyException();

        assertThat(AgentSpecRepository.firstUsable(rowsHolding(null))).isEmpty();
    }

    @Test
    @DisplayName("빈 문자열과 공백만 있는 코드도 '없음'으로 친다")
    void blankCodeCountsAsAbsent() {
        assertThat(AgentSpecRepository.firstUsable(rowsHolding(""))).isEmpty();
        assertThat(AgentSpecRepository.firstUsable(rowsHolding("   \n\t "))).isEmpty();
    }

    @Test
    @DisplayName("저장된 코드가 있으면 그대로 돌려준다")
    void storedCodeComesBack() {
        Optional<String> found = AgentSpecRepository.firstUsable(rowsHolding("class Agent {}"));

        assertThat(found).contains("class Agent {}");
    }

    @Test
    @DisplayName("행이 없으면 '없음'")
    void noRowsIsAbsent() {
        assertThat(AgentSpecRepository.firstUsable(List.of())).isEmpty();
    }
}
