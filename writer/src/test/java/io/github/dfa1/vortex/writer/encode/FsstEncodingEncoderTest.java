package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.core.testing.DTypes;
import io.github.dfa1.vortex.reader.decode.DecodeContext;

import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.decode.TestRegistry;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;
import io.github.dfa1.vortex.reader.decode.FsstEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FsstEncodingEncoderTest {

    private static final FsstEncodingEncoder ENCODER = new FsstEncodingEncoder();
    private static final FsstEncodingDecoder DECODER = new FsstEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(DECODER, new PrimitiveEncodingDecoder());

    @Nested
    class Encode {

        private static ArrayNode toArrayNode(EncodeNode node) {
            ArrayNode[] children = new ArrayNode[node.children().length];
            for (int i = 0; i < children.length; i++) {
                children[i] = toArrayNode(node.children()[i]);
            }
            return new ArrayNode(node.encodingId(), node.metadata(), children, node.bufferIndices());
        }

        static Stream<Arguments> stringArrays() {
            return Stream.of(
                    Arguments.of("empty-array", new String[0]),
                    Arguments.of("single-empty-string", new String[]{""}),
                    Arguments.of("short-strings", new String[]{"hi", "ok", "no"}),
                    Arguments.of("repeated-bigram", new String[]{"aaaa", "aaaa", "aaaa"}),
                    Arguments.of("long-strings", new String[]{"the quick brown fox jumps over the lazy dog"}),
                    Arguments.of("mixed-lengths", new String[]{"a", "hello", "this is a longer string than twelve"}),
                    Arguments.of("repeated-short", repeat("a", 1)),
                    Arguments.of("repeated-short", repeat("ab", 50)),
                    Arguments.of("all-empty", new String[]{"", "", "", ""}),
                    Arguments.of("unicode", new String[]{"héllo", "wörld", "こんにちは"}),
                    // Long repeated English text: iterative training should learn 3+ byte symbols
                    // (e.g. "the ", "brown"), exercising the multi-byte match path end to end.
                    Arguments.of("repeated-sentence",
                            repeat("the quick brown fox jumps over the lazy dog", 40)),
                    // Common English words repeated: many overlapping multi-byte symbols.
                    Arguments.of("common-words", repeatCycle(
                            new String[]{"hello", "world", "the", "quick", "brown", "hello", "world"}, 60)),
                    // Strings all shorter than the 8-byte symbol cap.
                    Arguments.of("sub-8-byte", new String[]{"a", "ab", "abc", "abcd", "abcde", "abcdef", "abcdefg"}),
                    // Truly incompressible: single distinct-byte-heavy string. Must round-trip and
                    // not blow up beyond ~2x raw (the escape-scheme floor).
                    Arguments.of("incompressible", new String[]{distinctBytes()}),
                    // Non-ASCII multi-byte UTF-8, repeated so symbols can span codepoint boundaries.
                    Arguments.of("utf8-repeated", repeat("café ☕ münchen 日本語テスト", 30)),
                    // Exactly-8-byte symbol repeated: confirms the length-8 boundary encodes/decodes.
                    Arguments.of("exact-8-byte", repeat("ABCDEFGH", 50)),
                    // U16-tier round-trip: forces both uncompLenPType and codesOffPType off the U8
                    // fast path so the actual U16 buffer bytes are read back and verified end to end,
                    // not just the metadata ptype. Row 0 is 300 raw bytes (> 255 -> U16 row lengths);
                    // the six distinct incompressible rows together compress to > 255 bytes
                    // (> 255 cumulative codes offset -> U16 offsets). Multiple rows mean a wrong
                    // U16 stride in the length/offset write path shifts more than one value, so the
                    // per-index round-trip assertion catches an off-by-stride byte-arithmetic bug.
                    Arguments.of("u16-tier-multi-row", u16TierRows())
            );
        }

        /// One 300-byte row followed by 300 single-character rows. The long first row pushes the
        /// maximum raw row length past the U8 range (forcing `U16` uncompressed lengths). Each
        /// single-character row compresses to exactly one code byte regardless of how training
        /// ranks symbols, so the cumulative codes-offset total is deterministically at least 300 —
        /// past the U8 range too (forcing `U16` codes offsets). 301 rows mean a wrong U16 stride in
        /// either buffer write shifts many values, so the per-index round-trip assertion catches an
        /// off-by-stride byte-arithmetic bug rather than a lone corrupted row that might slip by.
        private static String[] u16TierRows() {
            String[] rows = new String[301];
            rows[0] = "a".repeat(300);
            for (int i = 1; i < rows.length; i++) {
                rows[i] = "x";
            }
            return rows;
        }

        private static String[] repeatCycle(String[] cycle, int times) {
            String[] arr = new String[cycle.length * times];
            for (int t = 0; t < times; t++) {
                System.arraycopy(cycle, 0, arr, t * cycle.length, cycle.length);
            }
            return arr;
        }

        private static String distinctBytes() {
            // 0x00..0xFF is not valid UTF-8; use the full printable-ASCII + Latin-1 letter range,
            // each byte distinct so no bigram repeats — the adversarial case for symbol training.
            StringBuilder sb = new StringBuilder();
            for (char c = 'a'; c <= 'z'; c++) {
                sb.append(c);
            }
            for (char c = 'A'; c <= 'Z'; c++) {
                sb.append(c);
            }
            for (char c = '0'; c <= '9'; c++) {
                sb.append(c);
            }
            return sb.toString();
        }

        private static String[] repeat(String s, int n) {
            String[] arr = new String[n];
            java.util.Arrays.fill(arr, s);
            return arr;
        }

        @Test
        void accepts_utf8_true() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.UTF8);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void accepts_binary_true() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.BINARY);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        void accepts_primitive_false() {
            // Given
            // When
            boolean result = ENCODER.accepts(DTypes.I32);

            // Then
            assertThat(result).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("stringArrays")
        void encode_thenDecode_roundtripsAllStrings(String name, String[] values) {
            // Given
            Arena arena = Arena.ofAuto();

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, values, EncodeTestHelper.testCtx());
            MemorySegment[] bufs = result.buffers().toArray(MemorySegment[]::new);
            ArrayNode node = toArrayNode(result.rootNode());
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, values.length, bufs, REGISTRY, arena);
            var decoded = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(decoded.length()).isEqualTo(values.length);
            for (int i = 0; i < values.length; i++) {
                assertThat(decoded.getString(i)).as("index %d", i).isEqualTo(values[i]);
            }
        }

        @Test
        void encode_repeatedText_learnsMultiByteSymbols_compressesBelowRaw() {
            // Given — highly repetitive text where iterative training should build long symbols.
            String[] values = repeat("the quick brown fox jumps over the lazy dog", 100);
            long rawBytes = 0;
            for (String v : values) {
                rawBytes += v.getBytes(StandardCharsets.UTF_8).length;
            }

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, values, EncodeTestHelper.testCtx());
            long compressedBytes = result.buffers().toArray(MemorySegment[]::new)[2].byteSize();
            byte maxSymLen = maxSymbolLength(result);

            // Then — real compression (< half raw) proves multi-byte symbols are in play, and the
            // table learns symbols well past a bigram. The paper-faithful trainer grows symbols to
            // the 8-byte cap on this highly repetitive input (ten of eleven learned symbols are
            // length 8, one is length 3, with the fixed 0x5EED encoder seed), so a `> 4` floor is a
            // stronger regression guard than the old `> 2` with ample headroom — a training change
            // that quietly stopped growing symbols past a trigram would now be caught.
            assertThat(compressedBytes).isLessThan(rawBytes / 2);
            assertThat(maxSymLen).isGreaterThan((byte) 4);
        }

        @Test
        void encode_incompressible_staysUnderTwiceRaw() {
            // Given — all-distinct bytes, the adversarial case: no repeated substrings to learn.
            StringBuilder sb = new StringBuilder();
            for (char c = 'a'; c <= 'z'; c++) {
                sb.append(c);
            }
            String[] values = {sb.toString()};
            long rawBytes = sb.toString().getBytes(StandardCharsets.UTF_8).length;

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, values, EncodeTestHelper.testCtx());
            long compressedBytes = result.buffers().toArray(MemorySegment[]::new)[2].byteSize();

            // Then — must not regress past the escape-scheme floor of ~2x raw.
            assertThat(compressedBytes).isLessThanOrEqualTo(rawBytes * 2);
        }

        private static byte maxSymbolLength(EncodeResult result) {
            MemorySegment symLenBuf = result.buffers().toArray(MemorySegment[]::new)[1];
            byte max = 0;
            for (long i = 0; i < symLenBuf.byteSize(); i++) {
                byte len = symLenBuf.get(ValueLayout.JAVA_BYTE, i);
                if (len > max) {
                    max = len;
                }
            }
            return max;
        }
    }

    @Nested
    class Decode {

        private static DecodeContext buildCtx(
                long[] symbols, byte[] symLens, byte[] compressed,
                int[] uncompLens, int[] codesOffsets, long n
        ) {
            Arena arena = Arena.ofAuto();

            MemorySegment symBuf = arena.allocate(Math.max(symbols.length * 8L, 1), 8);
            for (int i = 0; i < symbols.length; i++) {
                symBuf.setAtIndex(VortexFormat.LE_LONG, i, symbols[i]);
            }

            MemorySegment symLenBuf = arena.allocate(Math.max(symLens.length, 1));
            for (int i = 0; i < symLens.length; i++) {
                symLenBuf.set(ValueLayout.JAVA_BYTE, i, symLens[i]);
            }

            MemorySegment compBuf = arena.allocate(Math.max(compressed.length, 1));
            for (int i = 0; i < compressed.length; i++) {
                compBuf.set(ValueLayout.JAVA_BYTE, i, compressed[i]);
            }

            MemorySegment uncompLenBuf = arena.allocate((long) uncompLens.length * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < uncompLens.length; i++) {
                uncompLenBuf.setAtIndex(VortexFormat.LE_INT, i, uncompLens[i]);
            }

            MemorySegment codesOffBuf = arena.allocate((long) codesOffsets.length * Integer.BYTES, Integer.BYTES);
            for (int i = 0; i < codesOffsets.length; i++) {
                codesOffBuf.setAtIndex(VortexFormat.LE_INT, i, codesOffsets[i]);
            }

            MemorySegment[] segs = {symBuf, symLenBuf, compBuf, uncompLenBuf, codesOffBuf};

            byte[] metaBytes = new ProtoFSSTMetadata(io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal()), io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(PType.I32.ordinal())).encode();

            ArrayNode uncompLensNode = new ArrayNode(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{3});
            ArrayNode codesOffNode = new ArrayNode(
                    EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{4});
            ArrayNode root = new ArrayNode(
                    EncodingId.VORTEX_FSST, MemorySegment.ofArray(metaBytes),
                    new ArrayNode[]{uncompLensNode, codesOffNode}, new int[]{0, 1, 2});

            return new DecodeContext(root, DTypes.UTF8, n, segs, REGISTRY, arena);
        }

        @Test
        void decode_singleByteSymbol_decompressesCorrectly() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[]{0x41L}, new byte[]{1}, new byte[]{0x00, 0x00},
                    new int[]{2}, new int[]{0, 2}, 1);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("AA".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_escapeByte_decompressesCorrectly() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[0], new byte[0], new byte[]{(byte) 0xFF, 0x58, (byte) 0xFF, 0x59},
                    new int[]{2}, new int[]{0, 4}, 1);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("XY".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_multiByteSymbol_decompressesCorrectly() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[]{0x6261L}, new byte[]{2}, new byte[]{0x00},
                    new int[]{2}, new int[]{0, 1}, 1);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("ab".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_multipleStrings_decompressesAll() {
            // Given
            DecodeContext ctx = buildCtx(
                    new long[]{0x48L}, new byte[]{1},
                    new byte[]{0x00, 0x00, 0x00, (byte) 0xFF, 0x21},
                    new int[]{1, 2, 1}, new int[]{0, 1, 3, 5}, 3);

            // When
            var result = (VarBinArray) DECODER.decode(ctx);

            // Then
            assertThat(result.getBytes(0)).isEqualTo("H".getBytes(StandardCharsets.UTF_8));
            assertThat(result.getBytes(1)).isEqualTo("HH".getBytes(StandardCharsets.UTF_8));
            assertThat(result.getBytes(2)).isEqualTo("!".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void decode_missingMetadata_throwsVortexException() {
            // Given
            ArrayNode node = new ArrayNode(EncodingId.VORTEX_FSST, null, new ArrayNode[0], new int[0]);
            DecodeContext ctx = new DecodeContext(node, DTypes.UTF8, 0, new MemorySegment[0], REGISTRY, Arena.ofAuto());

            // When
            // Then
            assertThatThrownBy(() -> DECODER.decode(ctx)).isInstanceOf(VortexException.class);
        }
    }

    @Nested
    class Metadata {

        @Test
        void encode_metadata_ptypes_pickNarrowestThatFits_smallData() throws Exception {
            // Given — row lengths and cumulative offsets both well under 256, so the narrowest
            // ptype (U8) should be picked instead of a fixed-width I32 that wastes 3 bytes/row.
            String[] data = {"hello", "world", "hello", "fsst"};

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            var metaSeg = result.rootNode().metadata();
            ProtoFSSTMetadata meta = ProtoFSSTMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.uncompressed_lengths_ptype().value()).isEqualTo(PType.U8.ordinal());
            assertThat(meta.codes_offsets_ptype().value()).isEqualTo(PType.U8.ordinal());
        }

        @Test
        void encode_metadata_uncompressedLengthsPType_escalates_pastU8Range() throws Exception {
            // Given — a single row longer than 255 bytes. Content is irrelevant here: this
            // ptype tracks raw row length, not compressed size.
            String[] data = {"a".repeat(300)};

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            var metaSeg = result.rootNode().metadata();
            ProtoFSSTMetadata meta = ProtoFSSTMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.uncompressed_lengths_ptype().value()).isEqualTo(PType.U16.ordinal());
        }

        @Test
        void encode_metadata_codesOffsetsPType_escalates_pastU8Range() throws Exception {
            // Given — 300 single-character rows. Each row is too short for any multi-byte
            // symbol match, so it compresses to exactly one code byte regardless of how
            // training ranks candidates; the cumulative codes_offsets total is therefore
            // deterministically 300, past the U8 range, whether or not "x" makes the table.
            String[] data = new String[300];
            java.util.Arrays.fill(data, "x");

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            var metaSeg = result.rootNode().metadata();
            ProtoFSSTMetadata meta = ProtoFSSTMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.uncompressed_lengths_ptype().value()).isEqualTo(PType.U8.ordinal());
            assertThat(meta.codes_offsets_ptype().value()).isEqualTo(PType.U16.ordinal());
        }

        @Test
        void encode_metadata_bothPTypes_escalate_forU16RoundtripCase() throws Exception {
            // Given — the same multi-row data the U16-tier round-trip case uses (Encode
            // .stringArrays "u16-tier-multi-row"). This pins the precondition that the round-trip
            // actually exercises the U16 write path for both buffers: if a future change made
            // either buffer stay in the U8 tier, this fails and the round-trip coverage would
            // silently no longer cover U16.
            String[] data = u16TierRowsCopy();

            // When
            EncodeResult result = ENCODER.encode(DTypes.UTF8, data, EncodeTestHelper.testCtx());
            var metaSeg = result.rootNode().metadata();
            ProtoFSSTMetadata meta = ProtoFSSTMetadata.decode(metaSeg, 0, metaSeg.byteSize());

            // Then
            assertThat(meta.uncompressed_lengths_ptype().value()).isEqualTo(PType.U16.ordinal());
            assertThat(meta.codes_offsets_ptype().value()).isEqualTo(PType.U16.ordinal());
        }

        // Mirror of Encode.u16TierRows() — the nested classes cannot share a private static helper
        // without exposing it, and the intent (deterministic U16-on-both data) is small enough to
        // restate here next to the assertion that depends on it.
        private static String[] u16TierRowsCopy() {
            String[] rows = new String[301];
            rows[0] = "a".repeat(300);
            for (int i = 1; i < rows.length; i++) {
                rows[i] = "x";
            }
            return rows;
        }
    }
}
