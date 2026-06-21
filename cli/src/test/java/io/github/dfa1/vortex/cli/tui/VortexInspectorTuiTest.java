package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void deepExpand_rendersDictAndDataPanes_synchronously() throws Exception {
        // Given — a rich fixture (low-cardinality dict column + an I64 column) and a
        // script that expands the whole tree, then visits every row so each node's
        // detail pane renders. worker == null runs all previews inline.
        Path file = TuiTestSupport.writeRichVortex(tmp, "rich.vortex", 200);
        FakeTerminal term = new FakeTerminal(new Terminal.Size(40, 120), expandAndVisitAll());

        // When
        try (VortexReader handle = VortexReader.open(file)) {
            InspectorTree tree = InspectorTree.buildShallow(handle);
            VortexInspectorTui.run(term, tree, handle, null);
        }

        // Then — the dictionary-preview and data-preview panes both rendered
        String out = term.output();
        assertThat(out).contains("Dictionary");
        assertThat(out).contains("Data (column");
    }

    @Test
    void deepExpand_overWorker_drivesAsyncPreviews() throws Exception {
        // Given — same rich fixture, but a real IoWorker so the async submit branches
        // (dict/stats/data load via worker.submit, indexStatsChildrenOnWorker, peek
        // prefetch) are exercised instead of the synchronous render-thread path.
        Path file = TuiTestSupport.writeRichVortex(tmp, "rich.vortex", 200);
        FakeTerminal term = new FakeTerminal(new Terminal.Size(40, 120), expandAndVisitAll(), 3);

        // When — handle and tree are built on the worker thread (confined arena)
        try (IoWorker worker = new IoWorker("inspect-test-io")) {
            VortexHandle handle = TuiTestSupport.openOnWorker(worker, file);
            try {
                AtomicReference<InspectorTree> tree = new AtomicReference<>();
                worker.runAndAwait(() -> tree.set(InspectorTree.buildShallow(handle)));
                VortexInspectorTui.run(term, tree.get(), handle, worker);
            } finally {
                TuiTestSupport.closeOnWorker(worker, handle);
            }
        }

        // Then — completes and rendered the inspector chrome
        assertThat(term.output()).contains("vortex-inspect");
    }

    /// Script: expand every node along the traversal, then return to the top and step
    /// through all now-visible rows so each node is selected (and its detail pane
    /// rendered) at least once.
    private static List<Key> expandAndVisitAll() {
        List<Key> script = new ArrayList<>();
        script.add(Key.Home.INSTANCE);
        for (int i = 0; i < 40; i++) {
            script.add(Key.ArrowRight.INSTANCE);
            script.add(Key.ArrowDown.INSTANCE);
        }
        script.add(Key.Home.INSTANCE);
        for (int i = 0; i < 80; i++) {
            script.add(Key.ArrowDown.INSTANCE);
        }
        script.add(new Key.Char('q'));
        return script;
    }
}
