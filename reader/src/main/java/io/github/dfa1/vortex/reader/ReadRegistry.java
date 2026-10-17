package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.core.model.Edition;
import io.github.dfa1.vortex.core.model.Editions;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
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
import io.github.dfa1.vortex.reader.decode.OnPairEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ParquetVariantEncodingDecoder;
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
import io.github.dfa1.vortex.reader.decode.UnionEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.VariantEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZigZagEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZstdBuffersEncodingDecoder;
import io.github.dfa1.vortex.reader.decode.ZstdEncodingDecoder;
import io.github.dfa1.vortex.reader.extension.DateExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.ExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimeExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.TimestampExtensionDecoder;
import io.github.dfa1.vortex.reader.extension.UuidExtensionDecoder;

import java.lang.foreign.MemorySegment;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/// Read-side registry: maps [EncodingId] to [EncodingDecoder] implementations,
/// and [ExtensionId] to [ExtensionDecoder] implementations.
///
/// Instances are immutable after construction. Build one via [#builder()] or
/// via the [#loadAll()] and [#empty()] convenience factories.
public final class ReadRegistry {

    // Keyed by the typed id — ArrayNode already carries a parsed EncodingId, so dispatch never
    // round-trips through the wire string ("strings at the boundary, types inside"). Ordered by
    // that string (EncodingId is not Comparable; a Custom key must not throw); order is not
    // load-bearing, but stable ordering keeps the registries consistent.
    private final Map<EncodingId, EncodingDecoder> decoders;
    private final Map<ExtensionId, ExtensionDecoder<?>> extensions;
    private final boolean allowUnknown;

    private ReadRegistry(Map<EncodingId, EncodingDecoder> decoders,
            Map<ExtensionId, ExtensionDecoder<?>> extensions, boolean allowUnknown) {
        var sorted = new TreeMap<EncodingId, EncodingDecoder>(Comparator.comparing(EncodingId::id));
        sorted.putAll(decoders);
        this.decoders = Collections.unmodifiableMap(sorted);
        this.extensions = Map.copyOf(extensions);
        this.allowUnknown = allowUnknown;
    }

    /// Loads all built-in [EncodingDecoder] and [ExtensionDecoder] implementations.
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

    /// Returns whether passthrough decode for unknown encoding and extension ids is enabled.
    ///
    /// @return `true` if unknown encodings are silently wrapped as
    ///         [io.github.dfa1.vortex.reader.array.UnknownArray] and unknown extension ids read as
    ///         their storage array
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

    /// Returns the registered extension decoder for `extensionId`, or `null` if not registered.
    /// A file with an unregistered extension id opens only with [Builder#allowUnknown()], and the
    /// column then reads as its storage array (Rust's foreign extension).
    ///
    /// @param extensionId the extension id to look up
    /// @return the registered [ExtensionDecoder], or `null`
    public ExtensionDecoder<?> lookup(ExtensionId extensionId) {
        return extensions.get(extensionId);
    }

    /// Checks every extension dtype in `dtype`, nested ones included, as Rust does when it
    /// deserializes one: a registered decoder validates it; an unregistered id is refused unless
    /// [Builder#allowUnknown()] is set, when it reads as its storage (Rust's `ForeignExtDType`).
    /// The file parser has already checked every extension id.
    ///
    /// @param dtype a file's schema, or `null` when the file carries no dtype blob
    /// @throws VortexException if a registered decoder rejects its dtype, or an extension id has no
    ///         decoder and unknown ids are not allowed
    void validateExtensions(DType dtype) {
        switch (dtype) {
            case null -> {
                // a file without a dtype blob parses with no schema: nothing to check
            }
            case DType.Extension ext -> {
                ExtensionDecoder<?> decoder = extensions.get(ExtensionId.parse(ext.extensionId()));
                if (decoder != null) {
                    validate(decoder, ext);
                } else if (!allowUnknown) {
                    throw new VortexException("no extension decoder registered for " + ext.extensionId()
                            + "; register one or enable ReadRegistry.Builder#allowUnknown()");
                }
                validateExtensions(ext.storageDType());
            }
            case DType.Struct struct -> struct.fieldTypes().forEach(this::validateExtensions);
            case DType.List list -> validateExtensions(list.elementType());
            case DType.FixedSizeList list -> validateExtensions(list.elementType());
            case DType.Map map -> {
                validateExtensions(map.keyType());
                validateExtensions(map.valueType());
            }
            case DType.Union union -> union.variantTypes().forEach(this::validateExtensions);
            case DType.Null _, DType.Bool _, DType.Primitive _, DType.Decimal _, DType.Utf8 _,
                 DType.Binary _, DType.Variant _ -> {
                // no nested dtype
            }
        }
    }

    /// Runs a decoder's [ExtensionDecoder#validate(DType.Extension)] on a dtype from an untrusted
    /// file. A third-party check may trip over malformed metadata with any runtime exception;
    /// reading a file must only ever fail with [VortexException].
    private static void validate(ExtensionDecoder<?> decoder, DType.Extension ext) {
        try {
            decoder.validate(ext);
        } catch (VortexException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VortexException("extension " + ext.extensionId() + " rejected its dtype", e);
        }
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
    /// experimental") instead of a bare id string. Issue #301.
    ///
    /// @param id the unrecognized encoding id
    /// @return the message body (the caller's [VortexException] constructor prefixes the id itself)
    private static String noDecoderMessage(EncodingId id) {
        Optional<Edition> owning = Editions.owningEdition(id);
        if (owning.isEmpty()) {
            return "no decoder registered; unknown to all editions (custom/experimental encoding)"
                    + " — register a decoder or enable ReadRegistry.Builder#allowUnknown()";
        }
        return "no decoder registered (joined edition " + owning.get().id()
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
        private final Map<ExtensionId, ExtensionDecoder<?>> extensions = new HashMap<>();
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

        /// Registers an extension decoder.
        ///
        /// @param extension the [ExtensionDecoder] to register
        /// @return this builder, for chaining
        /// @throws VortexException if an extension with the same id is already registered
        public Builder register(ExtensionDecoder<?> extension) {
            ExtensionDecoder<?> old = extensions.put(extension.extensionId(), extension);
            if (old != null) {
                throw new VortexException("extension %s already registered".formatted(extension.extensionId()));
            }
            return this;
        }

        /// Registers all built-in [EncodingDecoder] and [ExtensionDecoder] implementations.
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a built-in entry collides with one already registered
        public Builder registerDefaults() {
            register(DateExtensionDecoder.INSTANCE)
                    .register(TimeExtensionDecoder.INSTANCE)
                    .register(TimestampExtensionDecoder.INSTANCE)
                    .register(UuidExtensionDecoder.INSTANCE);
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
                    .register(new OnPairEncodingDecoder())
                    .register(new ParquetVariantEncodingDecoder())
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
                    .register(new UnionEncodingDecoder())
                    .register(new VarBinViewEncodingDecoder())
                    .register(new ZigZagEncodingDecoder())
                    .register(new ZstdBuffersEncodingDecoder())
                    .register(new ZstdEncodingDecoder());
        }

        /// Enable passthrough decode for unknown encoding and extension ids.
        ///
        /// Default is strict: unknown ids throw [VortexException]. When enabled, unknown
        /// encoding nodes are wrapped as [io.github.dfa1.vortex.reader.array.UnknownArray], and
        /// an extension id with no registered [ExtensionDecoder] reads as its storage array.
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
            return new ReadRegistry(decoders, extensions, allowUnknown);
        }
    }
}
