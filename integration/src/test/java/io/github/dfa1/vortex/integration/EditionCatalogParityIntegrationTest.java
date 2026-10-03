package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.EncodingId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/// Fitness test for issue #441: [Editions] must mirror Rust's first-party edition declarations
/// (`vortex-edition/src/declarations/`) at the vortex release vortex-jni is pinned to
/// ([RustFixtures#VERSION]). Editions decide what a default write may emit, so a drifted catalog
/// silently allows or refuses different encodings than Rust's writer.
///
/// Parses the declaration sources for every edition id and its `EditionMember::array` members and
/// compares them with the catalog edition by edition, in Rust's declaration order: the core
/// declarations first, then every plugin crate under `encodings/` that declares its own family in
/// `src/editions.rs` (today only `vortex-zstd`'s `zstd`). Layout, dtype and aggregate members are
/// ignored: the catalog models array encodings only.
class EditionCatalogParityIntegrationTest {

    private static final String TAG = RustFixtures.VERSION.substring(1);
    private static final String RAW = "https://raw.githubusercontent.com/vortex-data/vortex/" + TAG + "/";
    private static final String BASE = RAW + "vortex-edition/src/declarations/";
    private static final URI ENCODINGS_LISTING =
            URI.create("https://api.github.com/repos/vortex-data/vortex/contents/encodings?ref=" + TAG);
    private static final Path CACHE_DIR = Path.of("/tmp/vortex-edition-declarations", TAG);

    // `&core::v2026_08::DECLARATION_0,` in mod.rs's EDITION_DECLARATIONS list
    private static final Pattern DECLARATION_REF = Pattern.compile("&(\\w+)::(\\w+)::(\\w+),");
    // `pub const CORE_2026_08_1: EditionId = EditionId::new("core", 2026, 8, 1);`
    private static final Pattern EDITION_CONST = Pattern.compile(
            "const (\\w+): EditionId = EditionId::new\\(\"(\\w+)\", (\\d+), (\\d+), (\\d+)\\)");
    // `pub static DECLARATION_1: EditionDeclaration = EditionDeclaration { ... };`
    private static final Pattern DECLARATION_BLOCK = Pattern.compile(
            "static (\\w+): EditionDeclaration = EditionDeclaration \\{(.*?)\\n};", Pattern.DOTALL);
    private static final Pattern DECLARATION_ID = Pattern.compile("id: (\\w+),");
    private static final Pattern ARRAY_MEMBER = Pattern.compile("EditionMember::array\\(&\"([^\"]+)\"\\)");
    // a directory entry in the GitHub contents listing of `encodings/`
    private static final Pattern LISTED_DIR = Pattern.compile("\"name\":\\s*\"([\\w-]+)\"[^}]*?\"type\":\\s*\"dir\"");

    @Test
    void catalogMatchesRustDeclarations(@TempDir Path tmp) throws Exception {
        // Given — Rust's declarations at the pinned release, as edition id -> array member ids
        LocalHttpCache.assumeNetworkAvailable(URI.create("https://raw.githubusercontent.com"));
        Map<String, Set<String>> rust = rustDeclarations(tmp);
        rust.putAll(pluginDeclarations());

        // When
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Edition edition : Editions.ALL) {
            result.put(edition.id().toString(), ids(edition.added()));
        }

        // Then — same editions, same order, same members
        assertThat(rust).isNotEmpty();
        assertThat(result).containsExactlyEntriesOf(rust);
    }

    private static Map<String, Set<String>> rustDeclarations(Path tmp) throws Exception {
        String mod = fetch(tmp, "mod.rs");
        Map<String, Set<String>> editions = new LinkedHashMap<>();
        Map<String, String> fileCache = new HashMap<>();
        Matcher ref = DECLARATION_REF.matcher(mod.substring(mod.indexOf("EDITION_DECLARATIONS")));
        while (ref.find()) {
            String file = ref.group(1) + "/" + ref.group(2) + ".rs";
            String source = fileCache.computeIfAbsent(file, f -> fetchUnchecked(tmp, f));
            Map<String, String> consts = editionConstants(source);
            Matcher block = DECLARATION_BLOCK.matcher(source);
            while (block.find()) {
                if (!block.group(1).equals(ref.group(3))) {
                    continue;
                }
                Matcher id = DECLARATION_ID.matcher(block.group(2));
                assertThat(id.find()).as("edition id in %s", ref.group()).isTrue();
                editions.put(consts.get(id.group(1)), arrayMembers(block.group(2)));
            }
        }
        return editions;
    }

    /// Editions declared by plugin crates (`encodings/<crate>/src/editions.rs`), in crate order.
    private static Map<String, Set<String>> pluginDeclarations() throws Exception {
        Map<String, Set<String>> editions = new LinkedHashMap<>();
        Matcher dir = LISTED_DIR.matcher(fetchOptional(ENCODINGS_LISTING)
                .orElseThrow(() -> new IllegalStateException("cannot list " + ENCODINGS_LISTING)));
        while (dir.find()) {
            Optional<String> source = fetchOptional(URI.create(RAW + "encodings/" + dir.group(1) + "/src/editions.rs"));
            if (source.isEmpty()) {
                continue;
            }
            Map<String, String> consts = editionConstants(source.get());
            Matcher block = DECLARATION_BLOCK.matcher(source.get());
            while (block.find()) {
                Matcher id = DECLARATION_ID.matcher(block.group(2));
                assertThat(id.find()).as("edition id in encodings/%s", dir.group(1)).isTrue();
                editions.put(consts.get(id.group(1)), arrayMembers(block.group(2)));
            }
        }
        return editions;
    }

    private static Set<String> arrayMembers(String declaration) {
        Set<String> members = new TreeSet<>();
        Matcher member = ARRAY_MEMBER.matcher(declaration);
        while (member.find()) {
            members.add(member.group(1));
        }
        return members;
    }

    /// The body at `uri`, or empty on a 404: most `encodings/` crates declare no edition family.
    private static Optional<String> fetchOptional(URI uri) throws Exception {
        var conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
        int code = conn.getResponseCode();
        if (code == 404) {
            return Optional.empty();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(code < 500 && code != 403,
                () -> "transient or rate-limited response " + code + " for " + uri);
        try (var in = conn.getInputStream()) {
            return Optional.of(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /// Constant name -> the edition id's wire form (`core2026.08.1`), formatted like
    /// `EditionId#toString()`.
    private static Map<String, String> editionConstants(String source) {
        Map<String, String> consts = new HashMap<>();
        Matcher m = EDITION_CONST.matcher(source);
        while (m.find()) {
            consts.put(m.group(1), "%s%d.%02d.%d".formatted(m.group(2).toLowerCase(Locale.ROOT),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))));
        }
        return consts;
    }

    private static Set<String> ids(Set<EncodingId> encodings) {
        Set<String> out = new TreeSet<>();
        for (EncodingId id : encodings) {
            out.add(id.id());
        }
        return out;
    }

    private static String fetch(Path tmp, String file) throws Exception {
        Path local = LocalHttpCache.downloadIfMissing(tmp, CACHE_DIR, URI.create(BASE + file), file.replace('/', '_'));
        return Files.readString(local);
    }

    private static String fetchUnchecked(Path tmp, String file) {
        try {
            return fetch(tmp, file);
        } catch (Exception e) {
            throw new IllegalStateException("cannot fetch " + BASE + file, e);
        }
    }
}
