package io.github.dfa1.vortex.csv;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/// Reads CSV records straight from bytes into a [ColumnChunk], never decoding to characters.
///
/// The import used FastCSV, which decodes the input to `char`s and builds a `String` per field;
/// the writer then encodes every string back to UTF-8. Parsing bytes skips both, and since the
/// delimiter, quote and line breaks are ASCII, they can never be part of a multi-byte UTF-8
/// character, so the bytes split exactly where the characters do.
///
/// The format is FastCSV's default, which the import had: RFC 4180 fields, `"` quotes with `""`
/// for a quote inside one, records ended by `\n`, `\r` or `\r\n`, empty lines skipped, and every
/// record holding as many fields as the first. Anything but a delimiter or line break after a
/// closing quote is an error; a quote left open runs to the end of the input.
final class CsvByteReader implements Closeable {

    private static final int BUFFER_BYTES = 1 << 20;
    private static final byte QUOTE = '"';
    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private final InputStream in;
    private final byte delimiter;
    private byte[] buf = new byte[BUFFER_BYTES];
    private int pos;
    private int limit;
    private boolean eof;
    private int expectedFields = -1;
    private long records;

    /// @param in        the CSV bytes
    /// @param delimiter the field delimiter, an ASCII character other than a quote or line break
    CsvByteReader(InputStream in, char delimiter) {
        if (delimiter >= 0x80 || delimiter == QUOTE || delimiter == CR || delimiter == LF) {
            throw new IllegalArgumentException("the CSV delimiter must be an ASCII character other than a quote"
                    + " or line break, got '" + delimiter + "'");
        }
        this.in = in;
        this.delimiter = (byte) delimiter;
    }

    /// Appends the next record's fields to `out` as one row.
    ///
    /// @param out the chunk receiving the record
    /// @return `false` at the end of the input, with nothing appended
    /// @throws IOException if reading fails or the record is malformed
    boolean readRecord(ColumnChunk out) throws IOException {
        while (true) {
            int end = parseRecord(out);
            if (end >= 0) {
                pos = end;
                return true;
            }
            if (end == END_OF_INPUT) {
                return false;
            }
            out.abortRow();
            refill();
        }
    }

    private static final int NEED_MORE = -1;
    private static final int END_OF_INPUT = -2;

    /// Parses the record starting at `pos`, returning where it ends, [#NEED_MORE] when the buffer
    /// ends before the record does (the caller rolls back, refills and parses it again), or
    /// [#END_OF_INPUT].
    private int parseRecord(ColumnChunk out) throws IOException {
        byte[] b = buf;
        int p = pos;
        // An empty line, or the \n of a \r\n split across two reads, holds no record.
        while (p < limit && (b[p] == LF || b[p] == CR)) {
            p++;
        }
        pos = p;
        if (p == limit) {
            return eof ? END_OF_INPUT : NEED_MORE;
        }
        int field = 0;
        while (true) {
            if (p == limit) {
                // a delimiter ended the input: one last, empty field
                if (!eof) {
                    return NEED_MORE;
                }
                out.endField(field);
                return endRecord(out, field + 1, p);
            }
            if (b[p] == QUOTE) {
                p = parseQuoted(out, field, p);
                if (p < 0) {
                    return NEED_MORE;
                }
            } else {
                int start = p;
                byte d = delimiter;
                while (p < limit) {
                    byte c = b[p];
                    if (c == d || c == LF || c == CR) {
                        break;
                    }
                    p++;
                }
                if (p == limit && !eof) {
                    return NEED_MORE;
                }
                out.append(field, b, start, p);
            }
            out.endField(field);
            field++;
            if (p == limit) {
                return endRecord(out, field, p);
            }
            byte c = b[p++];
            if (c != delimiter) {
                // a line break: \r\n is one, a lone \n left by a split read is skipped as an empty line
                if (c == CR && p < limit && b[p] == LF) {
                    p++;
                }
                return endRecord(out, field, p);
            }
        }
    }

    /// Parses the quoted field opening at `p`, returning the position after its closing quote, or
    /// -1 when the buffer ends first.
    private int parseQuoted(ColumnChunk out, int field, int p) throws IOException {
        byte[] b = buf;
        int q = p + 1;
        int chunkStart = q;
        while (true) {
            while (q < limit && b[q] != QUOTE) {
                q++;
            }
            if (q == limit) {
                if (eof) {
                    // FastCSV keeps an unclosed quoted field: it runs to the end of the input
                    out.append(field, b, chunkStart, q);
                    return q;
                }
                return -1;
            }
            out.append(field, b, chunkStart, q);
            if (q + 1 == limit && !eof) {
                // the next byte decides whether this quote is escaped or closing
                return -1;
            }
            if (q + 1 < limit && b[q + 1] == QUOTE) {
                out.append(field, b, q, q + 1);
                q += 2;
                chunkStart = q;
                continue;
            }
            int after = q + 1;
            if (after < limit && b[after] != delimiter && b[after] != LF && b[after] != CR) {
                throw new IOException("record " + (records + 1) + " has characters after a closing quote");
            }
            return after;
        }
    }

    private int endRecord(ColumnChunk out, int fields, int end) throws IOException {
        records++;
        if (expectedFields < 0) {
            expectedFields = fields;
        } else if (fields != expectedFields) {
            throw new IOException("record " + records + " has " + fields + " fields, but the first record had "
                    + expectedFields);
        }
        out.endRow();
        return end;
    }

    /// Keeps the unparsed tail, growing the buffer when one record fills it, and reads more.
    private void refill() throws IOException {
        int tail = limit - pos;
        if (pos == 0 && limit == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        } else {
            System.arraycopy(buf, pos, buf, 0, tail);
        }
        pos = 0;
        limit = tail;
        int read = in.read(buf, limit, buf.length - limit);
        if (read < 0) {
            eof = true;
        } else {
            limit += read;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
