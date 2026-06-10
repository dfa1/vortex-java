package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.UnknownArray;
import io.github.dfa1.vortex.extension.Extension;
import io.github.dfa1.vortex.extension.ExtensionId;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/// Registry mapping wire identifiers to their library implementations:
/// [Encoding] keyed by [EncodingId], and [Extension] keyed by [ExtensionId].
///
/// Instances are immutable after construction. Build one via [#builder()] or via the
/// [#loadAll()], [#empty()], [#of(List)] convenience factories.
public final class Registry {

    private final Map<EncodingId, Encoding> encodings;
    private final Map<ExtensionId, Extension> extensions;
    private final boolean allowUnknown;

    private Registry(
            Map<EncodingId, Encoding> encodings,
            Map<ExtensionId, Extension> extensions,
            boolean allowUnknown
    ) {
        this.encodings = Map.copyOf(encodings);
        this.extensions = Map.copyOf(extensions);
        this.allowUnknown = allowUnknown;
    }

    /// Returns a new {@link Builder}.
    ///
    /// @return a fresh builder
    public static Builder builder() {
        return new Builder();
    }

    /// Load all [Encoding]s registered via `ServiceLoader`.
    ///
    /// @return an immutable {@link Registry} populated with all service-loaded encodings
    public static Registry loadAll() {
        return builder().registerServiceLoaded().build();
    }

    /// Creates an empty registry with no encodings registered.
    ///
    /// @return a new empty immutable {@link Registry}
    public static Registry empty() {
        return builder().build();
    }

    /// Creates a registry populated with the given encodings.
    ///
    /// @param encodings the encodings to register
    /// @return an immutable {@link Registry} populated with the given encodings
    public static Registry of(List<Encoding> encodings) {
        var b = builder();
        for (Encoding e : encodings) {
            b.register(e);
        }
        return b.build();
    }

    /// Recursively wrap a node and its children as [UnknownArray]. Children of an unknown
    /// parent are always wrapped unknown regardless of whether their own id is recognised —
    /// matches Rust `decode_foreign` in `vortex-array/src/serde.rs`.
    private static UnknownArray decodeUnknown(DecodeContext ctx, ArrayNode node) {
        String rawId = switch (node) {
            case KnownArrayNode k -> k.encodingId().id();
            case UnknownArrayNode u -> u.rawEncodingId();
        };
        MemorySegment[] bufs = new MemorySegment[node.bufferIndices().length];
        for (int i = 0; i < bufs.length; i++) {
            bufs[i] = ctx.buffer(i).unwrapForSubParser("registry");
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

    /// Returns whether passthrough decode for unknown encoding ids is enabled.
    ///
    /// @return {@code true} if unknown encodings are silently wrapped as {@link io.github.dfa1.vortex.core.array.UnknownArray}
    public boolean isAllowUnknown() {
        return allowUnknown;
    }

    /// Returns {@code true} if an encoding is registered for the given id.
    ///
    /// @param encodingId the encoding id to query
    /// @return {@code true} if an {@link Encoding} is registered for {@code encodingId}
    public boolean hasEncoding(EncodingId encodingId) {
        return encodings.containsKey(encodingId);
    }

    /// Returns the registered encoding for {@code encodingId}, or {@code null} if not registered.
    ///
    /// @param encodingId the encoding id to look up
    /// @return the registered {@link Encoding}, or {@code null}
    public Encoding lookup(EncodingId encodingId) {
        return encodings.get(encodingId);
    }

    /// Returns the registered extension for {@code extensionId}, or {@code null} if not registered.
    ///
    /// @param extensionId the extension id to look up
    /// @return the registered {@link Extension}, or {@code null}
    public Extension lookup(ExtensionId extensionId) {
        return extensions.get(extensionId);
    }

    MemorySegment decodeAsSegment(DecodeContext ctx) {
        ArrayNode node = ctx.node();
        Encoding encoding = switch (node) {
            case KnownArrayNode k -> encodings.get(k.encodingId());
            case UnknownArrayNode _ -> null;
        };
        if (encoding != null) {
            return ArraySegments.of(encoding.decode(ctx));
        }
        String id = switch (node) {
            case KnownArrayNode k -> k.encodingId().id();
            case UnknownArrayNode u -> u.rawEncodingId();
        };
        throw new VortexException("no encoding registered for " + id + " (or encoding has no primary segment)");
    }

    Array decode(DecodeContext ctx) {
        ArrayNode node = ctx.node();
        Encoding encoding = switch (node) {
            case KnownArrayNode k -> encodings.get(k.encodingId());
            case UnknownArrayNode _ -> null;
        };
        if (encoding != null) {
            return encoding.decode(ctx);
        }
        if (allowUnknown) {
            return decodeUnknown(ctx, node);
        }
        String id = switch (node) {
            case KnownArrayNode k -> k.encodingId().id();
            case UnknownArrayNode u -> u.rawEncodingId();
        };
        throw new VortexException("no encoding registered for " + id);
    }

    /// Builder for [Registry].
    ///
    /// Not thread-safe. Build once, use everywhere — the produced [Registry] is immutable.
    public static final class Builder {

        private final Map<EncodingId, Encoding> encodings = new HashMap<>();
        private final Map<ExtensionId, Extension> extensions = new HashMap<>();
        private boolean allowUnknown = false;

        private Builder() {
        }

        /// Registers an encoding implementation.
        ///
        /// @param encoding the {@link Encoding} to register
        /// @return this builder, for chaining
        /// @throws VortexException if an encoding with the same id is already registered
        public Builder register(Encoding encoding) {
            Encoding old = encodings.put(encoding.encodingId(), encoding);
            if (old != null) {
                throw new VortexException("encoding %s already registered".formatted(encoding.encodingId()));
            }
            return this;
        }

        /// Registers an extension implementation.
        ///
        /// @param extension the {@link Extension} to register
        /// @return this builder, for chaining
        /// @throws VortexException if an extension with the same id is already registered
        public Builder register(Extension extension) {
            Extension old = extensions.put(extension.extensionId(), extension);
            if (old != null) {
                throw new VortexException("extension %s already registered".formatted(extension.extensionId()));
            }
            return this;
        }

        /// Registers every [Encoding] and [Extension] discovered via `ServiceLoader`.
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a service-loaded entry collides with one already registered
        public Builder registerServiceLoaded() {
            for (Encoding encoding : ServiceLoader.load(Encoding.class)) {
                register(encoding);
            }
            for (Extension extension : ServiceLoader.load(Extension.class)) {
                register(extension);
            }
            return this;
        }

        /// Enable passthrough decode for unknown encoding ids.
        ///
        /// Default is strict: unknown ids throw [VortexException]. When enabled, unknown nodes
        /// (and all their children, recursively) are wrapped as [UnknownArray] preserving raw
        /// metadata + buffers + stats. Mirrors Rust `VortexSession::allow_unknown()`.
        ///
        /// @return this builder, for chaining
        public Builder allowUnknown() {
            this.allowUnknown = true;
            return this;
        }

        /// Builds an immutable [Registry].
        ///
        /// @return the immutable registry
        public Registry build() {
            return new Registry(encodings, extensions, allowUnknown);
        }
    }
}
