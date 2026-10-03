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
/// compares them with the catalog edition by edition, in Rust's declaration order. Layout, dtype
/// and aggregate members are ignored: the catalog models array encodings only.
class EditionCatalogParityIntegrationTest {

    private static final String TAG = RustFixtures.VERSION.substring(1);
    private static final String BASE =
            "https://raw.githubusercontent.com/vortex-data/vortex/" + TAG + "/vortex-edition/src/declarations/";
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

    @Test
    void catalogMatchesRustDeclarations(@TempDir Path tmp) throws Exception {
        // Given — Rust's declarations at the pinned release, as edition id -> array member ids
        LocalHttpCache.assumeNetworkAvailable(URI.create("https://raw.githubusercontent.com"));
        Map<String, Set<String>> rust = rustDeclarations(tmp);

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
                Set<String> members = new TreeSet<>();
                Matcher member = ARRAY_MEMBER.matcher(block.group(2));
                while (member.find()) {
                    members.add(member.group(1));
                }
                editions.put(consts.get(id.group(1)), members);
            }
        }
        return editions;
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
