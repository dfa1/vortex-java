package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.proto.ProtoOnPairMetadata;
import io.github.dfa1.vortex.core.proto.ProtoPType;
import io.github.dfa1.vortex.core.testing.TestSegments;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnPairEncodingDecoderTest {

    private static final OnPairEncodingDecoder SUT = new OnPairEncodingDecoder();
    private static final ReadRegistry REGISTRY = TestRegistry.ofDecoders(SUT, new PrimitiveEncodingDecoder());

    // Tokens "ab", "c", "de": multi-byte tokens make a wrong offset or length visible in the output.
    private static final String DICT = "abcde";
    private static final int[] DICT_OFFSETS = {0, 2, 3, 5};

    @Test
    void decode_concatenatesTokensPerRow_includingEmptyRow() {
        // Given — "abc" = [ab, c], "" = no codes, "deab" = [de, ab]

        // When
        Array result = decode(DICT_OFFSETS, new short[]{0, 1, 2, 0}, new int[]{0, 2, 2, 4}, new int[]{3, 0, 4});

        // Then
        assertThat(strings((VarBinArray) result)).containsExactly("abc", "", "deab");
    }

    @Test
    void decode_slicedCodesWindow_skipsCodesBeforeFirstBoundary() {
        // Given — Rust's slice keeps the whole codes child and only narrows codes_offsets, so the
        // first boundary is non-zero; decoding from code 0 would emit "abc" instead of "deab".

        // When
        Array result = decode(DICT_OFFSETS, new short[]{0, 1, 2, 0}, new int[]{2, 4}, new int[]{4});

        // Then
        assertThat(strings((VarBinArray) result)).containsExactly("deab");
    }

    @Test
    void decode_codeOutOfDictionaryRange_throws() {
        // Given — code 3 with only 3 tokens (0..2)

        // When / Then
        assertThatThrownBy(() -> decode(DICT_OFFSETS, new short[]{3}, new int[]{0, 1}, new int[]{1}))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void decode_lengthsDisagreeWithCodes_throws() {
        // Given — codes decode to 2 bytes but the row claims 5

        // When / Then
        assertThatThrownBy(() -> decode(DICT_OFFSETS, new short[]{0}, new int[]{0, 1}, new int[]{5}))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("uncompressed_lengths");
    }

    @Test
    void decode_dictOffsetsPastBuffer_throws() {
        // Given — last token ends at 9, the dictionary buffer holds 5 bytes

        // When / Then
        assertThatThrownBy(() -> decode(new int[]{0, 2, 3, 9}, new short[]{0}, new int[]{0, 1}, new int[]{2}))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("exceeds dictionary size");
    }

    private static Array decode(int[] dictOffsets, short[] codes, int[] codesOffsets, int[] lengths) {
        var meta = new ProtoOnPairMetadata(ProtoPType.U32, dictOffsets.length - 1, codes.length,
                ProtoPType.U32, ProtoPType.U16, ProtoPType.U32);
        MemorySegment[] segments = {
            MemorySegment.ofArray(DICT.getBytes(StandardCharsets.UTF_8)),
            TestSegments.leInts(dictOffsets), TestSegments.leShorts(codes),
            TestSegments.leInts(codesOffsets), TestSegments.leInts(lengths)};
        ArrayNode[] children = new ArrayNode[4];
        for (int i = 0; i < 4; i++) {
            children[i] = new ArrayNode(EncodingId.VORTEX_PRIMITIVE, null, new ArrayNode[0], new int[]{i + 1});
        }
        ArrayNode node = new ArrayNode(EncodingId.VORTEX_ONPAIR, MemorySegment.ofArray(meta.encode()),
                children, new int[]{0});
        return SUT.decode(new DecodeContext(node, DType.UTF8, lengths.length, segments, REGISTRY, Arena.ofAuto()));
    }

    private static String[] strings(VarBinArray arr) {
        String[] out = new String[(int) arr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.getString(i);
        }
        return out;
    }
}
