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

    /// Row bytes laid out back-to-back in one array, with `count + 1` cumulative offsets
    /// delimiting them: row `i` spans `bytes[offsets[i], offsets[i + 1])`.
    ///
    /// This is the shape the Rust reference gets for free — its varbin arrays already store bytes
    /// contiguously, so training and compression borrow slices and allocate nothing per row. Going
    /// through [#toByteArrays(Object)] instead materializes one `byte[]` object per row, which for
    /// a 50k-row chunk is 50k live objects the collector must trace for as long as the encode runs
    /// (profiled as the single largest allocation source in an FSST write). Here that is two
    /// arrays regardless of row count.
    ///
    /// @param bytes   all rows' bytes concatenated
    /// @param offsets `count + 1` cumulative offsets into `bytes`
    /// @param count   the number of rows
    @SuppressWarnings("java:S6218") // internal data carrier; array components flow through the encode pipeline and are never compared.
    record Rows(byte[] bytes, int[] offsets, int count) {
    }

    /// Converts `data` to a single contiguous byte array plus row offsets, treating a `null` entry
    /// as a zero-length row (the same convention [#toByteArrays(Object)] uses).
    ///
    /// Returns `null` when the rows do not fit a single `byte[]` (total length above
    /// [Integer#MAX_VALUE]); the caller must fall back to the per-row [#toByteArrays(Object)]
    /// shape, which has no such ceiling.
    ///
    /// @param data a `String[]` (UTF-8 encoded) or `byte[][]`
    /// @return the contiguous rows, or `null` if they exceed a single array's capacity
    static Rows toContiguous(Object data) {
        if (data instanceof byte[][] raw) {
            long total = 0;
            for (byte[] row : raw) {
                total += row == null ? 0 : row.length;
            }
            if (total > Integer.MAX_VALUE) {
                return null;
            }
            byte[] bytes = new byte[(int) total];
            int[] offsets = new int[raw.length + 1];
            int pos = 0;
            for (int i = 0; i < raw.length; i++) {
                byte[] row = raw[i];
                if (row != null) {
                    System.arraycopy(row, 0, bytes, pos, row.length);
                    pos += row.length;
                }
                offsets[i + 1] = pos;
            }
            return new Rows(bytes, offsets, raw.length);
        }

        String[] strings = (String[]) data;
        int[] offsets = new int[strings.length + 1];
        // UTF-8 never encodes a char to more than 3 bytes (a surrogate pair is 2 chars -> 4 bytes,
        // so 3 per char still bounds it), but sizing for that worst case up front would triple the
        // buffer for the overwhelmingly common ASCII input. Start at one byte per char and grow.
        long estimate = 0;
        for (String s : strings) {
            estimate += s == null ? 0 : s.length();
        }
        if (estimate > Integer.MAX_VALUE) {
            return null;
        }
        byte[] bytes = new byte[Math.max((int) estimate, 16)];
        int pos = 0;
        for (int i = 0; i < strings.length; i++) {
            String s = strings[i];
            if (s != null && !s.isEmpty()) {
                byte[] encoded = s.getBytes(StandardCharsets.UTF_8);
                if (encoded.length > bytes.length - pos) {
                    long needed = (long) pos + encoded.length;
                    if (needed > Integer.MAX_VALUE) {
                        return null;
                    }
                    bytes = java.util.Arrays.copyOf(bytes,
                            (int) Math.min(Integer.MAX_VALUE, Math.max(needed, bytes.length * 2L)));
                }
                System.arraycopy(encoded, 0, bytes, pos, encoded.length);
                pos += encoded.length;
            }
            offsets[i + 1] = pos;
        }
        return new Rows(bytes, offsets, strings.length);
    }

    /// Like [#toRawByteArrays(Object)], but substitutes a zero-length array for every `null`
    /// entry — the values child of a masked/nullable layout, where validity (not this array)
    /// carries nullity, so a null entry's bytes are never read back.
    ///
    /// @param data a `String[]` (UTF-8 encoded) or `byte[][]` (returned row-for-row unchanged)
    /// @return the row bytes, with `null` entries replaced by a zero-length array
    static byte[][] toByteArrays(Object data) {
        byte[][] raw = toRawByteArrays(data);
        // Copy-on-first-null: the common case (a non-nullable column, or a nullable one with no
        // nulls in this chunk) has nothing to substitute, so `raw` is returned as-is — no second
        // outer-array allocation, and no second pass past the point a null is (not) found.
        byte[][] out = raw;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == null) {
                if (out == raw) {
                    out = raw.clone();
                }
                out[i] = EMPTY;
            }
        }
        return out;
    }
}
