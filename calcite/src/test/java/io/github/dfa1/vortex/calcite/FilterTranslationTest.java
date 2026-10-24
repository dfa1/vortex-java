package io.github.dfa1.vortex.calcite;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.encode.NullableData;

import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Predicate shapes the translator used to leave to Calcite: an `IN` list (an `OR` of equalities
/// once Calcite's `SEARCH` is expanded), a literal on the left, and `IS NULL`. Left untranslated
/// they neither prune chunks nor are enforced by the scan, so Calcite re-checks every row.
class FilterTranslationTest {

    private static final RexBuilder REX = new RexBuilder(new JavaTypeFactoryImpl());
    private static final RelDataType BIGINT = REX.getTypeFactory().createSqlType(SqlTypeName.BIGINT);
    private static final RelDataType NULLABLE_BIGINT = REX.getTypeFactory()
            .createTypeWithNullability(BIGINT, true);

    @TempDir
    static Path tmp;
    private static Path file;

    // two chunks: id 1..3 with no nulls, id 4..6 where n is null on id 5
    @BeforeAll
    static void write() throws Exception {
        file = tmp.resolve("translate.vortex");
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("n"), DType.I64.asNullable())
                .build();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter w = VortexWriter.create(ch, schema, LegacyZoneMaps.OPTIONS)) {
            w.writeChunk(Map.of(ColumnName.of("id"), new long[]{1, 2, 3},
                    ColumnName.of("n"), new NullableData(new long[]{10, 20, 30}, new boolean[]{true, true, true})));
            w.writeChunk(Map.of(ColumnName.of("id"), new long[]{4, 5, 6},
                    ColumnName.of("n"), new NullableData(new long[]{40, 0, 60}, new boolean[]{true, false, true})));
        }
    }

    @Test
    void inList_isEnforcedAndPrunes() throws Exception {
        // Given id IN (1, 3)
        RexNode in = REX.makeIn(REX.makeInputRef(BIGINT, 0), List.of(literal(1), literal(3)));

        // When / Then — only the first chunk is decoded, and the scan enforces the list itself
        assertScan(in, List.of(1L, 3L), 1);
    }

    @Test
    void inListOverTwoColumns_isLeftToCalcite() throws Exception {
        // Given id = 1 OR n = 60 — no single-column predicate captures it
        RexNode or = REX.makeCall(SqlStdOperatorTable.OR,
                REX.makeCall(SqlStdOperatorTable.EQUALS, REX.makeInputRef(BIGINT, 0), literal(1)),
                REX.makeCall(SqlStdOperatorTable.EQUALS, REX.makeInputRef(NULLABLE_BIGINT, 1), literal(60)));
        List<RexNode> filters = new ArrayList<>(List.of(or));

        // When
        List<Object> result = ids(filters);

        // Then — nothing pushed, so Calcite keeps the predicate and the scan returns every row
        assertThat(filters).hasSize(1);
        assertThat(result).hasSize(6);
    }

    @Test
    void literalOnTheLeft_isEnforcedAndPrunes() throws Exception {
        // Given 3 >= id
        RexNode cmp = REX.makeCall(SqlStdOperatorTable.GREATER_THAN_OR_EQUAL, literal(3), REX.makeInputRef(BIGINT, 0));

        // When / Then
        assertScan(cmp, List.of(1L, 2L, 3L), 1);
    }

    @Test
    void isNull_prunesChunksWithoutNulls() throws Exception {
        // Given n IS NULL — the first chunk has a zero null count
        RexNode isNull = REX.makeCall(SqlStdOperatorTable.IS_NULL, REX.makeInputRef(NULLABLE_BIGINT, 1));

        // When / Then
        assertScan(isNull, List.of(5L), 1);
    }

    /// Calcite casts a narrow column up to meet a wider literal (`CAST($0):INTEGER < 5` for a
    /// `SMALLINT`), and the translation rejected anything but a bare column, so such a filter was
    /// neither pushed nor enforced and every aggregate over it fell back to the scan.
    @Test
    void wideningCast_translatesAsTheColumn() {
        // Given smallint < 5, as Calcite writes it
        RelDataType smallint = REX.getTypeFactory().createSqlType(SqlTypeName.SMALLINT);
        RelDataType integer = REX.getTypeFactory().createSqlType(SqlTypeName.INTEGER);
        RexNode cast = REX.makeCast(integer, REX.makeInputRef(smallint, 0));
        RexNode lessThan = REX.makeCall(SqlStdOperatorTable.LESS_THAN, cast,
                REX.makeExactLiteral(BigDecimal.valueOf(5), integer));

        // When
        var result = RexFilterTranslator.translateStrict(List.of(lessThan),
                List.of(ColumnName.of("small")), List.of(DType.I16));

        // Then
        assertThat(result).contains(io.github.dfa1.vortex.reader.ScanFilter.lt(ColumnName.of("small"), 5L));
    }

    /// `BIGINT` to `DOUBLE` rounds past 2^53, so comparing the cast is not comparing the column.
    @Test
    void lossyCast_isLeftToCalcite() {
        // Given bigint-as-double > 1.5
        RelDataType dbl = REX.getTypeFactory().createSqlType(SqlTypeName.DOUBLE);
        RexNode cast = REX.makeCast(dbl, REX.makeInputRef(BIGINT, 0));
        RexNode greater = REX.makeCall(SqlStdOperatorTable.GREATER_THAN, cast,
                REX.makeApproxLiteral(new BigDecimal("1.5"), dbl));

        // When
        var result = RexFilterTranslator.translateStrict(List.of(greater), List.of(ColumnName.of("id")), List.of(DType.I64));

        // Then
        assertThat(result).isEmpty();
    }

    private static void assertScan(RexNode predicate, List<Long> expectedIds, long expectedChunks) {
        // When
        List<RexNode> filters = new ArrayList<>(List.of(predicate));
        VortexTable table = new VortexTable(file);
        List<Object> result = ids(table, filters);

        // Then
        assertThat(filters).as("captured in full, so Calcite does not re-check").isEmpty();
        assertThat(result).containsExactlyElementsOf(expectedIds);
        assertThat(table.chunksScannedLastQuery()).as("chunks decoded").isEqualTo(expectedChunks);
    }

    private static List<Object> ids(List<RexNode> filters) {
        return ids(new VortexTable(file), filters);
    }

    private static List<Object> ids(VortexTable table, List<RexNode> filters) {
        List<Object> ids = new ArrayList<>();
        try (Enumerator<Object[]> en = table.scan(null, filters, new int[]{0}).enumerator()) {
            while (en.moveNext()) {
                ids.add(en.current()[0]);
            }
        }
        return ids;
    }

    private static RexNode literal(long value) {
        return REX.makeExactLiteral(BigDecimal.valueOf(value), BIGINT);
    }
}
