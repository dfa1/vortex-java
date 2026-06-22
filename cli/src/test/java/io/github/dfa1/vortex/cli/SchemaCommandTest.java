package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static io.github.dfa1.vortex.cli.CliTestSupport.writeSmallVortex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SchemaCommandTest {

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> SchemaCommand.run(new String[]{"schema"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void missingFile_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                SchemaCommand.run(new String[]{"schema", missing.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
        assertThat(result.stderr()).contains("file not found");
    }

    @Test
    void validFile_printsStructSchemaAndReturnsOk(@TempDir Path tmp) throws IOException {
        // Given — single I64 column named "id" (see CliTestSupport.writeSmallVortex)
        Path file = writeSmallVortex(tmp, "schema.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                SchemaCommand.run(new String[]{"schema", file.toString()}));

        // Then — header + per-column row; row count and column count surfaced
        assertThat(result.status()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout())
                .contains("schema.vortex")
                .contains("3 rows")
                .contains("1 columns")
                .contains("1  id")
                .contains("I64");
    }

    static Stream<Arguments> dtypeCases() {
        DType i64 = new DType.Primitive(PType.I64, false);
        return Stream.of(
                arguments(new DType.Primitive(PType.I64, false), "I64"),
                arguments(new DType.Primitive(PType.I32, true), "I32?"),
                arguments(new DType.Utf8(false), "utf8"),
                arguments(new DType.Utf8(true), "utf8?"),
                arguments(new DType.Binary(false), "binary"),
                arguments(new DType.Binary(true), "binary?"),
                arguments(new DType.Bool(false), "bool"),
                arguments(new DType.Bool(true), "bool?"),
                arguments(new DType.Null(false), "null"),
                arguments(new DType.Decimal((byte) 10, (byte) 2, false), "decimal(10,2)"),
                arguments(new DType.Decimal((byte) 10, (byte) 2, true), "decimal(10,2)?"),
                arguments(new DType.List(i64, false), "list<I64>"),
                arguments(new DType.List(i64, true), "list<I64>?"),
                arguments(new DType.FixedSizeList(i64, 4, false), "list<I64>[4]"),
                arguments(new DType.FixedSizeList(i64, 4, true), "list<I64>[4]?"),
                arguments(new DType.Extension("vortex.uuid", i64, null, false), "ext<vortex.uuid>"),
                arguments(new DType.Extension("vortex.uuid", i64, null, true), "ext<vortex.uuid>?"),
                arguments(new DType.Variant(false), "variant"),
                arguments(new DType.Variant(true), "variant?"),
                arguments(new DType.Struct(List.of("a", "b"), List.of(i64, new DType.Utf8(false)), false),
                        "struct<a: I64, b: utf8>"));
    }

    @ParameterizedTest
    @MethodSource("dtypeCases")
    void formatDType_rendersEachVariant(DType dtype, String expected) {
        // Given a DType variant

        // When
        String result = SchemaCommand.formatDType(dtype);

        // Then it renders to the schema-display string, with a trailing "?" when nullable
        assertThat(result).isEqualTo(expected);
    }
}
