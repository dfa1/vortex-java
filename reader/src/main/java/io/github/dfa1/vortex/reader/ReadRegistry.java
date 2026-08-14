package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.EditionFamily;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.AlpRdEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.BitpackedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.BoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ByteBoolEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ChunkedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ConstantEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DateTimePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DecimalBytePartsEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DecimalEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.DeltaEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.DictEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ExtEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.FixedSizeListEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.FrameOfReferenceEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.FsstEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ListEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ListViewEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.MapEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.MaskedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.NullEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PatchedEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PcoEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.RleEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.RunEndEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.SequenceEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.SparseEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.StructEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VarBinEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VarBinViewEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VariantEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZstdEncodingDecoder;

import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/// Read-side registry: maps [EncodingId] to [EncodingDecoder] implementations.
///
/// Instances are immutable after construction. Build one via [#builder()] or
/// via the [#loadAll()] and [#empty()] convenience factories.
public final class ReadRegistry {

    // Keyed by the typed id — ArrayNode already carries a parsed EncodingId, so dispatch never
    // round-trips through the wire string ("strings at the boundary, types inside"). Ordered by
    // that string (EncodingId is not Comparable; a Custom key must not throw); order is not
    // load-bearing, but stable ordering keeps the registries consistent.
    private final Map<EncodingId, EncodingDecoder> decoders;
    private final boolean allowUnknown;

    private ReadRegistry(Map<EncodingId, EncodingDecoder> decoders, boolean allowUnknown) {
        var sorted = new TreeMap<EncodingId, EncodingDecoder>(Comparator.comparing(EncodingId::id));
        sorted.putAll(decoders);
        this.decoders = Collections.unmodifiableMap(sorted);
        this.allowUnknown = allowUnknown;
    }

    /// Loads all built-in [EncodingDecoder] implementations.
    ///
    /// @return an immutable [ReadRegistry] populated with all built-in decoders
    public static ReadRegistry loadAll() {
        return builder().registerDefaults().build();
    }

    /// Creates an empty registry with no decoders registered.
    ///
    /// @return a new empty immutable [ReadRegistry]
    public static ReadRegistry empty() {
        return builder().build();
    }

    /// Returns a new [Builder].
    ///
    /// @return a fresh builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns an unmodifiable view of the registered decoders, keyed by encoding id.
    ///
    /// @return immutable decoder map
    public Map<EncodingId, EncodingDecoder> decoderMap() {
        return decoders;
    }

    /// Returns whether passthrough decode for unknown encoding ids is enabled.
    ///
    /// @return `true` if unknown encodings are silently wrapped as
    ///         [io.github.dfa1.vortex.reader.array.UnknownArray]
    public boolean isAllowUnknown() {
        return allowUnknown;
    }

    /// Returns `true` if a decoder is registered for the given id.
    ///
    /// @param encodingId the encoding id to query
    /// @return `true` if a decoder is registered
    public boolean hasDecoder(EncodingId encodingId) {
        return decoders.containsKey(encodingId);
    }

    /// Decodes the array described by `ctx`.
    ///
    /// @param ctx the decode context
    /// @return the decoded [Array]
    public Array decode(DecodeContext ctx) {
        ArrayNode node = ctx.node();
        EncodingDecoder decoder = decoders.get(node.encodingId());
        if (decoder != null) {
            return decoder.decode(ctx);
        }
        if (allowUnknown) {
            return decodeUnknown(ctx, node);
        }
        throw new VortexException(node.encodingId(), noDecoderMessage(node.encodingId()));
    }

    /// Decodes the array described by `ctx` and returns its primary backing segment.
    ///
    /// @param ctx the decode context
    /// @return the primary [MemorySegment] of the decoded array
    public MemorySegment decodeAsSegment(DecodeContext ctx) {
        ArrayNode node = ctx.node();
        EncodingDecoder decoder = decoders.get(node.encodingId());
        if (decoder != null) {
            return decoder.decode(ctx).materialize(ctx.arena());
        }
        throw new VortexException(node.encodingId(),
                noDecoderMessage(node.encodingId()) + " (or encoding has no primary segment)");
    }

    /// Builds the "no decoder registered" message, enriched with which [Edition] `id` belongs to
    /// when known — an actionable pointer ("this file is newer than your build, or the encoding is
    /// unstable/experimental") instead of a bare id string. Issue #301.
    ///
    /// @param id the unrecognized encoding id
    /// @return the message body (the caller's [VortexException] constructor prefixes the id itself)
    private static String noDecoderMessage(EncodingId id) {
        Optional<Edition> owning = Editions.owningEdition(id);
        if (owning.isEmpty()) {
            return "no decoder registered; unknown to all editions (custom/experimental encoding)"
                    + " — register a decoder or enable ReadRegistry.Builder#allowUnknown()";
        }
        Edition edition = owning.get();
        String draftNote = edition.id().family() == EditionFamily.UNSTABLE
                ? ", unstable — no compatibility guarantee" : "";
        return "no decoder registered (joined edition " + edition.id() + draftNote
                + "); register a decoder or enable ReadRegistry.Builder#allowUnknown()";
    }

    private static UnknownArray decodeUnknown(DecodeContext ctx, ArrayNode node) {
        MemorySegment[] bufs = new MemorySegment[node.bufferIndices().length];
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = ctx.buffer(i);
        }
        Array[] children = new Array[node.children().length];
        for (int i = 0; i < children.length; i++) {
            ArrayNode childNode = node.children()[i];
            DecodeContext childCtx = new DecodeContext(
                    childNode, ctx.dtype(), ctx.rowCount(),
                    ctx.segmentBuffers(), ctx.registry(), ctx.arena());
            children[i] = decodeUnknown(childCtx, childNode);
        }
        return new UnknownArray(
                node.encodingId(), ctx.dtype(), ctx.rowCount(),
                node.metadata(), bufs, children);
    }

    /// Builder for [ReadRegistry].
    ///
    /// Not thread-safe. Build once, use everywhere — the produced [ReadRegistry] is immutable.
    public static final class Builder {

        private final Map<EncodingId, EncodingDecoder> decoders = new TreeMap<>(Comparator.comparing(EncodingId::id));
        private boolean allowUnknown = false;

        private Builder() {
        }

        /// Registers a decoder.
        ///
        /// @param decoder the [EncodingDecoder] to register
        /// @return this builder, for chaining
        /// @throws VortexException if a decoder for the same id is already registered
        public Builder register(EncodingDecoder decoder) {
            EncodingDecoder old = decoders.put(decoder.encodingId(), decoder);
            if (old != null) {
                throw new VortexException("decoder %s already registered".formatted(decoder.encodingId()));
            }
            return this;
        }

        /// Registers all built-in [EncodingDecoder] implementations.
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a built-in decoder collides with one already registered
        public Builder registerDefaults() {
            return register(new AlpEncodingDecoder())
                    .register(new AlpRdEncodingDecoder())
                    .register(new BitpackedEncodingDecoder())
                    .register(new BoolEncodingDecoder())
                    .register(new ByteBoolEncodingDecoder())
                    .register(new ChunkedEncodingDecoder())
                    .register(new ConstantEncodingDecoder())
                    .register(new DateTimePartsEncodingDecoder())
                    .register(new DecimalBytePartsEncodingDecoder())
                    .register(new DecimalEncodingDecoder())
                    .register(new DeltaEncodingDecoder())
                    .register(new DictEncodingDecoder())
                    .register(new ExtEncodingDecoder())
                    .register(new FixedSizeListEncodingDecoder())
                    .register(new FrameOfReferenceEncodingDecoder())
                    .register(new FsstEncodingDecoder())
                    .register(new ListEncodingDecoder())
                    .register(new ListViewEncodingDecoder())
                    .register(new MapEncodingDecoder())
                    .register(new MaskedEncodingDecoder())
                    .register(new NullEncodingDecoder())
                    .register(new PatchedEncodingDecoder())
                    .register(new PcoEncodingDecoder())
                    .register(new PrimitiveEncodingDecoder())
                    .register(new RleEncodingDecoder())
                    .register(new RunEndEncodingDecoder())
                    .register(new SequenceEncodingDecoder())
                    .register(new SparseEncodingDecoder())
                    .register(new StructEncodingDecoder())
                    .register(new VarBinEncodingDecoder())
                    .register(new VariantEncodingDecoder())
                    .register(new VarBinViewEncodingDecoder())
                    .register(new ZigZagEncodingDecoder())
                    .register(new ZstdEncodingDecoder());
        }

        /// Enable passthrough decode for unknown encoding ids.
        ///
        /// Default is strict: unknown ids throw [VortexException]. When enabled, unknown
        /// nodes are wrapped as [io.github.dfa1.vortex.reader.array.UnknownArray].
        /// Mirrors Rust `VortexSession::allow_unknown()`.
        ///
        /// @return this builder, for chaining
        public Builder allowUnknown() {
            this.allowUnknown = true;
            return this;
        }

        /// Builds an immutable [ReadRegistry].
        ///
        /// @return the immutable registry
        public ReadRegistry build() {
            return new ReadRegistry(decoders, allowUnknown);
        }
    }
}
