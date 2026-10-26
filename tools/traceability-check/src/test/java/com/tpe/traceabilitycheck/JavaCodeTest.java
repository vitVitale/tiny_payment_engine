package com.tpe.traceabilitycheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaCodeTest {

    @Test
    void dropsLineAndBlockComments() {
        var code = JavaCode.of("a // b \"c\"\nd /* e\n f */ g");

        assertThat(code.text()).isEqualTo("a \nd   g");
    }

    @Test
    void replacesStringLiteralsWithIndexedPlaceholders() {
        var code = JavaCode.of("f(\"x // y\", \"q\\\"r\")");

        assertThat(code.text()).isEqualTo("f(\"#0\", \"#1\")");
        assertThat(code.literal(0)).isEqualTo("x // y");
        assertThat(code.literal(1)).isEqualTo("q\\\"r");
    }

    @Test
    void replacesTextBlocksAndCharLiterals() {
        var code = JavaCode.of("a = \"\"\"\n  /* not a comment */\n  \"\"\"; c = '\"'; d = '\\''; e");

        assertThat(code.text()).isEqualTo("a = \"#0\"; c = \"#1\"; d = \"#2\"; e");
        assertThat(code.literal(0)).isEqualTo("\n  /* not a comment */\n  ");
        assertThat(code.literal(1)).isEqualTo("\"");
    }

    @Test
    void unterminatedCommentOrLiteralRunsToTheEnd() {
        assertThat(JavaCode.of("a /* b").text()).isEqualTo("a  ");
        assertThat(JavaCode.of("a \"\"\" b").text()).isEqualTo("a \"#0\"");
    }
}
