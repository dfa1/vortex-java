package io.github.dfa1.vortex.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dfa1.vortex.cli.tui.IoWorker;
import io.github.dfa1.vortex.inspect.ByteSize;
import io.github.dfa1.vortex.inspect.ColumnDistribution;
import io.github.dfa1.vortex.inspect.ColumnProfile;
import io.github.dfa1.vortex.inspect.ExplorePage;
import io.github.dfa1.vortex.reader.VortexHandle;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/// `explore [--serve | --html <out.html>] <file|url>`: the data-first view of a file, per-column
/// statistics and distributions (`inspect --html` is the storage-first one).
///
/// - `--serve` (the default) serves the page on a local port, opens it in the browser and computes
///   a column's distribution when the page asks for it. Runs until interrupted.
/// - `--html` analyzes every column up front and writes the page, distributions included, to one
///   self-contained file to publish.
///
/// The file is open once per core, each on its own [IoWorker]: a reader's arena is confined to the
/// thread that opened it, so columns are analyzed in parallel by separate readers rather than by
/// threads sharing one.
@SuppressWarnings("java:S106") // CLI command: stdout is the intended output channel
final class ExploreCommand {

    private static final String USAGE = "usage: explore [--serve | --html <out.html>] <file.vortex | http(s)://url>";
    /// Draws the per-session URL token that keeps other local users off the served page.
    private static final SecureRandom TOKENS = new SecureRandom();

    private ExploreCommand() {
    }

    static int run(String[] args) {
        String target;
        Path html = null;
        if (args.length == 2 && !args[1].startsWith("--")) {
            target = args[1];
        } else if (args.length == 3 && "--serve".equals(args[1])) {
            target = args[2];
        } else if (args.length == 4 && "--html".equals(args[1])) {
            html = Path.of(args[2]);
            target = args[3];
        } else {
            System.err.println(USAGE);
            return ExitStatus.USAGE_ERROR;
        }
        IoWorker worker = new IoWorker("explore-io-0");
        try {
            Optional<VortexHandle> opened = CliHandles.openOnWorker(worker, target);
            if (opened.isEmpty()) {
                worker.close();
                return ExitStatus.FILE_NOT_FOUND;
            }
            VortexHandle handle = opened.get();
            List<ColumnProfile> profiles = onWorker(worker, () -> ColumnProfile.of(handle));
            String title = target.substring(target.lastIndexOf('/') + 1);
            BlockingQueue<Reader> readers = readers(new Reader(worker, handle), target, profiles.size());
            IntFunction<String> analyze = index -> {
                Reader reader = take(readers);
                try {
                    return onWorker(reader.worker(), () -> ExplorePage.distribution(
                            profiles.get(index), ColumnDistribution.of(reader.handle(), profiles.get(index))));
                } finally {
                    readers.add(reader);
                }
            };
            if (html != null) {
                List<String> analyses = analyzeAll(profiles.size(), analyze);
                Files.writeString(html, ExplorePage.render(title, handle.fileSize(),
                        handle.layout().rowCount(), profiles, analyses));
                readers.forEach(reader -> CliHandles.closeOnWorker(reader.worker(), reader.handle()));
                readers.forEach(reader -> reader.worker().close());
                System.out.printf("written: %s  (%d columns analyzed, %s)%n", html, profiles.size(),
                        ByteSize.format(Files.size(html)));
                return ExitStatus.OK;
            }
            String page = ExplorePage.render(title, handle.fileSize(), handle.layout().rowCount(), profiles);
            byte[] token = new byte[16];
            TOKENS.nextBytes(token);
            String path = "/" + HexFormat.of().formatHex(token) + "/";
            HttpServer server = serve(page, path, profiles.size(), analyze);
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
            System.out.println("serving " + url + " - press Ctrl-C to stop");
            openBrowser(url);
            Thread.currentThread().join();
            return ExitStatus.OK;
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + CliHandles.describe(e));
            worker.close();
            return ExitStatus.ERROR;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return ExitStatus.OK;
        }
    }

    /// One open file on the thread that owns it.
    private record Reader(IoWorker worker, VortexHandle handle) {
    }

    /// One reader per core, up to one per column, so as many columns are analyzed at once. Each
    /// opens the file on its own worker thread: a reader cannot be shared across threads, but
    /// mapping a file again is cheap and the operating system shares its pages between the mappings.
    private static BlockingQueue<Reader> readers(Reader first, String target, int columns)
            throws IOException, InterruptedException {
        int count = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, Math.max(1, columns));
        BlockingQueue<Reader> readers = new ArrayBlockingQueue<>(count);
        readers.add(first);
        for (int i = 1; i < count; i++) {
            IoWorker worker = new IoWorker("explore-io-" + i);
            VortexHandle handle = CliHandles.openOnWorker(worker, target)
                    .orElseThrow(() -> new IOException("file vanished: " + target));
            readers.add(new Reader(worker, handle));
        }
        return readers;
    }

    /// Every column's analysis, as many at once as there are readers, in column order.
    private static List<String> analyzeAll(int columns, IntFunction<String> analyze) throws InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>(columns);
            for (int i = 0; i < columns; i++) {
                int index = i;
                futures.add(executor.submit(() -> analyze.apply(index)));
            }
            List<String> analyses = new ArrayList<>(columns);
            for (Future<String> future : futures) {
                try {
                    analyses.add(future.get());
                } catch (ExecutionException e) {
                    // Rethrow the column's own failure (a malformed segment, say), not the wrapper.
                    if (e.getCause() instanceof RuntimeException cause) {
                        throw cause;
                    }
                    throw new IllegalStateException(e.getCause());
                }
            }
            return analyses;
        }
    }

    private static Reader take(BlockingQueue<Reader> readers) {
        try {
            return readers.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /// Starts a server on a free port of `127.0.0.1` answering two paths: `path` with `page`, and
    /// `path` + `column/<index>` with `analyze`'s fragment for that column. Every other path is a
    /// 404, and a failed analysis is a 500 carrying the error as plain text.
    ///
    /// The page shows file contents, so it is reachable only from this machine and only under
    /// `path`, a random one in [#run(String[])], as Jupyter does: another local user, or a web page
    /// probing localhost ports, cannot guess it.
    ///
    /// @param page    the HTML document to serve
    /// @param path    the page's path, e.g. `/3f2a.../`
    /// @param columns how many columns `analyze` accepts, indexed from `0`
    /// @param analyze renders one column's distribution as an HTML fragment
    /// @return the started server
    /// @throws IOException if the server cannot bind
    static HttpServer serve(String page, String path, int columns, IntFunction<String> analyze) throws IOException {
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        String columnPrefix = path + "column/";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                String requested = exchange.getRequestURI().getPath();
                if (path.equals(requested)) {
                    respond(exchange, 200, "text/html", body);
                    return;
                }
                int index = -1;
                if (requested.startsWith(columnPrefix)) {
                    index = columnIndex(requested.substring(columnPrefix.length()));
                }
                if (index < 0 || index >= columns) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                try {
                    respond(exchange, 200, "text/html", analyze.apply(index).getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    respond(exchange, 500, "text/plain", CliHandles.describe(e).getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        // A thread per request: `analyze` blocks until a reader is free, and the default executor's
        // single thread would serialize the columns the readers could analyze at once.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private static int columnIndex(String digits) {
        if (digits.isEmpty() || digits.length() > 9 || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        return Integer.parseInt(digits);
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /// Runs `task` on the thread that owns the file and returns its result, rethrowing its failure.
    private static <T> T onWorker(IoWorker worker, Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        try {
            worker.runAndAwait(() -> {
                try {
                    result.set(task.get());
                } catch (RuntimeException e) {
                    failure.set(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    /// Best effort: the URL is printed either way, so a machine with no desktop (a server over SSH)
    /// still gets a link to forward.
    private static void openBrowser(URI url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command;
        if (os.contains("mac")) {
            command = new String[] {"open", url.toString()};
        } else if (os.contains("win")) {
            command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url.toString()};
        } else {
            command = new String[] {"xdg-open", url.toString()};
        }
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException _) {
            // no browser launcher: the printed URL is the fallback
        }
    }
}
