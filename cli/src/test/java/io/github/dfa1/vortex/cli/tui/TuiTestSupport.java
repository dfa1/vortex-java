package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/// Shared scaffolding for the TUI loop tests: writes a small multi-column Vortex
/// fixture and opens it on an [IoWorker] thread (Vortex readers use a confined
/// `Arena`, so the handle must be created on the thread that later accesses it).
final class TuiTestSupport {

    private TuiTestSupport() {
    }

    /// Writes a `rows`-row file with three I64 columns `a`, `b`, `c`.
    /// Column `a` counts up from 0, `b` from 100, `c` from 200.
    static Path writeGridVortex(Path dir, String name, int rows) throws IOException {
        Path file = dir.resolve(name);
        DType.Struct schema = new DType.Struct(
                List.of("a", "b", "c"),
                List.of(
                        new DType.Primitive(PType.I64, false),
                        new DType.Primitive(PType.I64, false),
                        new DType.Primitive(PType.I64, false)),
                false);
        long[] a = new long[rows];
        long[] b = new long[rows];
        long[] c = new long[rows];
        for (int i = 0; i < rows; i++) {
            a[i] = i;
            b[i] = 100 + i;
            c[i] = 200 + i;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of("a", a, "b", b, "c", c));
        }
        return file;
    }

    /// Writes a 5-row file across two chunks with four differently-typed columns
    /// (`i` I64, `d` F64, `flag` Bool, `name` Utf8). Exercises the formatter's
    /// per-type branches and the multi-chunk scan transitions.
    static Path writeMultiTypeVortex(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        DType.Struct schema = new DType.Struct(
                List.of("i", "d", "flag", "name"),
                List.of(
                        new DType.Primitive(PType.I64, false),
                        new DType.Primitive(PType.F64, false),
                        new DType.Bool(false),
                        new DType.Utf8(false)),
                false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    "i", new long[]{0, 1, 2},
                    "d", new double[]{0.5, 1.5, 2.5},
                    "flag", new boolean[]{true, false, true},
                    "name", new String[]{"a", "b", "c"}));
            writer.writeChunk(Map.of(
                    "i", new long[]{3, 4},
                    "d", new double[]{3.5, 4.5},
                    "flag", new boolean[]{false, true},
                    "name", new String[]{"d", "e"}));
        }
        return file;
    }

    /// Writes a single-chunk file with a low-cardinality `label` Utf8 column and an
    /// `n` I64 column. The repeated labels trip the global-dictionary encoder, so the
    /// `label` column decodes to a `vortex.dict` layout — letting the inspector render
    /// its dictionary-preview detail pane (alongside the I64 data-preview pane).
    ///
    /// The global dictionary only forms within a single chunk, so all `rows` are
    /// written in one `writeChunk`.
    static Path writeRichVortex(Path dir, String name, int rows) throws IOException {
        Path file = dir.resolve(name);
        DType.Struct schema = new DType.Struct(
                List.of("label", "n"),
                List.of(new DType.Utf8(false), new DType.Primitive(PType.I64, false)),
                false);
        String[] labels = {"red", "green", "blue"};
        String[] label = new String[rows];
        long[] n = new long[rows];
        for (int i = 0; i < rows; i++) {
            label[i] = labels[i % labels.length];
            n[i] = i;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of("label", label, "n", n));
        }
        return file;
    }

    /// Opens `file` on the worker thread and returns the handle. The caller must
    /// close the returned handle on the same worker (see [#closeOnWorker]).
    ///
    /// @param worker worker that owns the handle's confined arena
    /// @param file   path to the Vortex file
    /// @return open handle, created on the worker thread
    /// @throws InterruptedException if interrupted while awaiting the worker
    static VortexHandle openOnWorker(IoWorker worker, Path file) throws InterruptedException {
        AtomicReference<VortexHandle> handle = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                handle.set(VortexReader.open(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return handle.get();
    }

    /// Closes `handle` on the worker thread that created it.
    ///
    /// @param worker the worker that owns the handle
    /// @param handle the handle to close
    /// @throws InterruptedException if interrupted while awaiting the worker
    static void closeOnWorker(IoWorker worker, VortexHandle handle) throws InterruptedException {
        worker.runAndAwait(handle::close);
    }
}
