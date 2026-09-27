package io.github.dfa1.vortex.writer.encode;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/// The encode path's shared open-addressing map, which replaced four private copies.
///
/// The cases here are the ones the structure can get quietly wrong: `0` as a stored value (slots
/// use `0` to mean empty, so values are biased by one internally), rehashing while growing, and
/// collision probing. A fault in any of them corrupts a dictionary or a distinct count into
/// something plausible rather than something that throws.
class LongIntMapTest {

    @Nested
    class AbsenceAndZero {

        @Test
        void get_returnsMinusOne_forAKeyNeverStored() {
            // Given
            LongIntMap sut = new LongIntMap(16);
            sut.put(1L, 7);

            // When
            int result = sut.get(2L);

            // Then
            assertThat(result).isEqualTo(-1);
        }

        @Test
        void get_returnsZero_whenZeroWasDeliberatelyStored() {
            // Given — the reason values are biased internally: a slot holding 0 means empty, so
            // an unbiased map could not tell "stored 0" from "never stored".
            LongIntMap sut = new LongIntMap(16);
            sut.put(42L, 0);

            // When
            int result = sut.get(42L);

            // Then
            assertThat(result).isZero();
            assertThat(sut.get(43L)).isEqualTo(-1);
            assertThat(sut.size()).isEqualTo(1);
        }

        @Test
        void get_handlesZeroAsAKey() {
            // Given — 0 is also a legitimate key, distinct from an empty slot
            LongIntMap sut = new LongIntMap(16);
            sut.put(0L, 5);

            // When
            int result = sut.get(0L);

            // Then
            assertThat(result).isEqualTo(5);
        }
    }

    @Nested
    class Increment {

        @Test
        void increment_countsFromOne_andAccumulates() {
            // Given
            LongIntMap sut = new LongIntMap(16);

            // When
            int first = sut.increment(9L);
            int second = sut.increment(9L);
            int third = sut.increment(9L);

            // Then
            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(2);
            assertThat(third).isEqualTo(3);
            assertThat(sut.get(9L)).isEqualTo(3);
            assertThat(sut.size()).isEqualTo(1);
        }

        @Test
        void maxEntry_findsTheMostFrequentKey() {
            // Given — most-frequent is derived by one pass over the table rather than tracked per
            // increment, so it has to agree with the counts after the fact.
            LongIntMap sut = new LongIntMap(16);
            for (int i = 0; i < 3; i++) {
                sut.increment(100L);
            }
            for (int i = 0; i < 7; i++) {
                sut.increment(200L);
            }
            sut.increment(300L);

            // When
            LongIntMap.Entry result = sut.maxEntry();

            // Then
            assertThat(result).isNotNull();
            assertThat(result.key()).isEqualTo(200L);
            assertThat(result.value()).isEqualTo(7);
        }

        @Test
        void maxEntry_breaksTiesOnTheSmallerKey_whateverTheCapacity() {
            // Given — the same two equally-frequent keys in maps sized differently, so they land
            // in different slots. Slot order must not decide the winner: the compressor picks an
            // encoding from this, and that choice has to be reproducible for the same input.
            LongIntMap small = new LongIntMap(4);
            LongIntMap large = new LongIntMap(4096);
            for (LongIntMap sut : new LongIntMap[]{small, large}) {
                sut.increment(77L);
                sut.increment(77L);
                sut.increment(1234L);
                sut.increment(1234L);
            }

            // When
            LongIntMap.Entry fromSmall = small.maxEntry();
            LongIntMap.Entry fromLarge = large.maxEntry();

            // Then
            assertThat(fromSmall).isNotNull();
            assertThat(fromSmall.key()).isEqualTo(77L);
            assertThat(fromSmall.value()).isEqualTo(2);
            assertThat(fromLarge).isEqualTo(fromSmall);
        }

        @Test
        void maxEntry_isNull_whenNothingStored() {
            // Given
            LongIntMap sut = new LongIntMap(16);

            // When
            LongIntMap.Entry result = sut.maxEntry();

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    class GrowthAndCollisions {

        @ParameterizedTest
        @ValueSource(ints = {1, 17, 64, 1000, 5000})
        void everyStoredKeyStillReadsBack_acrossRehashes(int entries) {
            // Given — starts far below `entries` so the table rehashes several times. grow()
            // re-probes every occupied slot, and an entry written while a resize was pending is
            // exactly what a naive implementation drops.
            LongIntMap sut = new LongIntMap(4);
            for (int i = 0; i < entries; i++) {
                sut.put(mix(i), i);
            }

            // When
            int firstMismatch = -1;
            for (int i = 0; i < entries; i++) {
                if (sut.get(mix(i)) != i) {
                    firstMismatch = i;
                    break;
                }
            }

            // Then
            assertThat(firstMismatch).isEqualTo(-1);
            assertThat(sut.size()).isEqualTo(entries);
            assertThat(sut.capacity()).isGreaterThanOrEqualTo(entries);
        }

        @Test
        void incrementSurvivesTheResizeItTriggers() {
            // Given — grow() copies slots whose value is non-zero, so an increment that inserts a
            // key and then trips the load factor must have written the value before resizing.
            LongIntMap sut = new LongIntMap(4);

            // When
            for (int i = 0; i < 500; i++) {
                sut.increment(mix(i));
            }

            // Then
            assertThat(sut.size()).isEqualTo(500);
            for (int i = 0; i < 500; i++) {
                assertThat(sut.get(mix(i))).as("key %d", i).isEqualTo(1);
            }
        }

        @Test
        void add_reportsOnlyTheFirstInsertionOfAKey() {
            // Given
            LongIntMap sut = new LongIntMap(16);

            // When
            boolean first = sut.add(5L);
            boolean again = sut.add(5L);

            // Then
            assertThat(first).isTrue();
            assertThat(again).isFalse();
            assertThat(sut.size()).isEqualTo(1);
        }

        @Test
        void matchesAHashMapOnSeededRandomTraffic() {
            // Given — mixed put/increment/get against java.util.HashMap as the reference, so a
            // probing or rehash fault shows up as a disagreement rather than a plausible number.
            Random random = new Random(20260927L);
            LongIntMap sut = new LongIntMap(8);
            Map<Long, Integer> reference = new HashMap<>();
            for (int i = 0; i < 20_000; i++) {
                long key = random.nextInt(2_000);
                if (random.nextBoolean()) {
                    int value = random.nextInt(1_000);
                    sut.put(key, value);
                    reference.put(key, value);
                } else {
                    int updated = sut.increment(key);
                    int expected = reference.getOrDefault(key, 0) + 1;
                    reference.put(key, expected);
                    assertThat(updated).as("increment of %d at step %d", key, i).isEqualTo(expected);
                }
            }

            // When
            int mismatches = 0;
            for (long key = 0; key < 2_000; key++) {
                int expected = reference.getOrDefault(key, -1);
                if (sut.get(key) != expected) {
                    mismatches++;
                }
            }

            // Then
            assertThat(mismatches).isZero();
            assertThat(sut.size()).isEqualTo(reference.size());
        }
    }

    /// Spreads sequential ints across the key space so the table sees real collisions rather
    /// than a perfectly-strided run.
    private static long mix(int i) {
        return (long) i * 0x9E3779B97F4A7C15L;
    }
}
