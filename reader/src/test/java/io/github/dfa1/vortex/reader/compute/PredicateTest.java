package io.github.dfa1.vortex.reader.compute;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PredicateTest {

    @Nested
    class Construction {

        @Test
        void eqRoundTripsValue() {
            // Given an equality predicate over a scalar
            Predicate.Eq sut = new Predicate.Eq(42L);

            // When reading the value back
            Object result = sut.value();

            // Then the accessor returns what was supplied
            assertThat(result).isEqualTo(42L);
        }

        @Test
        void neqRoundTripsValue() {
            // Given an inequality predicate over a scalar
            Predicate.Neq sut = new Predicate.Neq(42L);

            // When reading the value back
            Object result = sut.value();

            // Then the accessor returns what was supplied
            assertThat(result).isEqualTo(42L);
        }

        @Test
        void gteRoundTripsValue() {
            // Given an inclusive greater-than-or-equal predicate
            Predicate.Gte sut = new Predicate.Gte(7);

            // When reading the value back
            Comparable<?> result = sut.value();

            // Then the accessor returns what was supplied
            assertThat(result).isEqualTo(7);
        }

        @Test
        void lteRoundTripsValue() {
            // Given an inclusive less-than-or-equal predicate
            Predicate.Lte sut = new Predicate.Lte(9);

            // When reading the value back
            Comparable<?> result = sut.value();

            // Then the accessor returns what was supplied
            assertThat(result).isEqualTo(9);
        }

        @Test
        void ltRoundTripsValue() {
            // Given a strict less-than predicate
            Predicate.Lt sut = new Predicate.Lt(100);

            // When reading the value back
            Comparable<?> result = sut.value();

            // Then the accessor returns what was supplied
            assertThat(result).isEqualTo(100);
        }

        @Test
        void gtRoundTripsValue() {
            // Given a strict greater-than predicate
            Predicate.Gt sut = new Predicate.Gt(3.5);

            // When reading the value back
            Comparable<?> result = sut.value();

            // Then the accessor returns what was supplied
            assertThat(result).isEqualTo(3.5);
        }

        @Test
        void betweenRoundTripsBothBounds() {
            // Given an inclusive range predicate
            Predicate.Between sut = new Predicate.Between(1, 9);

            // When reading both bounds back
            Comparable<?> result = sut.lo();

            // Then both accessors return what was supplied
            assertThat(result).isEqualTo(1);
            assertThat(sut.hi()).isEqualTo(9);
        }

        @Test
        void isNullHasNoComponents() {
            // Given the dedicated null test
            Predicate sut = new Predicate.IsNull();

            // When inspecting it
            Predicate result = sut;

            // Then it is simply an IsNull instance carrying no state
            assertThat(result).isInstanceOf(Predicate.IsNull.class);
        }

        @Test
        void isNotNullHasNoComponents() {
            // Given the dedicated not-null test — present so the vocabulary is a superset of RowFilter
            Predicate sut = new Predicate.IsNotNull();

            // When inspecting it
            Predicate result = sut;

            // Then it is simply an IsNotNull instance carrying no state
            assertThat(result).isInstanceOf(Predicate.IsNotNull.class);
        }

        @Test
        void andExposesBothOperands() {
            // Given a conjunction of two leaves
            Predicate left = new Predicate.Gt(0);
            Predicate right = new Predicate.Lt(10);
            Predicate.And sut = new Predicate.And(left, right);

            // When reading the operands back
            Predicate result = sut.left();

            // Then both operands round-trip
            assertThat(result).isSameAs(left);
            assertThat(sut.right()).isSameAs(right);
        }

        @Test
        void orExposesBothOperands() {
            // Given a disjunction of two leaves
            Predicate left = new Predicate.Eq("a");
            Predicate right = new Predicate.Eq("b");
            Predicate.Or sut = new Predicate.Or(left, right);

            // When reading the operands back
            Predicate result = sut.left();

            // Then both operands round-trip
            assertThat(result).isSameAs(left);
            assertThat(sut.right()).isSameAs(right);
        }
    }

    @Nested
    class NullGuards {

        @Test
        void eqRejectsNullValue() {
            // Given a null value — IsNull is the dedicated null test, so null here is a programming error
            // When constructing an equality predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Eq(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void neqRejectsNullValue() {
            // Given a null value — IsNull is the dedicated null test, so null here is a programming error
            // When constructing an inequality predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Neq(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void gteRejectsNullValue() {
            // Given a null value
            // When constructing a greater-than-or-equal predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Gte(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void lteRejectsNullValue() {
            // Given a null value
            // When constructing a less-than-or-equal predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Lte(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void ltRejectsNullValue() {
            // Given a null value
            // When constructing a less-than predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Lt(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void gtRejectsNullValue() {
            // Given a null value
            // When constructing a greater-than predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Gt(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        void betweenRejectsNullLo() {
            // Given a null lower bound
            // When constructing a range predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Between(null, 9))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void betweenRejectsNullHi() {
            // Given a null upper bound
            // When constructing a range predicate
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Between(1, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void andRejectsNullLeft() {
            // Given a null left operand
            // When constructing a conjunction
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.And(null, new Predicate.IsNull()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void andRejectsNullRight() {
            // Given a null right operand
            // When constructing a conjunction
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.And(new Predicate.IsNull(), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void orRejectsNullLeft() {
            // Given a null left operand
            // When constructing a disjunction
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Or(null, new Predicate.IsNull()))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void orRejectsNullRight() {
            // Given a null right operand
            // When constructing a disjunction
            // Then it is rejected
            assertThatThrownBy(() -> new Predicate.Or(new Predicate.IsNull(), null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Composition {

        @Test
        void nestsArbitrarilyDeep() {
            // Given a tree mixing And/Or with leaves: (x > 0 AND x < 10) OR x IS NULL — proves the
            // composite variants recurse over other predicates, not just leaves
            Predicate range = new Predicate.And(new Predicate.Gt(0), new Predicate.Lt(10));
            Predicate.Or sut = new Predicate.Or(range, new Predicate.IsNull());

            // When walking back into the nested conjunction
            Predicate result = sut.left();

            // Then the nested And and its leaves are reachable through the tree
            assertThat(result).isInstanceOf(Predicate.And.class);
            Predicate.And nested = (Predicate.And) result;
            assertThat(nested.left()).isEqualTo(new Predicate.Gt(0));
            assertThat(nested.right()).isEqualTo(new Predicate.Lt(10));
            assertThat(sut.right()).isInstanceOf(Predicate.IsNull.class);
        }
    }

    @Nested
    class ValueSemantics {

        @Test
        void structurallyIdenticalTreesAreEqual() {
            // Given two independently built but structurally identical trees
            Predicate sut = new Predicate.And(new Predicate.Gt(0), new Predicate.Lt(10));

            // When comparing against an equal tree
            Predicate result = new Predicate.And(new Predicate.Gt(0), new Predicate.Lt(10));

            // Then the record contract gives value equality and matching hash codes
            assertThat(sut).isEqualTo(result).hasSameHashCodeAs(result);
        }

        @Test
        void differingLeafValuesBreakEquality() {
            // Given two trees that differ only in a single leaf's value
            Predicate sut = new Predicate.And(new Predicate.Gt(0), new Predicate.Lt(10));

            // When comparing against a tree with a different bound
            Predicate result = new Predicate.And(new Predicate.Gt(0), new Predicate.Lt(11));

            // Then they are unequal
            assertThat(sut).isNotEqualTo(result);
        }

        @Test
        void noArgVariantsOfSameTypeAreEqual() {
            // Given two IsNull instances — no components means all instances of the type are equal
            Predicate sut = new Predicate.IsNull();

            // When comparing against another IsNull
            Predicate result = new Predicate.IsNull();

            // Then they are equal, while a different no-arg variant is not
            assertThat(sut).isEqualTo(result).isNotEqualTo(new Predicate.IsNotNull());
        }
    }

    @Nested
    class Sealedness {

        @Test
        void exhaustivePatternSwitchCoversEveryVariant() {
            // Given one instance of every variant — the switch below has no default, so it compiles
            // only while the permits clause stays in sync with the cases, pinning the sealed contract
            Predicate[] all = {
                new Predicate.Eq(1),
                new Predicate.Neq(1),
                new Predicate.Lt(1),
                new Predicate.Gt(1),
                new Predicate.Lte(1),
                new Predicate.Gte(1),
                new Predicate.Between(0, 2),
                new Predicate.IsNull(),
                new Predicate.IsNotNull(),
                new Predicate.And(new Predicate.IsNull(), new Predicate.IsNotNull()),
                new Predicate.Or(new Predicate.IsNull(), new Predicate.IsNotNull())
            };

            // When labeling each variant through an exhaustive, default-free pattern switch
            String result = label(all[0]);

            // Then every variant resolves to a distinct label
            assertThat(result).isEqualTo("eq");
            assertThat(label(all[1])).isEqualTo("neq");
            assertThat(label(all[2])).isEqualTo("lt");
            assertThat(label(all[3])).isEqualTo("gt");
            assertThat(label(all[4])).isEqualTo("lte");
            assertThat(label(all[5])).isEqualTo("gte");
            assertThat(label(all[6])).isEqualTo("between");
            assertThat(label(all[7])).isEqualTo("isNull");
            assertThat(label(all[8])).isEqualTo("isNotNull");
            assertThat(label(all[9])).isEqualTo("and");
            assertThat(label(all[10])).isEqualTo("or");
        }

        private static String label(Predicate predicate) {
            return switch (predicate) {
                case Predicate.Eq _ -> "eq";
                case Predicate.Neq _ -> "neq";
                case Predicate.Lt _ -> "lt";
                case Predicate.Gt _ -> "gt";
                case Predicate.Lte _ -> "lte";
                case Predicate.Gte _ -> "gte";
                case Predicate.Between _ -> "between";
                case Predicate.IsNull _ -> "isNull";
                case Predicate.IsNotNull _ -> "isNotNull";
                case Predicate.And _ -> "and";
                case Predicate.Or _ -> "or";
            };
        }
    }
}
