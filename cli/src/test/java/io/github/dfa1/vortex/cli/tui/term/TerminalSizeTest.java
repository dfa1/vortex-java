package io.github.dfa1.vortex.cli.tui.term;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalSizeTest {

    @Test
    void exposesRowsAndCols() {
        // Given / When
        Terminal.Size sut = new Terminal.Size(24, 80);

        // Then
        assertThat(sut.rows()).isEqualTo(24);
        assertThat(sut.cols()).isEqualTo(80);
    }

    @Test
    void equalsByComponentValues() {
        // Given / When / Then — TUI layout code compares sizes to detect resize;
        // record equality must be by-value, not by-identity.
        assertThat(new Terminal.Size(30, 100)).isEqualTo(new Terminal.Size(30, 100));
        assertThat(new Terminal.Size(30, 100)).isNotEqualTo(new Terminal.Size(31, 100));
        assertThat(new Terminal.Size(30, 100)).isNotEqualTo(new Terminal.Size(30, 101));
    }
}
