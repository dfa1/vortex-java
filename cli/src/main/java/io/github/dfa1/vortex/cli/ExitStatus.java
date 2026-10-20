package io.github.dfa1.vortex.cli;

/// Process exit codes of the `vortex` command.
public final class ExitStatus {

    /// The command succeeded.
    public static final int OK = 0;
    /// The command line was malformed: unknown command, missing or invalid argument.
    public static final int USAGE_ERROR = 1;
    /// An input file does not exist.
    public static final int FILE_NOT_FOUND = 2;
    /// The command failed for any other reason.
    public static final int ERROR = 3;

    private ExitStatus() {
    }
}
