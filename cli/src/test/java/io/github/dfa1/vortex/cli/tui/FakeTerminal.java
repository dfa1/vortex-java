package io.github.dfa1.vortex.cli.tui;

import io.github.dfa1.vortex.cli.tui.term.Key;
import io.github.dfa1.vortex.cli.tui.term.Terminal;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/// Scripted, in-memory [Terminal] for driving the TUI loops without a real console.
///
/// `readKey()` replays the supplied key script in order; once exhausted it returns
/// [Key.Eof] so the loop terminates instead of blocking forever. The timed
/// `readKey(Duration)` overload emits a configurable number of empty results first
/// (exercising the idle-tick branch) before replaying the script. All `write`
/// output is captured into [#output()] for assertions.
final class FakeTerminal implements Terminal {

    private final Deque<Key> keys;
    private final Size size;
    private final StringBuilder out = new StringBuilder();
    private int pendingTimeouts;
    private boolean closed;

    FakeTerminal(Size size, List<Key> script) {
        this(size, script, 0);
    }

    FakeTerminal(Size size, List<Key> script, int idleTicks) {
        this.size = size;
        this.keys = new ArrayDeque<>(script);
        this.pendingTimeouts = idleTicks;
    }

    @Override
    public Size size() {
        return size;
    }

    @Override
    public void write(String s) {
        out.append(s);
    }

    @Override
    public void flush() {
        // no-op: nothing is buffered
    }

    @Override
    public Key readKey() {
        return keys.isEmpty() ? Key.Eof.INSTANCE : keys.poll();
    }

    @Override
    public Optional<Key> readKey(Duration timeout) {
        if (pendingTimeouts > 0) {
            pendingTimeouts--;
            return Optional.empty();
        }
        return Optional.of(keys.isEmpty() ? Key.Eof.INSTANCE : keys.poll());
    }

    @Override
    public void close() {
        closed = true;
    }

    /// All bytes written to the terminal so far.
    ///
    /// @return concatenated `write` output
    String output() {
        return out.toString();
    }

    /// Whether [#close()] has been called.
    ///
    /// @return true once closed
    boolean isClosed() {
        return closed;
    }
}
