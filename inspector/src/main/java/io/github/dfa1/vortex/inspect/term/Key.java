package io.github.dfa1.vortex.inspect.term;

/// Decoded terminal input event.
public sealed interface Key {

    /// Up arrow.
    enum ArrowUp implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Down arrow.
    enum ArrowDown implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Left arrow.
    enum ArrowLeft implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Right arrow.
    enum ArrowRight implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Page Up.
    enum PageUp implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Page Down.
    enum PageDown implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Home key.
    enum Home implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// End key.
    enum End implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Enter / Return (LF or CR).
    enum Enter implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Bare Escape key press (no CSI sequence followed).
    enum Escape implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// End of input - stdin closed.
    enum Eof implements Key {
        /// Singleton instance.
        INSTANCE
    }

    /// Printable character.
    ///
    /// @param value ASCII codepoint (multi-byte UTF-8 not decoded here)
    record Char(char value) implements Key {
    }
}
