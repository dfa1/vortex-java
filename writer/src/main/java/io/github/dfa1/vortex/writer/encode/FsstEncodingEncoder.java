package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.proto.ProtoFSSTMetadata;
import io.github.dfa1.vortex.fsst.Compressor;
import io.github.dfa1.vortex.fsst.CompressorBuilder;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;

/// Write-only encoder for `vortex.fsst`.
///
/// This class is a thin wire adapter over the standalone `vortex-fsst` module (issue #287): the
/// FSST compression algorithm — symbol-table training and greedy longest-match compression — lives
/// entirely in [CompressorBuilder]/[Compressor]. This adapter converts the input strings to UTF-8
/// bytes, drives training, compresses each row, and lays the result out in the `vortex.fsst` wire
/// format (symbol table buffers, remapped code stream, per-row uncompressed lengths and code
/// offsets, plus the [ProtoFSSTMetadata] describing the two offset ptypes).
///
/// The wire format packs each symbol's bytes LSB-first into a `long` (first byte in the low byte)
/// alongside a per-symbol length byte, and reserves code `0xFF` as the single-literal-byte escape.
/// Real symbol codes are therefore `0..254` (max 255 symbols), each 1-8 bytes long.
///
/// Symbols are laid out on the wire in length order — all multi-byte symbols (length 2-8) first in
/// non-decreasing length order, then all length-1 symbols — as the `vortex.fsst` wire contract
/// requires (mirroring Rust `FSSTData::validate_symbol_lengths`). The [Compressor] instead numbers
/// its codes in gain-descending order, so this adapter remaps every code the compressor emits into
/// that length-sorted wire order (see [#encode]).
public final class FsstEncodingEncoder implements EncodingEncoder {

    /// Escape opcode: emitted as `0xFF` followed by one literal byte.
    private static final int ESCAPE = 0xFF;

    /// Fixed seed for the training-sample PRNG. Encoding must be reproducible: the same input
    /// always trains the same symbol table and produces byte-identical output.
    private static final long TRAINING_SAMPLE_SEED = 0x5EEDL;

    @Override
    public EncodingId encodingId() {
        return EncodingId.VORTEX_FSST;
    }

    @Override
    public boolean accepts(DType dtype) {
        return dtype instanceof DType.Utf8 || dtype instanceof DType.Binary;
    }

    @Override
    public EncodeResult encode(DType dtype, Object data, EncodeContext ctx) {
        Arena arena = ctx.arena();
        Fsst c = compress((String[]) data, arena);

        // Terminal layout: the per-row length and cumulative-offset children are raw primitive
        // segments (buffers 3 and 4). The cascading path (encodeCascade) instead exposes them as
        // open child slots so they can be bitpacked/constant-folded.
        long uncompLenBytes = c.uncompLenPType().byteSize();
        MemorySegment uncompLenBuf = arena.allocate(Math.max((long) c.n() * uncompLenBytes, 1));
        for (int i = 0; i < c.n(); i++) {
            PTypeIO.set(uncompLenBuf, i * uncompLenBytes, c.uncompLenPType(), c.uncompLens()[i]);
        }
        long codesOffBytes = c.codesOffPType().byteSize();
        MemorySegment codesOffBuf = arena.allocate((long) (c.n() + 1) * codesOffBytes);
        for (int i = 0; i <= c.n(); i++) {
            PTypeIO.set(codesOffBuf, i * codesOffBytes, c.codesOffPType(), c.codesOffsets()[i]);
        }

        EncodeNode uncompLensNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 3);
        EncodeNode codesOffNode = EncodeNode.leaf(EncodingId.VORTEX_PRIMITIVE, 4);
        EncodeNode root = new EncodeNode(
                EncodingId.VORTEX_FSST,
                MemorySegment.ofArray(c.metaBytes()),
                new EncodeNode[]{uncompLensNode, codesOffNode},
                new int[]{0, 1, 2});

        return new EncodeResult(root,
                List.of(c.symBuf(), c.symLenBuf(), c.compBuf(), uncompLenBuf, codesOffBuf),
                null, null);
    }

    /// Cascading FSST: expose the per-row uncompressed-length and code-offset children as open
    /// primitive slots so the cascade can compress them — bitpacking the monotonic offsets and
    /// constant-folding the lengths of fixed-width columns (dates, coordinates). Matches the Rust
    /// reference, which stores these children as `fastlanes.bitpacked` / `vortex.constant` rather
    /// than raw primitives; leaving them raw cost ~2-4 bytes/row and was the largest remaining gap
    /// on the nyc-311 date columns (#299). At cascade depth 0 there is no competition, so fall back
    /// to the terminal raw-primitive layout.
    ///
    /// @param dtype the Utf8/Binary type being encoded
    /// @param data  the string values
    /// @param ctx   encoding context supplying the arena and cascade depth
    /// @return a cascade step with the two offset children left open, or a terminal step at depth 0
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (ctx.allowedCascading() == 0) {
            return CascadeStep.terminal(encode(dtype, data, ctx));
        }
        Fsst c = compress((String[]) data, ctx.arena());
        Object uncompLens = typedUnsigned(c.uncompLenPType(), c.uncompLens());
        Object codesOffsets = typedUnsigned(c.codesOffPType(), c.codesOffsets());
        EncodeNode partialRoot = new EncodeNode(
                EncodingId.VORTEX_FSST,
                MemorySegment.ofArray(c.metaBytes()),
                new EncodeNode[]{null, null},
                new int[]{0, 1, 2});
        return new CascadeStep(partialRoot,
                List.of(c.symBuf(), c.symLenBuf(), c.compBuf()),
                List.of(new ChildSlot(new DType.Primitive(c.uncompLenPType(), false), uncompLens, 0),
                        new ChildSlot(new DType.Primitive(c.codesOffPType(), false), codesOffsets, 1)),
                null, null, true);
    }

    /// The FSST-specific product of compression: the symbol-table buffers, the wire code stream, the
    /// [ProtoFSSTMetadata] bytes, and the per-row uncompressed lengths / cumulative code offsets as
    /// plain `int[]` (the two offset children, in narrowest-unsigned ptypes).
    @SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
    private record Fsst(
            MemorySegment symBuf, MemorySegment symLenBuf, MemorySegment compBuf,
            byte[] metaBytes, int[] uncompLens, int[] codesOffsets,
            PType uncompLenPType, PType codesOffPType, int n) {
    }

    private static Fsst compress(String[] strings, Arena arena) {
        int n = strings.length;

        byte[][] byteArrays = new byte[n][];
        long totalInput = 0;
        int maxUncompLen = 0;
        for (int i = 0; i < n; i++) {
            byteArrays[i] = strings[i].getBytes(StandardCharsets.UTF_8);
            totalInput += byteArrays[i].length;
            maxUncompLen = Math.max(maxUncompLen, byteArrays[i].length);
        }

        Compressor compressor = new CompressorBuilder().seed(TRAINING_SAMPLE_SEED).train(byteArrays);
        int numSymbols = compressor.symbolCount();

        // Wire order: the wire lists symbols in length order (multi-byte length-ascending, then
        // length-1 last), whereas the compressor numbers its codes gain-descending. wireOrder[i] is
        // the compressor's (internal) code that belongs at wire position i; internalToWire is its
        // inverse, mapping every internal code emitted by compress() to its wire code. Both the
        // symbol-table buffers and the code stream must be expressed in wire codes so the file is
        // self-consistent.
        int[] wireOrder = compressor.codesSortedByLength();
        int[] internalToWire = new int[numSymbols];
        for (int i = 0; i < numSymbols; i++) {
            internalToWire[wireOrder[i]] = i;
        }

        MemorySegment symBuf = arena.allocate(Math.max(numSymbols * 8L, 1), 8);
        MemorySegment symLenBuf = arena.allocate(Math.max(numSymbols, 1));
        for (int i = 0; i < numSymbols; i++) {
            int internalCode = wireOrder[i];
            symBuf.setAtIndex(VortexFormat.LE_LONG, i, compressor.packedSymbol(internalCode));
            symLenBuf.set(ValueLayout.JAVA_BYTE, i, (byte) compressor.symbolLength(internalCode));
        }

        // Compress every row back-to-back into one shared scratch buffer (a per-row scratch plus a
        // per-row exact-size copy costs two heap allocations and an extra copy per row — millions
        // per chunk), then remap the code bytes (never the literal byte following an escape) to
        // wire codes in a single pass over the whole stream. Worst case each input byte escapes to
        // 2 output bytes, so 2 * totalInput bounds the entire stream.
        byte[] scratch = new byte[Math.toIntExact(2 * totalInput)];
        int[] rowEnds = new int[n];
        int totalCompressed = 0;
        for (int i = 0; i < n; i++) {
            byte[] row = byteArrays[i];
            totalCompressed = (int) compressor.compress(row, 0, row.length, scratch, totalCompressed);
            rowEnds[i] = totalCompressed;
        }
        remapCodesToWire(scratch, totalCompressed, internalToWire);

        MemorySegment compBuf = arena.allocate(Math.max(totalCompressed, 1));
        MemorySegment.copy(scratch, 0, compBuf, ValueLayout.JAVA_BYTE, 0, totalCompressed);

        // Narrowest ptype that fits every value: row lengths and cumulative offsets are
        // typically far below the 4-byte ceiling this always used to pay (e.g. a 6-byte string
        // column needs only U8 lengths, not I32), and the wire format carries the chosen ptype
        // per FSSTMetadata specifically so a reader never has to guess.
        PType uncompLenPType = PType.narrowestUnsigned(maxUncompLen);
        PType codesOffPType = PType.narrowestUnsigned(totalCompressed);

        int[] uncompLens = new int[n];
        for (int i = 0; i < n; i++) {
            uncompLens[i] = byteArrays[i].length;
        }
        int[] codesOffsets = new int[n + 1];
        for (int i = 0; i < n; i++) {
            codesOffsets[i + 1] = rowEnds[i];
        }

        byte[] metaBytes = new ProtoFSSTMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(uncompLenPType.ordinal()),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codesOffPType.ordinal())
        ).encode();

        return new Fsst(symBuf, symLenBuf, compBuf, metaBytes, uncompLens, codesOffsets,
                uncompLenPType, codesOffPType, n);
    }

    /// Copies unsigned values into the narrowest Java array matching `ptype` (U8→`byte[]`,
    /// U16→`short[]`, else `int[]`) so a [ChildSlot] can hand them to the cascade's primitive codecs.
    ///
    /// @param ptype the child's primitive type
    /// @param vals  the values (already within `ptype`'s unsigned range)
    /// @return a `byte[]`, `short[]`, or `int[]` holding `vals`
    private static Object typedUnsigned(PType ptype, int[] vals) {
        return switch (ptype) {
            case U8, I8 -> {
                byte[] a = new byte[vals.length];
                for (int i = 0; i < vals.length; i++) {
                    a[i] = (byte) vals[i];
                }
                yield a;
            }
            case U16, I16 -> {
                short[] a = new short[vals.length];
                for (int i = 0; i < vals.length; i++) {
                    a[i] = (short) vals[i];
                }
                yield a;
            }
            default -> vals;
        };
    }

    /// Remaps every code byte in `stream[0..length)` from the compressor's internal (gain-descending)
    /// code to its wire (length-sorted) code, in place. The escape byte `0xFF` and the single literal
    /// byte that follows it are copied through unchanged — the literal is raw data, not a code.
    ///
    /// @param stream the freshly compressed code stream, mutated in place
    /// @param length the number of valid bytes in `stream`
    /// @param internalToWire maps an internal code to its wire code, indexed by internal code
    private static void remapCodesToWire(byte[] stream, int length, int[] internalToWire) {
        int j = 0;
        while (j < length) {
            int code = stream[j] & 0xFF;
            if (code == ESCAPE) {
                j += 2;
            } else {
                stream[j] = (byte) internalToWire[code];
                j++;
            }
        }
    }
}
