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
import java.util.List;

/// Write-only encoder for `vortex.fsst`.
///
/// This class is a thin wire adapter over the standalone `vortex-fsst` module (issue #287): the
/// FSST compression algorithm — symbol-table training and greedy longest-match compression — lives
/// entirely in [CompressorBuilder]/[Compressor]. This adapter normalizes the input (Utf8 `String[]`
/// UTF-8 encoded, Binary `byte[][]` passed through — [VarBinBytes]) to raw row bytes, drives
/// training, compresses each row, and lays the result out in the `vortex.fsst` wire format (symbol
/// table buffers, remapped code stream, per-row uncompressed lengths and code offsets, plus the
/// [ProtoFSSTMetadata] describing the two offset ptypes).
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
        return toEncodeResult(compress(data, arena), arena);
    }

    /// Lays out a compression product in the terminal `vortex.fsst` wire format: the per-row
    /// length and cumulative-offset children as raw primitive segments (buffers 3 and 4). The
    /// cascading path ([#encodeCascade]) instead exposes them as open child slots so they can be
    /// bitpacked/constant-folded.
    ///
    /// Package-private so tests can drive it directly off a [#compressPerRow] result — the
    /// fallback path only [VarBinBytes#toContiguous] can trigger, at a >2 GB corpus no unit test
    /// can afford to allocate.
    ///
    /// @param c     the compression product to lay out
    /// @param arena arena backing the length/offset buffers
    /// @return the terminal `vortex.fsst` encode result
    static EncodeResult toEncodeResult(Fsst c, Arena arena) {
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
                List.of(c.symBuf(), c.symLenBuf(), c.compBuf(), uncompLenBuf, codesOffBuf), null, null);
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
    /// @param data  the Utf8 (`String[]`) or Binary (`byte[][]`) values
    /// @param ctx   encoding context supplying the arena and cascade depth
    /// @return a cascade step with the two offset children left open, or a terminal step at depth 0
    @Override
    public CascadeStep encodeCascade(DType dtype, Object data, EncodeContext ctx) {
        if (ctx.allowedCascading() == 0) {
            return CascadeStep.terminal(encode(dtype, data, ctx));
        }
        Fsst c = compress(data, ctx.arena());
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
    record Fsst(
            MemorySegment symBuf, MemorySegment symLenBuf, MemorySegment compBuf,
            byte[] metaBytes, int[] uncompLens, int[] codesOffsets,
            PType uncompLenPType, PType codesOffPType, int n) {
    }

    private static Fsst compress(Object data, Arena arena) {
        // Contiguous rows (one shared byte[] plus offsets) rather than one byte[] per row: a 50k-row
        // chunk otherwise holds 50k live objects for the whole encode, which the collector must
        // trace, and that was profiled as this write path's single largest allocation source. Above
        // 2 GB of row bytes the rows cannot share one array, and toContiguous returns null — fall
        // back to the per-row shape, which has no such ceiling.
        VarBinBytes.Rows rows = VarBinBytes.toContiguous(data);
        if (rows == null) {
            return compressPerRow(VarBinBytes.toByteArrays(data), arena);
        }
        byte[] rowBytes = rows.bytes();
        int[] rowOffsets = rows.offsets();
        int n = rows.count();

        long totalInput = rowOffsets[n];
        int maxUncompLen = 0;
        int[] uncompLens = new int[n];
        for (int i = 0; i < n; i++) {
            uncompLens[i] = rowOffsets[i + 1] - rowOffsets[i];
            maxUncompLen = Math.max(maxUncompLen, uncompLens[i]);
        }

        Compressor trained = new CompressorBuilder().seed(TRAINING_SAMPLE_SEED)
                                     .train(rowBytes, rowOffsets, n);

        // byte[]-input overload over the shared row buffer: the intrinsified VarHandle word load
        // is faster than wrapping each row in a MemorySegment first, and the row is addressed as
        // a range of the contiguous array rather than an array of its own.
        return finishCompress(trained, n, totalInput, maxUncompLen, uncompLens, arena,
                (compressor, i, scratch, destOffset) -> compressor.compress(
                        rowBytes, rowOffsets[i], rowOffsets[i + 1], scratch, destOffset));
    }

    /// Per-row fallback for corpora whose bytes exceed a single `byte[]` (over 2 GB in one chunk),
    /// where [VarBinBytes#toContiguous] cannot produce a shared buffer. Identical in output to the
    /// contiguous path — same sample, same table, same code stream — just addressing each row as
    /// its own array. Package-private (rather than `private`) so a test can drive this path
    /// directly — the public path only reaches it past a >2 GB corpus.
    static Fsst compressPerRow(byte[][] byteArrays, Arena arena) {
        int n = byteArrays.length;

        long totalInput = 0;
        int maxUncompLen = 0;
        int[] uncompLens = new int[n];
        for (int i = 0; i < n; i++) {
            uncompLens[i] = byteArrays[i].length;
            totalInput += uncompLens[i];
            maxUncompLen = Math.max(maxUncompLen, uncompLens[i]);
        }

        Compressor trained = new CompressorBuilder().seed(TRAINING_SAMPLE_SEED).train(byteArrays);

        return finishCompress(trained, n, totalInput, maxUncompLen, uncompLens, arena,
                (compressor, i, scratch, destOffset) -> {
                    byte[] row = byteArrays[i];
                    return compressor.compress(row, 0, row.length, scratch, destOffset);
                });
    }

    /// Shared tail of `compress`/`compressPerRow`: renumbers the trained symbol table to wire
    /// order, lays out the symbol buffers, then drives `rowCompressor` over every row into one
    /// arena scratch segment. Identical for both row-storage shapes — only how a row's bytes are
    /// located differs, which `rowCompressor` encapsulates.
    ///
    /// @param trained       the compressor after training, not yet renumbered to wire order
    /// @param n             the row count
    /// @param totalInput    total input bytes across all rows, bounding the compressed scratch size
    /// @param maxUncompLen  the longest row's uncompressed length, for choosing `uncompLenPType`
    /// @param uncompLens    each row's uncompressed length, already computed by the caller
    /// @param arena         arena backing the symbol/compressed buffers
    /// @param rowCompressor compresses row `i` into `scratch` starting at `destOffset`, returning
    ///                      the new total compressed length
    /// @return the FSST compression product ready for wire layout
    private static Fsst finishCompress(Compressor trained, int n, long totalInput, int maxUncompLen,
            int[] uncompLens, Arena arena, RowCompressor rowCompressor) {
        int numSymbols = trained.symbolCount();

        // Wire order: the wire lists symbols in length order (multi-byte length-ascending, then
        // length-1 last), whereas training numbers its codes gain-descending. wireOrder[i] is the
        // trained (internal) code that belongs at wire position i.
        int[] wireOrder = trained.codesSortedByLength();

        MemorySegment symBuf = arena.allocate(Math.max(numSymbols * 8L, 1), 8);
        MemorySegment symLenBuf = arena.allocate(Math.max(numSymbols, 1));
        for (int i = 0; i < numSymbols; i++) {
            int internalCode = wireOrder[i];
            symBuf.setAtIndex(VortexFormat.LE_LONG, i, trained.packedSymbol(internalCode));
            symLenBuf.set(ValueLayout.JAVA_BYTE, i, (byte) trained.symbolLength(internalCode));
        }

        // Renumber the trained compressor's own codes to wire order (reusing its matcher's backing
        // arrays) so compression emits wire codes directly — no second pass remapping every emitted
        // code byte afterward. trained itself must not be read again past this point.
        Compressor compressor = trained.withCodeOrder(wireOrder);

        // Compress every row back-to-back directly into an arena-allocated scratch segment: a heap
        // scratch plus a copy into the arena costs a full extra allocation and copy of the whole
        // stream, the CLAUDE.md allocation rule this violated. Worst case each input byte escapes
        // to 2 output bytes, so 2 * totalInput bounds the entire stream.
        MemorySegment scratch = arena.allocate(Math.max(2 * totalInput, 1));
        // Written directly at wire position: codesOffsets[0] stays its default 0, and each row's
        // end lands at codesOffsets[i + 1] as it compresses, so no separate rowEnds array (and no
        // copy into a codesOffsets array afterward) is needed.
        int[] codesOffsets = new int[n + 1];
        long totalCompressed = 0;
        for (int i = 0; i < n; i++) {
            totalCompressed = rowCompressor.compress(compressor, i, scratch, totalCompressed);
            codesOffsets[i + 1] = Math.toIntExact(totalCompressed);
        }

        // A slice, not a copy: scratch is already arena-owned, so trimming the worst-case 2x
        // allocation down to the real compressed length needs no further copy.
        MemorySegment compBuf = scratch.asSlice(0, totalCompressed).asReadOnly();

        // Narrowest ptype that fits every value: row lengths and cumulative offsets are
        // typically far below the 4-byte ceiling this always used to pay (e.g. a 6-byte string
        // column needs only U8 lengths, not I32), and the wire format carries the chosen ptype
        // per FSSTMetadata specifically so a reader never has to guess.
        PType uncompLenPType = PType.narrowestUnsigned(maxUncompLen);
        PType codesOffPType = PType.narrowestUnsigned(totalCompressed);

        byte[] metaBytes = new ProtoFSSTMetadata(
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(uncompLenPType.ordinal()),
                io.github.dfa1.vortex.core.proto.ProtoPType.fromValue(codesOffPType.ordinal())
        ).encode();

        return new Fsst(symBuf, symLenBuf, compBuf, metaBytes, uncompLens, codesOffsets,
                uncompLenPType, codesOffPType, n);
    }

    /// Compresses one row into `scratch` starting at `destOffset`, returning the new total
    /// compressed length — abstracts over how `compress`/`compressPerRow` locate a row's bytes.
    @FunctionalInterface
    private interface RowCompressor {
        long compress(Compressor compressor, int rowIndex, MemorySegment scratch, long destOffset);
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
}
