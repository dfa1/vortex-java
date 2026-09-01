package io.github.dfa1.vortex.writer.encode;

import java.nio.charset.StandardCharsets;

/// Normalizes Utf8 (`String[]`) or Binary (`byte[][]`) encoder input to a common `byte[][]`
/// shape — Utf8 elements are UTF-8 encoded, Binary elements pass through unchanged. Shared by
/// every varbin-family encoder ([VarBinEncodingEncoder], [VarBinViewEncodingEncoder],
/// [FsstEncodingEncoder], [ZstdEncodingEncoder]) so a `DType.Binary` column gets the same
/// byte-safe treatment `DType.Utf8` already had (issue #352).
final class VarBinBytes {

    private static final byte[] EMPTY = new byte[0];

    private VarBinBytes() {
    }

    /// Converts `data` to `byte[][]`, preserving `null` entries as Java `null` rather than
    /// substituting a placeholder.
    ///
    /// @param data a `String[]` (UTF-8 encoded) or `byte[][]` (returned row-for-row unchanged)
    /// @return the row bytes, with any `null` entries preserved
    static byte[][] toRawByteArrays(Object data) {
        if (data instanceof byte[][] raw) {
            return raw;
        }
        String[] strings = (String[]) data;
        byte[][] out = new byte[strings.length][];
        for (int i = 0; i < strings.length; i++) {
            out[i] = strings[i] == null ? null : strings[i].getBytes(StandardCharsets.UTF_8);
        }
        return out;
    }

    /// Like [#toRawByteArrays(Object)], but substitutes a zero-length array for every `null`
    /// entry — the values child of a masked/nullable layout, where validity (not this array)
    /// carries nullity, so a null entry's bytes are never read back.
    ///
    /// @param data a `String[]` (UTF-8 encoded) or `byte[][]` (returned row-for-row unchanged)
    /// @return the row bytes, with `null` entries replaced by a zero-length array
    static byte[][] toByteArrays(Object data) {
        byte[][] raw = toRawByteArrays(data);
        byte[][] out = new byte[raw.length][];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i] == null ? EMPTY : raw[i];
        }
        return out;
    }
}
