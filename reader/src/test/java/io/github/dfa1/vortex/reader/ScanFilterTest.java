package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.reader.compute.Predicate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanFilterTest {

    @Nested
    class Factories {
        @Test
        void gt_createsColumnBoundToGt() {
            // Given / When
            ScanFilter sut = ScanFilter.gt(ColumnName.of("price"), 100L);

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("price"), new Predicate.Gt(100L)));
        }

        @Test
        void gte_createsColumnBoundToGte() {
            // Given / When
            ScanFilter sut = ScanFilter.gte(ColumnName.of("price"), 100L);

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("price"), new Predicate.Gte(100L)));
        }

        @Test
        void lt_createsColumnBoundToLt() {
            // Given / When
            ScanFilter sut = ScanFilter.lt(ColumnName.of("price"), 500L);

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("price"), new Predicate.Lt(500L)));
        }

        @Test
        void lte_createsColumnBoundToLte() {
            // Given / When
            ScanFilter sut = ScanFilter.lte(ColumnName.of("price"), 500L);

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("price"), new Predicate.Lte(500L)));
        }

        @Test
        void eq_createsColumnBoundToEq() {
            // Given / When
            ScanFilter sut = ScanFilter.eq(ColumnName.of("status"), "open");

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("status"), new Predicate.Eq("open")));
        }

        @Test
        void neq_createsColumnBoundToNeq() {
            // Given / When
            ScanFilter sut = ScanFilter.neq(ColumnName.of("status"), "closed");

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("status"), new Predicate.Neq("closed")));
        }

        @Test
        void isNull_createsColumnBoundToIsNull() {
            // Given / When
            ScanFilter sut = ScanFilter.isNull(ColumnName.of("status"));

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("status"), new Predicate.IsNull()));
        }

        @Test
        void isNotNull_createsColumnBoundToIsNotNull() {
            // Given / When
            ScanFilter sut = ScanFilter.isNotNull(ColumnName.of("status"));

            // Then
            assertThat(sut).isEqualTo(new ScanFilter.Column(ColumnName.of("status"), new Predicate.IsNotNull()));
        }
    }

    @Nested
    class AndComposition {
        @Test
        void and_instanceMethod_combinesTwoFilters() {
            // Given
            ScanFilter left = ScanFilter.gte(ColumnName.of("price"), 100L);
            ScanFilter right = ScanFilter.lte(ColumnName.of("price"), 500L);

            // When
            ScanFilter sut = left.and(right);

            // Then
            assertThat(sut).isInstanceOf(ScanFilter.And.class);
            assertThat(((ScanFilter.And) sut).filters()).isEqualTo(List.of(left, right));
        }

        @Test
        void and_instanceMethod_chainsMultiple() {
            // Given / When
            ScanFilter sut = ScanFilter.gte(ColumnName.of("price"), 10L)
                                    .and(ScanFilter.lte(ColumnName.of("price"), 500L))
                                    .and(ScanFilter.neq(ColumnName.of("status"), "cancelled"));

            // Then — nested And(And(gte, lte), neq)
            assertThat(sut).isInstanceOf(ScanFilter.And.class);
            ScanFilter.And outer = (ScanFilter.And) sut;
            assertThat(outer.filters()).hasSize(2);
            assertThat(outer.filters().get(0)).isInstanceOf(ScanFilter.And.class);
            assertThat(outer.filters().get(1))
                    .isEqualTo(new ScanFilter.Column(ColumnName.of("status"), new Predicate.Neq("cancelled")));
        }

        @Test
        void and_staticMethod_combinesMultiple() {
            // Given
            ScanFilter a = ScanFilter.gt(ColumnName.of("x"), 0L);
            ScanFilter b = ScanFilter.lt(ColumnName.of("x"), 100L);
            ScanFilter c = ScanFilter.neq(ColumnName.of("x"), 50L);

            // When
            ScanFilter sut = ScanFilter.and(a, b, c);

            // Then
            assertThat(sut).isInstanceOf(ScanFilter.And.class);
            assertThat(((ScanFilter.And) sut).filters()).isEqualTo(List.of(a, b, c));
        }
    }
}
