package io.github.dfa1.vortex.mapper;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnMappingTest {

    record Trade(long ts, String symbol, double price, int volume, boolean buy, Double fee, byte[] orderId) {
    }

    record Narrow(byte i8, short i16, float f32) {
    }

    @Test
    void schema_followsDeclarationOrderNamesAndTypes() {
        // Given
        ColumnMapping<Trade> sut = ColumnMapping.<Trade>builder()
                .i64("ts", Trade::ts)
                .utf8("symbol", Trade::symbol)
                .f64("price", Trade::price)
                .u32("volume", Trade::volume)
                .bool("buy", Trade::buy)
                .nullableF64("fee", Trade::fee)
                .binary("order_id", Trade::orderId)
                .build();

        // When
        DType.Struct result = sut.schema();

        // Then
        assertThat(result.fieldNames()).extracting(ColumnName::value)
                .containsExactly("ts", "symbol", "price", "volume", "buy", "fee", "order_id");
        assertThat(result.fieldTypes()).containsExactly(DType.I64, DType.UTF8, DType.F64, DType.U32, DType.BOOL,
                DType.F64.asNullable(), DType.BINARY);
        assertThat(result.nullable()).isFalse();
    }

    @Test
    void schema_unsignedAndNullableVariantsKeepTheirOwnType() {
        // Given: the signed/unsigned and plain/nullable pairs share a Java type, so only the
        // builder method tells them apart; a copy-paste slip between them would go unnoticed
        ColumnMapping<Narrow> sut = ColumnMapping.<Narrow>builder()
                .i8("a", Narrow::i8)
                .u8("b", Narrow::i8)
                .nullableU8("c", Narrow::i8)
                .i16("d", Narrow::i16)
                .u16("e", Narrow::i16)
                .nullableU16("f", Narrow::i16)
                .f32("g", Narrow::f32)
                .nullableF32("h", Narrow::f32)
                .build();

        // When
        DType.Struct result = sut.schema();

        // Then
        assertThat(result.fieldTypes()).containsExactly(DType.I8, DType.U8, DType.U8.asNullable(), DType.I16,
                DType.U16, DType.U16.asNullable(), DType.F32, DType.F32.asNullable());
    }

    @Test
    void build_rejectsDuplicateColumn() {
        // Given
        ColumnMapping.Builder<Trade> sut = ColumnMapping.<Trade>builder().i64("ts", Trade::ts);

        // When / Then
        assertThatThrownBy(() -> sut.f64("ts", Trade::price))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate field name");
    }

    @Test
    void build_rejectsNoColumns() {
        // Given
        ColumnMapping.Builder<Trade> sut = ColumnMapping.builder();

        // When / Then
        assertThatThrownBy(sut::build)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a mapping needs at least one column");
    }

    @Test
    void write_rejectsNullFromNonNullablePrimitiveGetter() throws IOException {
        // Given: a boxed getter is the only way a non-nullable byte column can see a null
        ColumnMapping<Narrow> sut = ColumnMapping.<Narrow>builder().i8("v", _ -> null).build();
        try (VortexWriter writer = inMemoryWriter(sut)) {

            // When / Then
            assertThatThrownBy(() -> sut.write(writer, List.of(new Narrow((byte) 1, (short) 1, 1f))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("non-nullable column 'v' received null at row 0");
        }
    }

    @Test
    void write_rejectsNullInNonNullableUtf8() throws IOException {
        // Given
        ColumnMapping<Trade> sut = ColumnMapping.<Trade>builder().utf8("symbol", Trade::symbol).build();
        List<Trade> rows = Arrays.asList(trade("A"), trade(null));
        try (VortexWriter writer = inMemoryWriter(sut)) {

            // When / Then
            assertThatThrownBy(() -> sut.write(writer, rows))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("non-nullable column 'symbol' received null at row 1");
        }
    }

    @Test
    void write_acceptsNullInNullableUtf8() throws IOException {
        // Given
        ColumnMapping<Trade> sut = ColumnMapping.<Trade>builder().nullableUtf8("symbol", Trade::symbol).build();
        var out = new ByteArrayOutputStream();

        // When
        try (VortexWriter writer = VortexWriter.create(Channels.newChannel(out), sut.schema(),
                WriteOptions.defaults())) {
            sut.write(writer, Arrays.asList(trade("A"), trade(null)));
        }

        // Then
        assertThat(out.size()).isPositive();
    }

    private static Trade trade(String symbol) {
        return new Trade(0L, symbol, 0d, 0, false, null, new byte[0]);
    }

    private static VortexWriter inMemoryWriter(ColumnMapping<?> mapping) {
        return VortexWriter.create(Channels.newChannel(new ByteArrayOutputStream()), mapping.schema(),
                WriteOptions.defaults());
    }
}
