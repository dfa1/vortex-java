package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.reader.compute.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RowFilterTest {

    @Nested
    class Factories {
        @Test
        void gt_createsColumnBoundToGt() {
            // Given / When
            RowFilter sut = RowFilter.gt("price", 100L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("price", new Predicate.Gt(100L)));
        }

        @Test
        void gte_createsColumnBoundToGte() {
            // Given / When
            RowFilter sut = RowFilter.gte("price", 100L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("price", new Predicate.Gte(100L)));
        }

        @Test
        void lt_createsColumnBoundToLt() {
            // Given / When
            RowFilter sut = RowFilter.lt("price", 500L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("price", new Predicate.Lt(500L)));
        }

        @Test
        void lte_createsColumnBoundToLte() {
            // Given / When
            RowFilter sut = RowFilter.lte("price", 500L);

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("price", new Predicate.Lte(500L)));
        }

        @Test
        void eq_createsColumnBoundToEq() {
            // Given / When
            RowFilter sut = RowFilter.eq("status", "open");

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("status", new Predicate.Eq("open")));
        }

        @Test
        void neq_createsColumnBoundToNeq() {
            // Given / When
            RowFilter sut = RowFilter.neq("status", "closed");

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("status", new Predicate.Neq("closed")));
        }

        @Test
        void isNull_createsColumnBoundToIsNull() {
            // Given / When
            RowFilter sut = RowFilter.isNull("status");

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("status", new Predicate.IsNull()));
        }

        @Test
        void isNotNull_createsColumnBoundToIsNotNull() {
            // Given / When
            RowFilter sut = RowFilter.isNotNull("status");

            // Then
            assertThat(sut).isEqualTo(new RowFilter.Column("status", new Predicate.IsNotNull()));
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
            assertThat(outer.filters().get(1))
                    .isEqualTo(new RowFilter.Column("status", new Predicate.Neq("cancelled")));
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
