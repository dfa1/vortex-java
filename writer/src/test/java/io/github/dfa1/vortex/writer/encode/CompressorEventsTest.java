package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompressorEventsTest {

    @Test
    void dtypeName_isRustsShortForm() {
        // Given
        DType struct = new DType.Struct(List.of(ColumnName.of("id"), ColumnName.of("name")),
                List.of(DTypes.I64, DTypes.UTF8.asNullable()), false);

        // When / Then
        assertThat(CompressorEvents.dtypeName(DTypes.I64)).isEqualTo("i64");
        assertThat(CompressorEvents.dtypeName(new DType.Primitive(PType.U16, true))).isEqualTo("u16?");
        assertThat(CompressorEvents.dtypeName(DTypes.UTF8)).isEqualTo("utf8");
        assertThat(CompressorEvents.dtypeName(DTypes.BOOL)).isEqualTo("bool");
        assertThat(CompressorEvents.dtypeName(new DType.List(DTypes.I32, false))).isEqualTo("list(i32)");
        assertThat(CompressorEvents.dtypeName(struct)).isEqualTo("struct{id: i64, name: utf8?}");
    }
}
