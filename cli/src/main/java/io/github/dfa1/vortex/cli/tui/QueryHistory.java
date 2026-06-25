package io.github.dfa1.vortex.cli.tui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Bounded, persistent recall buffer for the SQL shell: the last [#MAX_ENTRIES] queries the
/// user ran, oldest first.
///
/// Entries are stored one per file line with newlines and backslashes escaped (`\n`, `\\`), so a
/// multiline query survives a round-trip through the flat history file. Consecutive duplicates are
/// collapsed - re-running the same query does not grow the list. The buffer is rewritten on every
/// [#add(String)] so an interrupted session still keeps everything up to the last run query.
public final class QueryHistory {

    /// Maximum number of queries retained; older entries are dropped on overflow.
    public static final int MAX_ENTRIES = 100;

    private final Path file;
    private final List<String> entries;

    private QueryHistory(Path file, List<String> entries) {
        this.file = file;
        this.entries = entries;
    }

    /// Loads history from the default location, `~/.vortex/sql_history`.
    ///
    /// A missing or unreadable file yields an empty history rather than failing - history is a
    /// convenience, never a hard dependency.
    ///
    /// @return the loaded history, possibly empty
    public static QueryHistory loadDefault() {
        return load(defaultFile());
    }

    /// Loads history from `file`, treating a missing or unreadable file as empty.
    ///
    /// @param file the history file to read and later persist to
    /// @return the loaded history, possibly empty
    public static QueryHistory load(Path file) {
        List<String> entries = new ArrayList<>();
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    if (!line.isEmpty()) {
                        entries.add(unescape(line));
                    }
                }
            }
        } catch (IOException _) {
            entries.clear();
        }
        return new QueryHistory(file, entries);
    }

    /// Appends `query` (collapsing a consecutive duplicate), enforces the size cap, and rewrites
    /// the history file. Blank queries are ignored.
    ///
    /// @param query the query text to record
    public void add(String query) {
        String trimmed = query.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (!entries.isEmpty() && entries.getLast().equals(trimmed)) {
            return;
        }
        entries.add(trimmed);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        persist();
    }

    /// All retained queries, oldest first. The returned list is a copy.
    ///
    /// @return the history entries
    public List<String> entries() {
        return List.copyOf(entries);
    }

    /// Number of retained queries.
    ///
    /// @return the entry count
    public int size() {
        return entries.size();
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>(entries.size());
            for (String e : entries) {
                lines.add(escape(e));
            }
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write history file " + file, e);
        }
    }

    private static Path defaultFile() {
        return Path.of(System.getProperty("user.home", "."), ".vortex", "sql_history");
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                out.append(next == 'n' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
