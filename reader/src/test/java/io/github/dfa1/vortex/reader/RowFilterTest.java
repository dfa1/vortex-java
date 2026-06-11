package io.github.dfa1.vortex.reader;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowFilterTest {

    @Nested
    class Factories {
        @Test
        void gt_createsGtRecord() {
            // Given / When
            RowFilter sut = RowFilter.gt("price", 100L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Gt("price", 100L));
        }

        @Test
        void gte_createsGteRecord() {
            // Given / When
            RowFilter sut = RowFilter.gte("price", 100L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Gte("price", 100L));
        }

        @Test
        void lt_createsLtRecord() {
            // Given / When
            RowFilter sut = RowFilter.lt("price", 500L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Lt("price", 500L));
        }

        @Test
        void lte_createsLteRecord() {
            // Given / When
            RowFilter sut = RowFilter.lte("price", 500L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Lte("price", 500L));
        }

        @Test
        void eq_createsEqRecord() {
            // Given / When
            RowFilter sut = RowFilter.eq("status", "open");

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Eq("status", "open"));
        }

        @Test
        void neq_createsNeqRecord() {
            // Given / When
            RowFilter sut = RowFilter.neq("status", "closed");

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Neq("status", "closed"));
        }
    }

    @Nested
    class AndComposition {
        @Test
        void and_instanceMethod_combinesTwoFilters() {
            // Given
            RowFilter left = RowFilter.gte("price", 100L);
            RowFilter right = RowFilter.lte("price", 500L);

            // When
            RowFilter sut = left.and(right);

            // Then
            assertThat(sut).isInstanceOf(RowFilter.And.class);
            assertThat(((RowFilter.And) sut).filters()).isEqualTo(List.of(left, right));
        }

        @Test
        void and_instanceMethod_chainsMultiple() {
            // Given / When
            RowFilter sut = RowFilter.gte("price", 10L)
                                    .and(RowFilter.lte("price", 500L))
                                    .and(RowFilter.neq("status", "cancelled"));

            // Then — nested And(And(gte, lte), neq)
            assertThat(sut).isInstanceOf(RowFilter.And.class);
            RowFilter.And outer = (RowFilter.And) sut;
            assertThat(outer.filters()).hasSize(2);
            assertThat(outer.filters().get(0)).isInstanceOf(RowFilter.And.class);
            assertThat(outer.filters().get(1)).isInstanceOf(RowFilter.Neq.class);
        }

        @Test
        void and_staticMethod_combinesMultiple() {
            // Given
            RowFilter a = RowFilter.gt("x", 0L);
            RowFilter b = RowFilter.lt("x", 100L);
            RowFilter c = RowFilter.neq("x", 50L);

            // When
            RowFilter sut = RowFilter.and(a, b, c);

            // Then
            assertThat(sut).isInstanceOf(RowFilter.And.class);
            assertThat(((RowFilter.And) sut).filters()).isEqualTo(List.of(a, b, c));
        }
    }
}
