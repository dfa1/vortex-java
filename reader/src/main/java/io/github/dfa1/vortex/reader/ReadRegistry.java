package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.ArraySegments;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.reader.decode.ArrayNode;
import io.github.dfa1.vortex.reader.decode.DecodeContext;
import io.github.dfa1.vortex.reader.decode.EncodingDecoder;
import io.github.dfa1.vortex.reader.decode.KnownArrayNode;
import io.github.dfa1.vortex.reader.decode.UnknownArrayNode;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/// Read-side registry: maps [EncodingId] to [EncodingDecoder] implementations.
///
/// Instances are immutable after construction. Build one via [#builder()] or
/// via the [#loadAll()] and [#empty()] convenience factories.
public final class ReadRegistry {

    private final Map<EncodingId, EncodingDecoder> decoders;
    private final boolean allowUnknown;

    private ReadRegistry(Map<EncodingId, EncodingDecoder> decoders, boolean allowUnknown) {
        this.decoders = Map.copyOf(decoders);
        this.allowUnknown = allowUnknown;
    }

    /// Loads all service-discovered [EncodingDecoder] implementations.
    ///
    /// @return an immutable [ReadRegistry] populated with all service-loaded decoders
    public static ReadRegistry loadAll() {
        return builder().registerServiceLoaded().build();
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
        EncodingDecoder decoder = switch (node) {
            case KnownArrayNode k -> decoders.get(k.encodingId());
            case UnknownArrayNode _ -> null;
        };
        if (decoder != null) {
            return decoder.decode(ctx);
        }
        if (allowUnknown) {
            return decodeUnknown(ctx, node);
        }
        String id = switch (node) {
            case KnownArrayNode k -> k.encodingId().id();
            case UnknownArrayNode u -> u.rawEncodingId();
        };
        throw new VortexException("no decoder registered for " + id);
    }

    /// Decodes the array described by `ctx` and returns its primary backing segment.
    ///
    /// @param ctx the decode context
    /// @return the primary [MemorySegment] of the decoded array
    public MemorySegment decodeAsSegment(DecodeContext ctx) {
        ArrayNode node = ctx.node();
        EncodingDecoder decoder = switch (node) {
            case KnownArrayNode k -> decoders.get(k.encodingId());
            case UnknownArrayNode _ -> null;
        };
        if (decoder != null) {
            return ArraySegments.of(decoder.decode(ctx), ctx.arena());
        }
        String id = switch (node) {
            case KnownArrayNode k -> k.encodingId().id();
            case UnknownArrayNode u -> u.rawEncodingId();
        };
        throw new VortexException("no decoder registered for " + id + " (or encoding has no primary segment)");
    }

    private static UnknownArray decodeUnknown(DecodeContext ctx, ArrayNode node) {
        String rawId = switch (node) {
            case KnownArrayNode k -> k.encodingId().id();
            case UnknownArrayNode u -> u.rawEncodingId();
        };
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
                rawId, ctx.dtype(), ctx.rowCount(),
                node.metadata(), bufs, children);
    }

    /// Builder for [ReadRegistry].
    ///
    /// Not thread-safe. Build once, use everywhere — the produced [ReadRegistry] is immutable.
    public static final class Builder {

        private final Map<EncodingId, EncodingDecoder> decoders = new HashMap<>();
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

        /// Registers every [EncodingDecoder] discovered via [ServiceLoader].
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a service-loaded entry collides with one already registered
        public Builder registerServiceLoaded() {
            for (EncodingDecoder decoder : ServiceLoader.load(EncodingDecoder.class)) {
                register(decoder);
            }
            return this;
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
