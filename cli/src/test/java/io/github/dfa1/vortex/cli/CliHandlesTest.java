package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/// The worker open/close paths are covered through ViewCommand/TuiCommand; this pins the
/// pure cause-chain formatter, which those error paths don't otherwise reach.
class CliHandlesTest {

    @Test
    void describe_messagelessThrowable_isJustTheSimpleName() {
        // Given / When
        String text = CliHandles.describe(new IllegalStateException());

        // Then
        assertThat(text).isEqualTo("IllegalStateException");
    }

    @Test
    void describe_withMessage_appendsIt() {
        // Given / When
        String text = CliHandles.describe(new IOException("disk gone"));

        // Then
        assertThat(text).isEqualTo("IOException: disk gone");
    }

    @Test
    void describe_flattensTheCauseChain() {
        // Given — a two-deep cause chain
        Throwable root = new IllegalArgumentException("bad arg");
        Throwable wrapped = new IOException("read failed", root);

        // When
        String text = CliHandles.describe(wrapped);

        // Then — outermost first, joined by " -> "
        assertThat(text).isEqualTo("IOException: read failed -> IllegalArgumentException: bad arg");
    }
}
