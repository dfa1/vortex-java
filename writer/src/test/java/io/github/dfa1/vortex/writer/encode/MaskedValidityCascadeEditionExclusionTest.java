package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifies the exact mechanism [io.github.dfa1.vortex.writer.VortexWriter]'s edition guard
/// (issue #301) relies on: seeding [EncodeContext]'s initial `excluded` set makes
/// [CascadingCompressor] skip an out-of-edition candidate during its cost-based competition and
/// fall back to the best remaining one, instead of the guard only discovering the violation after
/// encoding completes.
///
/// This generalizes to any nested candidate list built the same way, including
/// [SparseEncodingEncoder]'s `INDEX_CASCADE_CANDIDATES` — used by [MaskedEncodingEncoder]'s
/// nested validity-mask cascade to compress a `vortex.sparse` patch-index array, entirely
/// independent of any top-level cascade codec list, which is what makes an `unstable`-family
/// encoding structurally reachable from a plain nullable-column write, not just an explicit
/// custom encoder list. Uses `vortex.constant` vs. `vortex.primitive` (not `fastlanes.delta`) as
/// the competing pair: `DeltaEncodingEncoder` stores its transposed bases and deltas at full
/// width with no further cascading, so it never actually wins a real size competition against raw
/// storage in the current implementation — constant-vs-primitive gives a reliable, easily
/// constructed win margin instead. See adr/0023-vortex-editions-adoption.md.
class MaskedValidityCascadeEditionExclusionTest {

    private static Set<EncodingId> collectEncodingIds(EncodeNode node) {
        Set<EncodingId> ids = new LinkedHashSet<>();
        collectEncodingIds(node, ids);
        return ids;
    }

    private static void collectEncodingIds(EncodeNode node, Set<EncodingId> into) {
        into.add(node.encodingId());
        for (EncodeNode child : node.children()) {
            collectEncodingIds(child, into);
        }
    }

    private static CascadingCompressor compressor() {
        return new CascadingCompressor(List.of(new ConstantEncodingEncoder(), new PrimitiveEncodingEncoder()));
    }

    @Test
    void unrestrictedContext_selectsConstantOverRawPrimitive() {
        // Given — an all-same-value array, cascading enabled, no exclusions
        long[] values = new long[4000];
        java.util.Arrays.fill(values, 7L);
        EncodeContext ctx = EncodeContext.ofDepth(1, Arena.ofAuto(), WriteRegistry.loadAll());

        // When
        EncodeResult result = compressor().encode(new DType.Primitive(PType.I64, false), values, ctx);

        // Then — confirms the data pattern genuinely makes vortex.constant win a fair competition
        // against vortex.primitive, so the next test's exclusion is a real effect, not vacuous
        // (vortex.constant was the only other candidate to fall back from)
        assertThat(collectEncodingIds(result.rootNode())).contains(EncodingId.VORTEX_CONSTANT);
    }

    @Test
    void editionExcludedContext_fallsBackInsteadOfSelectingConstant() {
        // Given — the exact same all-same-value array, but vortex.constant seeded into the
        // initial exclusion set, mirroring how VortexWriter seeds editionExcluded when the
        // configured edition doesn't cover it (see VortexWriter#editionExcluded)
        long[] values = new long[4000];
        java.util.Arrays.fill(values, 7L);
        EncodeContext ctx = EncodeContext.ofDepth(
                1, Arena.ofAuto(), WriteRegistry.loadAll(), Set.of(EncodingId.VORTEX_CONSTANT));

        // When
        EncodeResult result = compressor().encode(new DType.Primitive(PType.I64, false), values, ctx);

        // Then — gracefully falls back to the only remaining candidate; never surfaces a
        // violation here, let alone throws
        assertThat(collectEncodingIds(result.rootNode()))
                .doesNotContain(EncodingId.VORTEX_CONSTANT)
                .contains(EncodingId.VORTEX_PRIMITIVE);
    }
}
