package com.graph.graphtemp.tools;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Evaluates an arithmetic expression with a hand-written recursive-descent parser.
 * A script engine would be the short path and would also hand the model arbitrary
 * code execution, so the grammar here is deliberately closed: numbers, + - * / %,
 * parentheses and unary minus. Nothing else parses.
 */
@Component
public class CalculatorTool extends BuiltinTool {

    private static final String SCHEMA = """
            {"type":"object",\
            "properties":{"expression":{"type":"string",\
            "description":"Arithmetic expression, e.g. (3 + 4) * 2"}},\
            "required":["expression"]}""";

    private final ObjectMapper mapper;

    public CalculatorTool(ObjectMapper mapper) {
        super("calculator", "Evaluate an arithmetic expression and return the numeric result.", SCHEMA);
        this.mapper = mapper;
    }

    @Override
    public String call(String toolInput) {
        String expression;
        try {
            JsonNode node = mapper.readTree(toolInput);
            JsonNode field = node.get("expression");
            expression = field == null ? null : field.asString();
        } catch (RuntimeException e) {
            return "error: tool input was not valid JSON";
        }
        if (expression == null || expression.isBlank()) {
            return "error: 'expression' is required";
        }
        try {
            double result = new Parser(expression).parse();
            if (result == Math.rint(result) && !Double.isInfinite(result)) {
                return String.valueOf((long) result);
            }
            return String.valueOf(result);
        } catch (ArithmeticException | IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
    }

    /** expression := term (('+' | '-') term)* */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        double parse() {
            double value = expression();
            skipSpaces();
            if (pos < src.length()) {
                throw new IllegalArgumentException(
                        "unexpected character '" + src.charAt(pos) + "' at position " + pos);
            }
            return value;
        }

        private double expression() {
            double value = term();
            while (true) {
                if (eat('+')) {
                    value += term();
                } else if (eat('-')) {
                    value -= term();
                } else {
                    return value;
                }
            }
        }

        /** term := factor (('*' | '/' | '%') factor)* */
        private double term() {
            double value = factor();
            while (true) {
                if (eat('*')) {
                    value *= factor();
                } else if (eat('/')) {
                    double divisor = factor();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else if (eat('%')) {
                    double divisor = factor();
                    if (divisor == 0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value %= divisor;
                } else {
                    return value;
                }
            }
        }

        /** factor := ('+' | '-')? (number | '(' expression ')') */
        private double factor() {
            if (eat('+')) {
                return factor();
            }
            if (eat('-')) {
                return -factor();
            }
            if (eat('(')) {
                double value = expression();
                if (!eat(')')) {
                    throw new IllegalArgumentException("unbalanced parenthesis");
                }
                return value;
            }
            return number();
        }

        private double number() {
            skipSpaces();
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("expected a number at position " + pos);
            }
            try {
                return Double.parseDouble(src.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("malformed number '" + src.substring(start, pos) + "'");
            }
        }

        private boolean eat(char expected) {
            skipSpaces();
            if (pos < src.length() && src.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
