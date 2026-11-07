package io.github.dfa1.vortex.fsst;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// The heap equal candidates pop out of, as Rust's `BinaryHeap` pops them.
class CandidateHeapTest {

    @Test
    void pop_candidatesOfEqualGain_leaveInRustsHeapOrderNotPushOrder() {
        // Given A5, B3, C5, D9 pushed in that order. By Rust's push (sift_up stops at an equal
        // parent) and pop (swap in the last, sift down to the bottom, sift back up): D, C, A, B
        TrainingGeneration.CandidateHeap sut = new TrainingGeneration.CandidateHeap(4);
        sut.push(5, 1, 'A');
        sut.push(3, 1, 'B');
        sut.push(5, 1, 'C');
        sut.push(9, 1, 'D');

        // When
        List<Long> popped = drain(sut);

        // Then
        assertThat(popped).containsExactly((long) 'D', (long) 'C', (long) 'A', (long) 'B');
    }

    @Test
    void pop_allEqual_leavesInPushOrder() {
        // Given three candidates of the same gain and length
        TrainingGeneration.CandidateHeap sut = new TrainingGeneration.CandidateHeap(3);
        sut.push(1, 1, 'A');
        sut.push(1, 1, 'B');
        sut.push(1, 1, 'C');

        // When
        List<Long> popped = drain(sut);

        // Then
        assertThat(popped).containsExactly((long) 'A', (long) 'B', (long) 'C');
    }

    @Test
    void pop_orderedByGainThenLength() {
        // Given a longer symbol and a shorter one of the same gain
        TrainingGeneration.CandidateHeap sut = new TrainingGeneration.CandidateHeap(3);
        sut.push(7, 2, 'S');
        sut.push(7, 5, 'L');
        sut.push(9, 1, 'G');

        // When
        List<Long> popped = drain(sut);

        // Then the higher gain first, then the longer symbol
        assertThat(popped).containsExactly((long) 'G', (long) 'L', (long) 'S');
    }

    @Test
    void pop_randomCandidates_yieldsEachOnceInNonIncreasingOrder() {
        // Given 500 candidates with many equal gains
        Random random = new Random(11);
        TrainingGeneration.CandidateHeap sut = new TrainingGeneration.CandidateHeap(500);
        long[] gains = new long[500];
        for (int i = 0; i < gains.length; i++) {
            gains[i] = random.nextInt(40);
            sut.push(gains[i], 1 + random.nextInt(8), i);
        }

        // When
        List<Long> popped = drain(sut);

        // Then
        assertThat(popped).hasSize(500).doesNotHaveDuplicates();
        for (int i = 1; i < popped.size(); i++) {
            assertThat(gains[(int) (long) popped.get(i)]).isLessThanOrEqualTo(gains[(int) (long) popped.get(i - 1)]);
        }
    }

    private static List<Long> drain(TrainingGeneration.CandidateHeap heap) {
        List<Long> popped = new ArrayList<>();
        while (!heap.isEmpty()) {
            heap.pop();
            popped.add(heap.poppedSymbol());
        }
        return popped;
    }
}
