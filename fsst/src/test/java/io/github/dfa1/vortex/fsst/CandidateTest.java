package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// The order training takes candidates in, as fsst-rs 0.7's `Candidate::cmp` orders them.
class CandidateTest {

    @Test
    void bestFirst_higherGainFirst() {
        // Given
        TrainingGeneration.Candidate low = new TrainingGeneration.Candidate(3, 1, 'A');
        TrainingGeneration.Candidate high = new TrainingGeneration.Candidate(9, 1, 'B');

        // When
        List<TrainingGeneration.Candidate> sorted = sorted(low, high);

        // Then
        assertThat(sorted).containsExactly(high, low);
    }

    @Test
    void bestFirst_equalGain_longerFirst() {
        // Given
        TrainingGeneration.Candidate shorter = new TrainingGeneration.Candidate(7, 2, 0x4141);
        TrainingGeneration.Candidate longer = new TrainingGeneration.Candidate(7, 5, 0x4141414141L);

        // When
        List<TrainingGeneration.Candidate> sorted = sorted(shorter, longer);

        // Then
        assertThat(sorted).containsExactly(longer, shorter);
    }

    @Test
    void bestFirst_equalGainAndLength_largerSymbolFirst() {
        // Given "aa" < "bb" as little-endian u64, so "bb" goes first
        TrainingGeneration.Candidate aa = new TrainingGeneration.Candidate(10, 2, 0x6161);
        TrainingGeneration.Candidate bb = new TrainingGeneration.Candidate(10, 2, 0x6262);

        // When
        List<TrainingGeneration.Candidate> sorted = sorted(aa, bb);

        // Then
        assertThat(sorted).containsExactly(bb, aa);
    }

    @Test
    void bestFirst_comparesSymbolsUnsigned() {
        // Given an 8-byte symbol whose last byte has the sign bit set, which is the larger u64
        TrainingGeneration.Candidate high = new TrainingGeneration.Candidate(10, 8, 0xFF41414141414141L);
        TrainingGeneration.Candidate low = new TrainingGeneration.Candidate(10, 8, 0x4141414141414141L);

        // When
        List<TrainingGeneration.Candidate> sorted = sorted(low, high);

        // Then
        assertThat(sorted).containsExactly(high, low);
    }

    @Test
    void bestFirst_anyInsertionOrder_sameSequence() {
        // Given 200 distinct candidates with many equal gains and lengths, in random orders
        Random random = new Random(11);
        List<TrainingGeneration.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            candidates.add(new TrainingGeneration.Candidate(random.nextInt(4), 1 + random.nextInt(2), i));
        }
        List<TrainingGeneration.Candidate> expected = new ArrayList<>(candidates);
        expected.sort(TrainingGeneration.Candidate.BEST_FIRST);

        for (int round = 0; round < 10; round++) {
            Collections.shuffle(candidates, random);

            // When
            List<TrainingGeneration.Candidate> actual = new ArrayList<>(candidates);
            actual.sort(TrainingGeneration.Candidate.BEST_FIRST);

            // Then
            assertThat(actual).isEqualTo(expected);
        }
    }

    private static List<TrainingGeneration.Candidate> sorted(TrainingGeneration.Candidate... candidates) {
        List<TrainingGeneration.Candidate> out = new ArrayList<>(List.of(candidates));
        out.sort(TrainingGeneration.Candidate.BEST_FIRST);
        return out;
    }
}
