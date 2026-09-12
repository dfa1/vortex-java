package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorParserTest {

    @Nested
    class ValidDescriptors {

        @Test
        void parsesASeriesGenerator() {
            // Given
            String descriptor = "timestamp:i64:series(1700000000000,1000)";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.name()).isEqualTo(ColumnName.of("timestamp"));
            assertThat(result.dtype()).isEqualTo(DType.I64);
            assertThat(result.generator()).isEqualTo(new GeneratorSpec.Series(1_700_000_000_000.0, 1000.0));
        }

        @Test
        void parsesARangeGenerator() {
            // Given
            String descriptor = "price:f64:range(50,150)";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.dtype()).isEqualTo(DType.F64);
            assertThat(result.generator()).isEqualTo(new GeneratorSpec.Range(50.0, 150.0));
        }

        @Test
        void parsesANormalGenerator() {
            // Given
            String descriptor = "price:f64:normal(100,15)";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.generator()).isEqualTo(new GeneratorSpec.Normal(100.0, 15.0));
        }

        @Test
        void parsesAnEnumGenerator() {
            // Given
            String descriptor = "symbol:utf8:enum(SYM,30)";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.dtype()).isEqualTo(DType.UTF8);
            assertThat(result.generator()).isEqualTo(new GeneratorSpec.EnumLabels("SYM", 30));
        }

        @Test
        void parsesAConstantGenerator() {
            // Given
            String descriptor = "flag:bool:constant(true)";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.generator()).isEqualTo(new GeneratorSpec.Constant("true"));
        }

        @Test
        void parsesABoolGenerator() {
            // Given
            String descriptor = "active:bool:bool()";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.dtype()).isEqualTo(DType.BOOL);
            assertThat(result.generator()).isEqualTo(new GeneratorSpec.RandomBool());
        }

        @ParameterizedTest
        @ValueSource(strings = {"i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64"})
        void acceptsEveryNumericTypeForSeries(String type) {
            // Given
            String descriptor = "col:" + type + ":series(1,1)";

            // When
            ColumnDescriptor result = DescriptorParser.parse(descriptor);

            // Then
            assertThat(result.dtype()).isInstanceOf(DType.Primitive.class);
        }
    }

    @Nested
    class InvalidDescriptors {

        @Test
        void rejectsMalformedSyntax() {
            // Given
            String descriptor = "not-a-valid-descriptor";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("malformed");
        }

        @Test
        void rejectsUnknownType() {
            // Given
            String descriptor = "col:decimal:range(1,2)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown type");
        }

        @Test
        void rejectsUnknownGenerator() {
            // Given
            String descriptor = "col:i64:fibonacci(1,2)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unknown generator");
        }

        @Test
        void rejectsSeriesOnUtf8Column() {
            // Given
            String descriptor = "col:utf8:series(1,1)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
        }

        @Test
        void rejectsEnumOnNumericColumn() {
            // Given
            String descriptor = "col:i64:enum(A,3)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
        }

        @Test
        void rejectsBoolGeneratorOnNumericColumn() {
            // Given
            String descriptor = "col:i64:bool()";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
        }

        @Test
        void rejectsNonNumericConstantOnNumericColumn() {
            // Given
            String descriptor = "col:i64:constant(not-a-number)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
        }

        @Test
        void rejectsNonBooleanConstantOnBoolColumn() {
            // Given
            String descriptor = "col:bool:constant(maybe)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("incompatible");
        }

        @Test
        void rejectsTooFewGeneratorArguments() {
            // Given
            String descriptor = "col:i64:range(1)";

            // When / Then
            assertThatThrownBy(() -> DescriptorParser.parse(descriptor))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("needs at least");
        }
    }
}
