package io.github.dfa1.vortex.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/// `main` dispatches and calls `System.exit`, so it is covered end-to-end by the CLI
/// integration test rather than here. This pins the one pure, unit-testable piece — the
/// usage text must advertise every subcommand `main` actually dispatches.
class VortexCliTest {

    @Test
    void printUsage_listsEverySubcommand() {
        // Given
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // When
        VortexCli.printUsage(new PrintStream(buf, true, StandardCharsets.UTF_8));

        // Then — one line per subcommand handled by main()'s switch
        String usage = buf.toString(StandardCharsets.UTF_8);
        assertThat(usage)
                .contains("Usage:")
                .contains("inspect")
                .contains("tui")
                .contains("view")
                .contains("export")
                .contains("import")
                .contains("schema")
                .contains("count")
                .contains("select")
                .contains("stats")
                .contains("filter");
    }
}
