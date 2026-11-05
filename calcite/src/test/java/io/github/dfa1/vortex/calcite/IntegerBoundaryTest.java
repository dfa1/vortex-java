package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// SQL reads of the smallest and largest value of every integer type, signed and unsigned (#665):
/// the scan, `MIN`, `MAX` and `SUM` must all return the exact value, never a two's-complement
/// reading of an unsigned high half.
class IntegerBoundaryTest {

    private static final ColumnName COLUMN = ColumnName.of("v");

    @TempDir
    Path tmp;

    @ParameterizedTest
    @EnumSource(value = PType.class, mode = EnumSource.Mode.EXCLUDE, names = {"F16", "F32", "F64"})
    void scan_returnsMinAndMaxExactly(PType ptype) throws Exception {
        // Given
        Path file = write(ptype);

        // When
        List<BigDecimal> values;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select v from vtx.t")) {
            rs.next();
            BigDecimal first = rs.getBigDecimal(1);
            rs.next();
            values = List.of(first, rs.getBigDecimal(1));
        }

        // Then
        assertThat(values).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(min(ptype), max(ptype));
    }

    @ParameterizedTest
    @EnumSource(value = PType.class, mode = EnumSource.Mode.EXCLUDE, names = {"F16", "F32", "F64"})
    void minMaxSum_returnExactly(PType ptype) throws Exception {
        // Given
        Path file = write(ptype);

        // When
        BigDecimal min;
        BigDecimal max;
        BigDecimal sum;
        try (Connection conn = VortexCalcite.connect("vtx", Map.of("t", file));
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("select min(v), max(v), sum(v) from vtx.t")) {
            rs.next();
            min = rs.getBigDecimal(1);
            max = rs.getBigDecimal(2);
            sum = rs.getBigDecimal(3);
        }

        // Then
        assertThat(min).isEqualByComparingTo(min(ptype));
        assertThat(max).isEqualByComparingTo(max(ptype));
        assertThat(sum).isEqualByComparingTo(min(ptype).add(max(ptype)));
    }

    private Path write(PType ptype) throws Exception {
        DType.Struct schema = DType.structBuilder().field(COLUMN, new DType.Primitive(ptype, false)).build();
        long lo = min(ptype).longValue();
        long hi = max(ptype).toBigInteger().longValue();
        Object data = switch (ptype.byteSize()) {
            case 1 -> new byte[]{(byte) lo, (byte) hi};
            case 2 -> new short[]{(short) lo, (short) hi};
            case 4 -> new int[]{(int) lo, (int) hi};
            default -> new long[]{lo, hi};
        };
        Path file = tmp.resolve(ptype + ".vortex");
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(COLUMN, data));
        }
        return file;
    }

    private static BigDecimal min(PType ptype) {
        if (ptype.isUnsigned()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(BigInteger.ONE.shiftLeft(ptype.bits() - 1).negate());
    }

    private static BigDecimal max(PType ptype) {
        int bits = ptype.bits();
        if (ptype.isUnsigned()) {
            return new BigDecimal(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE));
        }
        return new BigDecimal(BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE));
    }
}
