package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;
import io.github.dfa1.vortex.reader.VortexHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Drives [VortexGridTui] through its full navigation surface with a scripted
/// [FakeTerminal], asserting the rendered grid contains the expected chrome and
/// data. Exercises the Loop's key dispatch, viewport scrolling, and row decode
/// without touching a real console.
class VortexGridTuiTest {

    @TempDir
    Path tmp;

    @Test
    void navigatesAndRendersGrid() throws Exception {
        // Given — 40-row, 3-column fixture so vertical paging and horizontal
        // column scrolling both have room to move
        Path file = TuiTestSupport.writeGridVortex(tmp, "grid.vortex", 40);
        List<Key> script = List.of(
                Key.ArrowDown.INSTANCE,
                Key.ArrowRight.INSTANCE,
                Key.PageDown.INSTANCE,
                Key.PageUp.INSTANCE,
                Key.End.INSTANCE,
                Key.Home.INSTANCE,
                new Key.Char('G'),
                new Key.Char('g'),
                new Key.Char('q'));
        FakeTerminal term = new FakeTerminal(new Terminal.Size(24, 80), script);

        // When
        try (IoWorker worker = new IoWorker("grid-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                VortexGridTui.run(term, "grid.vortex", source);
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }

        // Then — title, column header, and at least one decoded cell rendered
        String out = term.output();
        assertThat(out)
                .contains("Vortex View")
                .contains("grid.vortex")
                .contains("q quit")
                .contains("a").contains("b").contains("c");
    }

    @Test
    void quitsImmediatelyOnEscape() throws Exception {
        // Given — Escape is one of three quit keys; the loop must exit on the
        // first key without throwing on an otherwise-untouched grid
        Path file = TuiTestSupport.writeGridVortex(tmp, "grid.vortex", 5);
        FakeTerminal term = new FakeTerminal(new Terminal.Size(24, 80),
                List.of(Key.Escape.INSTANCE));

        // When / Then — completes without exception
        try (IoWorker worker = new IoWorker("grid-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                VortexGridTui.run(term, "grid.vortex", source);
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }

        assertThat(term.output()).contains("Vortex View");
    }

    @Test
    void handlesTerminalTooSmall() throws IOException, InterruptedException {
        // Given — a terminal narrower than the row-number gutter triggers the
        // "terminal too small" guard instead of a full render
        Path file = TuiTestSupport.writeGridVortex(tmp, "grid.vortex", 3);
        FakeTerminal term = new FakeTerminal(new Terminal.Size(2, 4),
                List.of(new Key.Char('q')));

        // When
        try (IoWorker worker = new IoWorker("grid-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try (LazyGridSource source = LazyGridSource.open(handle, worker)) {
                VortexGridTui.run(term, "grid.vortex", source);
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }

        // Then
        assertThat(term.output()).contains("terminal too small");
    }
}
