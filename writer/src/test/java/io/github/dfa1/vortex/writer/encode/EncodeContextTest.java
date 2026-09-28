package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.writer.WriteRegistry;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Exclusion-set derivation. A child is filled under the union of its own [ChildSlot#excluded()]
/// and everything its ancestors already excluded — dropping an ancestor's exclusion would let an
/// encoding the tree already ruled out reappear further down.
class EncodeContextTest {

    private static EncodeContext ctx(Set<EncodingId> initial) {
        return EncodeContext.ofDepth(3, Arena.ofAuto(), WriteRegistry.builder().registerDefaults().build(), initial);
    }

    @Test
    void withExcluded_unionsWithWhatIsAlreadyExcluded() {
        // Given a context that already excludes ALP (an ancestor's policy, or an edition guard)
        EncodeContext sut = ctx(Set.of(EncodingId.VORTEX_ALP));

        // When a child slot adds its own exclusions
        EncodeContext result = sut.withExcluded(Set.of(EncodingId.VORTEX_DICT, EncodingId.VORTEX_SEQUENCE));

        // Then both survive
        assertThat(result.excluded()).containsExactlyInAnyOrder(
                EncodingId.VORTEX_ALP, EncodingId.VORTEX_DICT, EncodingId.VORTEX_SEQUENCE);
    }

    @Test
    void withExcluded_emptyOrRedundant_returnsSameInstance() {
        // Given
        EncodeContext sut = ctx(Set.of(EncodingId.VORTEX_ALP));

        // When / Then — a slot with nothing new to say allocates nothing
        assertThat(sut.withExcluded(Set.of())).isSameAs(sut);
        assertThat(sut.withExcluded(Set.of(EncodingId.VORTEX_ALP))).isSameAs(sut);
    }

    @Test
    void withExcluded_leavesTheSourceContextUntouched() {
        // Given — sibling slots derive from one parent context, so derivation must not mutate it
        EncodeContext sut = ctx(Set.of());

        // When
        sut.withExcluded(Set.of(EncodingId.VORTEX_DICT));

        // Then
        assertThat(sut.excluded()).isEmpty();
    }

    @Test
    void childSlot_copiesItsExclusionSet() {
        // Given a mutable set handed to a slot
        Set<EncodingId> mutable = new java.util.HashSet<>(Set.of(EncodingId.VORTEX_DICT));
        ChildSlot sut = new ChildSlot(io.github.dfa1.vortex.core.model.DType.I64, new long[0], 0, mutable);

        // When the caller keeps mutating it
        mutable.add(EncodingId.VORTEX_SEQUENCE);

        // Then the slot's policy is the one it was constructed with
        assertThat(sut.excluded()).containsExactly(EncodingId.VORTEX_DICT);
    }
}
