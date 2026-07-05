package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// Architectural fitness function (Neal Ford, *Building Evolutionary Architectures*) guarding a
/// COMPLETENESS characteristic of the documentation: the encodings support table in
/// `docs/compatibility.md` must list exactly the encodings the code actually registers.
///
/// Atomic / triggered / static / automated. Complements [DocsConsistencyTest], which guards
/// *accuracy* (no stale claim); this guards *completeness* (no missing row) — add an encoding
/// without documenting it and the build goes red.
///
/// Ground truth is the `ServiceLoader` set, not a hand-list: every service-registered
/// [EncodingDecoder]/[EncodingEncoder] contributes its `encodingId()` wire string, its decode and
/// encode support, and its implementing class's simple name. The table must match on all four —
/// id present, decoder class named, encoder class named, ✅/❌ flags correct. It asserts, it never
/// rewrites the doc; on drift the message prints the exact rows to add.
///
/// The `Notes` column is human prose (per-encoding supported dtypes) and is deliberately NOT
/// asserted — a fitness function checks the objective characteristic, not editorial content.
class EncodingTableFitnessTest {

    /// One table row's machine-checkable content (Notes excluded).
    private record Row(String decoderClass, String encoderClass, boolean decode, boolean encode) {
    }

    private static final Pattern TABLE_ROW = Pattern.compile(
            "^\\|\\s*`([a-z][a-z0-9._]*)`\\s*\\|\\s*`?([A-Za-z0-9]*)`?\\s*\\|\\s*`?([A-Za-z0-9]*)`?\\s*"
                    + "\\|\\s*([✅❌])\\s*\\|\\s*([✅❌])\\s*\\|",
            Pattern.MULTILINE);

    private static Map<String, Row> documented;
    private static Map<String, String> decoderClassById;
    private static Map<String, String> encoderClassById;

    @BeforeAll
    static void loadGroundTruthAndTable() throws IOException {
        decoderClassById = new TreeMap<>();
        for (EncodingDecoder decoder : ServiceLoader.load(EncodingDecoder.class)) {
            decoderClassById.put(decoder.encodingId().id(), decoder.getClass().getSimpleName());
        }
        encoderClassById = new TreeMap<>();
        for (EncodingEncoder encoder : ServiceLoader.load(EncodingEncoder.class)) {
            encoderClassById.put(encoder.encodingId().id(), encoder.getClass().getSimpleName());
        }

        Path compat = repoRoot().resolve("docs/compatibility.md");
        String encodingsSection = sliceEncodingsTable(Files.readString(compat));
        documented = new LinkedHashMap<>();
        Matcher m = TABLE_ROW.matcher(encodingsSection);
        while (m.find()) {
            documented.put(m.group(1), new Row(
                    m.group(2), m.group(3),
                    m.group(4).equals("✅"), m.group(5).equals("✅")));
        }
    }

    @Test
    void everyRegisteredEncoding_hasATableRow() {
        // Given every encoding id the code registers a decoder or encoder for
        var allIds = new TreeMap<String, Row>();
        for (String id : union(decoderClassById.keySet(), encoderClassById.keySet())) {
            allIds.put(id, new Row(
                    decoderClassById.getOrDefault(id, ""),
                    encoderClassById.getOrDefault(id, ""),
                    decoderClassById.containsKey(id),
                    encoderClassById.containsKey(id)));
        }

        // When missing rows are collected, rendered as ready-to-paste markdown
        var missing = new ArrayList<String>();
        allIds.forEach((id, expected) -> {
            if (!documented.containsKey(id)) {
                missing.add(renderRow(id, expected));
            }
        });

        // Then the table documents every registered encoding
        assertThat(missing).as("encodings registered in code but MISSING from the "
                + "docs/compatibility.md table — add these rows (Notes are yours to fill):\n"
                + String.join("\n", missing)).isEmpty();
    }

    @Test
    void everyTableRow_matchesARegisteredEncoding() {
        // Given every row in the documented table
        var wrong = new ArrayList<String>();

        // When each is checked against the ServiceLoader ground truth
        documented.forEach((id, row) -> {
            boolean known = decoderClassById.containsKey(id) || encoderClassById.containsKey(id);
            if (!known) {
                wrong.add(id + ": no registered decoder or encoder (phantom row)");
                return;
            }
            if (row.decode() != decoderClassById.containsKey(id)) {
                wrong.add(id + ": Decode flag says " + tick(row.decode())
                        + " but a decoder is " + (decoderClassById.containsKey(id) ? "" : "NOT ") + "registered");
            }
            if (row.encode() != encoderClassById.containsKey(id)) {
                wrong.add(id + ": Encode flag says " + tick(row.encode())
                        + " but an encoder is " + (encoderClassById.containsKey(id) ? "" : "NOT ") + "registered");
            }
            if (row.decode() && !row.decoderClass().equals(decoderClassById.get(id))) {
                wrong.add(id + ": Decoder column says `" + row.decoderClass()
                        + "` but the registered class is `" + decoderClassById.get(id) + "`");
            }
            if (row.encode() && !row.encoderClass().equals(encoderClassById.get(id))) {
                wrong.add(id + ": Encoder column says `" + row.encoderClass()
                        + "` but the registered class is `" + encoderClassById.get(id) + "`");
            }
        });

        // Then no row is a phantom, and every class name and flag matches reality
        assertThat(wrong).as("rows in the docs/compatibility.md encodings table that disagree "
                + "with the registered encodings:\n" + String.join("\n", wrong)).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String sliceEncodingsTable(String doc) {
        int start = doc.indexOf("## Encodings");
        assertThat(start).as("## Encodings section in compatibility.md").isNotNegative();
        int end = doc.indexOf("\n## ", start + 1);
        return end < 0 ? doc.substring(start) : doc.substring(start, end);
    }

    private static String renderRow(String id, Row r) {
        return String.format("| `%s` | %s | %s | %s | %s |  |",
                id,
                r.decoderClass().isEmpty() ? "—" : "`" + r.decoderClass() + "`",
                r.encoderClass().isEmpty() ? "—" : "`" + r.encoderClass() + "`",
                tick(r.decode()), tick(r.encode()));
    }

    private static String tick(boolean b) {
        return b ? "✅" : "❌";
    }

    private static <T> List<T> union(java.util.Set<T> a, java.util.Set<T> b) {
        var out = new ArrayList<>(a);
        b.forEach(x -> {
            if (!out.contains(x)) {
                out.add(x);
            }
        });
        return out;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("CLAUDE.md"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repo root (directory with CLAUDE.md)").isNotNull();
        return dir;
    }
}
