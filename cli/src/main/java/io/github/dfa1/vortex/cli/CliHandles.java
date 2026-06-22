package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.reader.VortexHandle;
import io.github.dfa1.vortex.reader.VortexHttpReader;
import io.github.dfa1.vortex.reader.VortexReader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/// Shared plumbing for the subcommands: local-file / http(s) target resolution ([#openTarget]),
/// cause-chain rendering ([#describe]), and the worker-thread handle round-trip the interactive
/// `view`/`tui` commands need.
///
/// A [VortexReader] uses a confined [java.lang.foreign.Arena], so for the interactive commands the
/// file must be opened and closed on the same [IoWorker] thread the TUI later dispatches I/O to.
@SuppressWarnings("java:S106") // CLI tool: user-facing diagnostics go to System.err by design.
final class CliHandles {

    private CliHandles() {
    }

    /// Resolves and opens `target` on the worker thread, blocking until it completes.
    ///
    /// @param worker the I/O worker that owns the handle's arena
    /// @param target a local path or an `http(s)://` URL
    /// @return the opened handle, or empty if the target is missing or malformed
    /// @throws InterruptedException if interrupted while waiting on the worker
    /// @throws IOException          if opening the file or URL fails
    static Optional<VortexHandle> openOnWorker(IoWorker worker, String target)
            throws InterruptedException, IOException {
        AtomicReference<VortexHandle> handle = new AtomicReference<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        worker.runAndAwait(() -> {
            try {
                handle.set(openTarget(target));
            } catch (IOException e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return Optional.ofNullable(handle.get());
    }

    /// Closes `handle` on the worker thread that opened it.
    ///
    /// @param worker the I/O worker that owns the handle's arena
    /// @param handle the handle to close
    static void closeOnWorker(IoWorker worker, VortexHandle handle) {
        try {
            worker.runAndAwait(handle::close);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /// Renders a throwable and its cause chain as a single `Type: message -> Type: message` line.
    ///
    /// @param t the throwable to describe
    /// @return a flattened, human-readable cause chain
    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        while (cur != null) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
        }
        return sb.toString();
    }

    /// Opens `target` as a local path or `http(s)://` URL, printing a diagnostic and returning
    /// `null` when the file is missing or the URL is malformed.
    ///
    /// @param target a local path or an `http(s)://` URL
    /// @return the opened handle, or `null` if the target is missing or malformed
    /// @throws IOException if opening the file or URL fails
    static VortexHandle openTarget(String target) throws IOException {
        if (target.startsWith("http://") || target.startsWith("https://")) {
            try {
                return VortexHttpReader.open(new URI(target));
            } catch (URISyntaxException _) {
                System.err.println("invalid URL: " + target);
                return null;
            }
        }
        Path path = Path.of(target);
        if (!Files.exists(path)) {
            System.err.println("file not found: " + path);
            return null;
        }
        return VortexReader.open(path);
    }
}
