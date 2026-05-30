package io.github.dfa1.vortex.cli;

public final class ExitStatus {

    public static final int OK             = 0;
    public static final int USAGE_ERROR    = 1;
    public static final int FILE_NOT_FOUND = 2;
    public static final int ERROR          = 3;

    private ExitStatus() {
    }
}
