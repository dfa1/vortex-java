package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Drives [VortexInspectorTui] through its navigation surface with a scripted
/// [FakeTerminal] and a `null` worker (synchronous render-thread I/O), asserting
/// the two-pane inspector renders header, tree, and details. The handle is opened
/// on this thread so the null-worker synchronous path stays on a single thread.
class VortexInspectorTuiTest {

    @TempDir
    Path tmp;

    @Test
    void navigatesAndRendersInspector() throws Exception {
        // Given — a 12-row, 3-column fixture; the inspector builds a shallow tree
        // and previews column data on the render thread (worker == null)
        Path file = TuiTestSupport.writeGridVortex(tmp, "inspect.vortex", 12);
        List<Key> script = List.of(
                Key.ArrowDown.INSTANCE,
                Key.ArrowRight.INSTANCE,
                Key.Enter.INSTANCE,
                Key.ArrowLeft.INSTANCE,
                Key.PageDown.INSTANCE,
                Key.PageUp.INSTANCE,
                Key.Home.INSTANCE,
                Key.End.INSTANCE,
                new Key.Char('q'));
        // idleTicks=2 exercises the readKey-timeout branch (spinner tick) before keys replay
        FakeTerminal term = new FakeTerminal(new Terminal.Size(30, 100), script, 2);

        // When
        try (VortexReader handle = VortexReader.open(file)) {
            InspectorTree tree = InspectorTree.buildShallow(handle);
            VortexInspectorTui.run(term, tree, handle, null);
        }

        // Then — header chrome and tree content rendered
        String out = term.output();
        assertThat(out).contains("vortex-inspect");
        assertThat(out).contains("struct");
        assertThat(out).contains("quit");
    }

    @Test
    void quitsOnEscapeWithoutRenderingDetails() throws Exception {
        // Given — single Escape key; loop renders once then exits
        Path file = TuiTestSupport.writeGridVortex(tmp, "inspect.vortex", 4);
        FakeTerminal term = new FakeTerminal(new Terminal.Size(30, 100),
                List.of(Key.Escape.INSTANCE));

        // When / Then — completes without exception, header still drawn
        try (VortexReader handle = VortexReader.open(file)) {
            InspectorTree tree = InspectorTree.buildShallow(handle);
            VortexInspectorTui.run(term, tree, handle, null);
        }

        assertThat(term.output()).contains("vortex-inspect");
    }
}
