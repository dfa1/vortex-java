package io.github.dfa1.vortex.core.testing;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Base class for tests that assert on the `io.github.dfa1.vortex.*` JFR events: a [Recording]
/// runs around each test, and [#events(String)] dumps it, so every event committed before the
/// call is there (a streaming consumer would race the commits).
public abstract class JfrRecorderBase {

    private static final String PREFIX = "io.github.dfa1.vortex.";

    private Recording recording;

    @BeforeEach
    void startRecording() {
        recording = new Recording();
        for (String name : List.of("FileOpened", "FileMapping", "HttpRange", "SegmentDecoded", "ChunkFilter",
                "SegmentEncoded", "SegmentWait", "Compress", "Candidate", "Winner")) {
            recording.enable(PREFIX + name);
        }
        recording.start();
    }

    @AfterEach
    void stopRecording() {
        recording.close();
    }

    /// Returns every event named `name` committed so far, on any thread.
    ///
    /// @param name the event name without the `io.github.dfa1.vortex.` prefix, e.g. `FileOpened`
    /// @return the matching events, oldest first
    protected final List<RecordedEvent> events(String name) {
        try {
            Path dump = Files.createTempFile("vortex-jfr", ".jfr");
            try {
                recording.dump(dump);
                return RecordingFile.readAllEvents(dump).stream()
                        .filter(e -> e.getEventType().getName().equals(PREFIX + name))
                        .toList();
            } finally {
                Files.deleteIfExists(dump);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
