package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Session;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.extension.ExtensionDecoder;
import io.github.dfa1.vortex.writer.ExtensionEncoder;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.WriteRegistry;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A second third-party extension type, next to [CustomExtensionIntegrationTest]'s vector: an
/// `acme.currency` column with one [Currency] per row, stored as its ISO 4217 code over `Utf8`.
/// Where the vector shows non-primitive storage and typed metadata, this one shows string storage
/// and no metadata ([OpaqueMetadata#EMPTY]), and a decoder that is strict about the values it maps:
/// a code that is not ISO 4217 is refused, not read back as a string.
///
/// `java.util.Currency` suits a row value: it is immutable, `Currency.getInstance` returns one
/// cached instance per code, and an unknown code fails fast.
class CurrencyExtensionIntegrationTest {

    static {
        NativeLoader.loadJni();
    }

    private static final Session SESSION = Session.create();
    private static final ExtensionId CURRENCY = ExtensionId.parse("acme.currency");
    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName PRICE_CURRENCY = ColumnName.of("price_currency");
    private static final List<Currency> CURRENCIES = List.of(
            Currency.getInstance("EUR"), Currency.getInstance("USD"), Currency.getInstance("JPY"),
            Currency.getInstance("CHF"));
    private static final int ROWS = 1_000;

    private static final WriteRegistry WRITE_REGISTRY = WriteRegistry.builder()
            .registerDefaults()
            .register(new CurrencyEncoder())
            .build();
    private static final ReadRegistry READ_REGISTRY = ReadRegistry.builder()
            .registerDefaults()
            .register(new CurrencyDecoder())
            .build();

    @Test
    void fullScan_decodesEveryCurrencyAndNull(@TempDir Path tmp) throws IOException {
        // Given — Currency values written from a Collection, every eleventh row null
        Path file = tmp.resolve("currencies.vortex");
        writeCurrencies(file);

        // When
        List<Currency> result = readCurrencies(file, ScanOptions.all());

        // Then — the same instances, as Currency.getInstance caches one per code
        assertThat(result).isEqualTo(expected(0));
    }

    @Test
    void filteredScan_keepsEachCurrencyWithItsRow(@TempDir Path tmp) throws IOException {
        // Given — a filter on the sibling id column: the scan returns only the matching rows, and
        // each currency must stay next to its own row
        Path file = tmp.resolve("currencies.vortex");
        writeCurrencies(file);
        long threshold = ROWS / 2;

        // When
        List<Currency> result = readCurrencies(file, ScanOptions.all().withFilter(ScanFilter.gte(ID, threshold)));

        // Then
        assertThat(result).isEqualTo(expected(threshold));
    }

    @Test
    void codeThatIsNotIso4217_isRefusedOnDecode(@TempDir Path tmp) throws IOException {
        // Given — a file from a writer without the encoder, its storage written as plain strings
        Path file = tmp.resolve("bad.vortex");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema(false), WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ID, new long[]{0L, 1L}, PRICE_CURRENCY, new String[]{"EUR", "ABC"}));
        }

        try (VortexReader reader = VortexReader.open(file, READ_REGISTRY);
             var iter = reader.scan(ScanOptions.all());
             Chunk chunk = iter.next()) {
            // When / Then
            assertThatThrownBy(() -> chunk.as(PRICE_CURRENCY, Currency.class))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("row 1: ABC is not an ISO 4217 currency code");
        }
    }

    @Test
    void allowUnknown_readsColumnAsItsCodes(@TempDir Path tmp) throws IOException {
        // Given — without the decoder the column reads as its Utf8 storage, Rust's ForeignExtDType
        Path file = tmp.resolve("currencies.vortex");
        writeCurrencies(file);
        ReadRegistry allowUnknown = ReadRegistry.builder().registerDefaults().allowUnknown().build();

        // When
        String firstCode;
        try (VortexReader reader = VortexReader.open(file, allowUnknown);
             var iter = reader.scan(ScanOptions.all());
             Chunk chunk = iter.next()) {
            VarBinArray codes = (VarBinArray) MaskedArray.innerOrSelf(chunk.column(PRICE_CURRENCY));
            firstCode = codes.getString(1);
        }

        // Then
        // row 1 is the second of the four cycling currencies
        assertThat(firstCode).isEqualTo("USD");
    }

    @Test
    void vortexJni_withoutVtable_refusesTheFile(@TempDir Path tmp) throws IOException {
        // Given — the Rust reference has no acme.currency vtable and its JNI session exposes no
        // allow_unknown: the behavior the strict Java default mirrors
        Path file = tmp.resolve("currencies.vortex");
        writeCurrencies(file);
        String uri = file.toAbsolutePath().toUri().toString();

        // When / Then
        assertThatThrownBy(() -> DataSource.open(SESSION, uri).scan(dev.vortex.api.ScanOptions.of()))
                .hasMessageContaining("No such DType extension ID: acme.currency");
    }

    // ── the custom extension type ────────────────────────────────────────────

    /// The `acme.currency` dtype: `Utf8` storage of ISO 4217 codes, no metadata.
    static DType.Extension currencyDtype(boolean nullable) {
        return new DType.Extension(CURRENCY, nullable ? DTypes.UTF8.asNullable() : DTypes.UTF8,
                OpaqueMetadata.EMPTY, nullable);
    }

    /// Shared by encoder and decoder, as one Rust `ExtVTable::validate_dtype` serves both directions.
    static void validateCurrency(DType.Extension dtype) {
        if (!(dtype.storageDType() instanceof DType.Utf8)) {
            throw new VortexException("acme.currency storage must be Utf8, got " + dtype.storageDType());
        }
    }

    static final class CurrencyEncoder implements ExtensionEncoder {

        @Override
        public ExtensionId extensionId() {
            return CURRENCY;
        }

        @Override
        public DType.Extension dtype(boolean nullable) {
            return currencyDtype(nullable);
        }

        @Override
        public void validate(DType.Extension dtype) {
            validateCurrency(dtype);
        }

        @Override
        public Object encodeAll(DType.Extension dtype, Collection<?> values) {
            String[] codes = new String[values.size()];
            boolean[] validity = new boolean[codes.length];
            boolean anyNull = false;
            int row = 0;
            for (Object value : values) {
                if (value == null) {
                    anyNull = true;
                    codes[row] = "";
                } else {
                    codes[row] = ((Currency) value).getCurrencyCode();
                    validity[row] = true;
                }
                row++;
            }
            if (!anyNull) {
                return codes;
            }
            if (!dtype.nullable()) {
                throw new VortexException("null element in non-nullable acme.currency column");
            }
            return new NullableData(codes, validity);
        }
    }

    static final class CurrencyDecoder implements ExtensionDecoder<Currency> {

        @Override
        public ExtensionId extensionId() {
            return CURRENCY;
        }

        @Override
        public DType.Extension dtype(boolean nullable) {
            return currencyDtype(nullable);
        }

        @Override
        public Class<Currency> domainType() {
            return Currency.class;
        }

        @Override
        public void validate(DType.Extension dtype) {
            validateCurrency(dtype);
        }

        @Override
        public List<Currency> decodeAll(DType.Extension dtype, Array storage) {
            MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(storage);
            VarBinArray codes = (VarBinArray) unwrapped.inner();
            int n = Math.toIntExact(codes.length());
            List<Currency> out = new ArrayList<>(n);
            for (int row = 0; row < n; row++) {
                if (unwrapped.validity() != null && !unwrapped.validity().getBoolean(row)) {
                    out.add(null);
                    continue;
                }
                String code = codes.getString(row);
                try {
                    out.add(Currency.getInstance(code));
                } catch (IllegalArgumentException e) {
                    throw new VortexException("row " + row + ": " + code + " is not an ISO 4217 currency code", e);
                }
            }
            return out;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static DType.Struct schema(boolean nullable) {
        return new DType.Struct(List.of(ID, PRICE_CURRENCY), List.of(DTypes.I64, currencyDtype(nullable)), false);
    }

    /// Every eleventh row is null; the rest cycle through four currencies.
    private static Currency currency(long row) {
        return row % 11 == 5 ? null : CURRENCIES.get((int) (row % CURRENCIES.size()));
    }

    private static List<Currency> expected(long fromRow) {
        List<Currency> out = new ArrayList<>();
        for (long row = fromRow; row < ROWS; row++) {
            out.add(currency(row));
        }
        return out;
    }

    private static void writeCurrencies(Path file) throws IOException {
        long[] ids = new long[ROWS];
        List<Currency> values = new ArrayList<>(ROWS);
        for (int row = 0; row < ROWS; row++) {
            ids[row] = row;
            values.add(currency(row));
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = VortexWriter.create(ch, schema(true), WriteOptions.defaults(), WRITE_REGISTRY)) {
            writer.writeChunk(Map.of(ID, ids, PRICE_CURRENCY, values));
        }
    }

    /// The decoded currency column, in scan order.
    private static List<Currency> readCurrencies(Path file, ScanOptions options) throws IOException {
        List<Currency> out = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file, READ_REGISTRY);
             var iter = reader.scan(options)) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    out.addAll(chunk.as(PRICE_CURRENCY, Currency.class));
                }
            }
        }
        return out;
    }
}
