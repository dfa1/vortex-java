package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/// Shared scaffolding for CLI command tests: writes minimal Vortex fixtures into a
/// caller-supplied temp directory and captures stdout / stderr around a command invocation.
final class CliTestSupport {

    private CliTestSupport() {
    }

    /// Writes a 3-row file with one I64 column `id` = [1, 2, 3].
    static Path writeSmallVortex(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        DType.Struct schema = new DType.Struct(
                List.of("id"),
                List.of(DType.I64),
                false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of("id", new long[]{1L, 2L, 3L}));
        }
        return file;
    }

    /// Writes a 3-row file with mixed column types: `id` (I64), `qty` (I32),
    /// `price` (F64), and `name` (Utf8). Used to exercise per-type formatting and
    /// comparison branches that the single-column [#writeSmallVortex] cannot reach.
    static Path writeTypedVortex(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        DType.Struct schema = new DType.Struct(
                List.of("id", "qty", "price", "name"),
                List.of(
                        DType.I64,
                        DType.I32,
                        DType.F64,
                        DType.UTF8),
                false);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(
                    "id", new long[]{1L, 2L, 3L},
                    "qty", new int[]{10, 20, 30},
                    "price", new double[]{100.0, 200.0, 300.0},
                    "name", new String[]{"alice", "bob", "carol"}));
        }
        return file;
    }

    /// Captured streams + exit status from one CLI invocation.
    record Captured(int status, String stdout, String stderr) {
    }

    /// Redirects `System.out` and `System.err` to fresh in-memory buffers,
    /// runs the supplied action, restores the originals, and returns whatever the action
    /// returned together with the captured output.
    static Captured capture(Runner runner) {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        try {
            int status = runner.run();
            return new Captured(status,
                    outBuf.toString(StandardCharsets.UTF_8),
                    errBuf.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }

    @FunctionalInterface
    interface Runner {
        int run();
    }
}
