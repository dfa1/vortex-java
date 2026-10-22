package io.github.dfa1.vortex.core.compute;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8ValidationTest {

    @ParameterizedTest
    @CsvSource({
            "'', true",
            "61, true",                   // a
            "c3a9, true",                 // é
            "e282ac, true",               // €
            "f09f9880, true",             // U+1F600
            "f48fbfbf, true",             // U+10FFFF, the last code point
            "efbfbd, true",               // U+FFFD
            "c080, false",                // overlong NUL
            "e08080, false",              // overlong 3-byte
            "f0808080, false",            // overlong 4-byte
            "eda080, false",              // U+D800, a surrogate
            "f4908080, false",            // above U+10FFFF
            "f5808080, false",            // lead byte past F4
            "80, false",                  // a stray continuation byte
            "c3, false",                  // truncated
            "e282, false",                // truncated
            "c328, false",                // second byte is not a continuation
            "e28228, false",              // third byte is not a continuation
    })
    void isWellFormed_namedCases(String hex, boolean expected) {
        // Given
        byte[] bytes = HexFormat.of().parseHex(hex);

        // When
        boolean result = Utf8Validation.isWellFormed(bytes, 0, bytes.length);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    void isWellFormed_agreesWithTheJdkDecoder(long seed) {
        // Given: short random byte runs biased toward lead and continuation bytes, so most are
        // near-misses rather than plain ASCII; the JDK's strict decoder is the oracle
        Random random = new Random(seed);
        int[] palette = {0x41, 0x80, 0x8f, 0x90, 0x9f, 0xa0, 0xbf, 0xc2, 0xdf, 0xe0, 0xed, 0xef, 0xf0, 0xf4, 0xf5, 0xff};
        for (int run = 0; run < 2_000; run++) {
            byte[] bytes = new byte[random.nextInt(6)];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) palette[random.nextInt(palette.length)];
            }

            // When
            boolean result = Utf8Validation.isWellFormed(bytes, 0, bytes.length);

            // Then
            assertThat(result).as(HexFormat.of().formatHex(bytes)).isEqualTo(jdkAccepts(bytes));
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 1, true", "1, 3, true", "0, 2, false", "2, 3, false"})
    void isWellFormed_checksOnlyTheRange(int from, int to, boolean expected) {
        // Given: 'a' then 'é' (c3 a9): a range that cuts the 2-byte character is malformed
        byte[] bytes = {0x61, (byte) 0xc3, (byte) 0xa9};

        // When
        boolean result = Utf8Validation.isWellFormed(bytes, from, to);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    private static boolean jdkAccepts(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException _) {
            return false;
        }
    }
}
