package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.UnknownArray;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/// Registry mapping encoding IDs to [Encoding] implementations.
public final class EncodingRegistry {

    private final Map<EncodingId, Encoding> encodings;
    private boolean allowUnknown;

    private EncodingRegistry() {
        encodings = new HashMap<>();
        allowUnknown = false;
    }

    /// Load all [Encoding]s registered via `ServiceLoader`.
    ///
    /// @return a new {@link EncodingRegistry} populated with all service-loaded encodings
    public static EncodingRegistry loadAll() {
        var registry = new EncodingRegistry();
        for (Encoding encoding : ServiceLoader.load(Encoding.class)) {
            registry.register(encoding);
        }
        return registry;
    }

    /// Creates an empty registry with no encodings registered.
    ///
    /// @return a new empty {@link EncodingRegistry}
    public static EncodingRegistry empty() {
        return new EncodingRegistry();
    }

    /// Creates a registry populated with the given encodings.
    ///
    /// @param encodings the encodings to register
    /// @return a new {@link EncodingRegistry} populated with the given encodings
    public static EncodingRegistry of(List<Encoding> encodings) {
        var registry = new EncodingRegistry();
        for (Encoding encoding : encodings) {
            registry.register(encoding);
        }
        return registry;
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

    /// Enable passthrough decode for unknown encoding ids.
    ///
    /// Default is strict: unknown ids throw [VortexException]. When enabled, unknown nodes
    /// (and all their children, recursively) are wrapped as [UnknownArray] preserving raw
    /// metadata + buffers + stats. Mirrors Rust `VortexSession::allow_unknown()`.
    ///
    /// @return this registry, for chaining
    public EncodingRegistry allowUnknown() {
        this.allowUnknown = true;
        return this;
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

    /// Registers an encoding implementation in this registry.
    ///
    /// @param encoding the {@link Encoding} to register
    /// @throws io.github.dfa1.vortex.core.VortexException if an encoding with the same id is already registered
    public void register(Encoding encoding) {
        Encoding old = encodings.put(encoding.encodingId(), encoding);
        if (old != null) {
            throw new VortexException("encoding %s already registered".formatted(encoding.encodingId()));
        }
    }

    MemorySegment decodeAsSegment(DecodeContext ctx) {
        ArrayNode node = ctx.node();
        Encoding encoding = switch (node) {
            case KnownArrayNode k -> encodings.get(k.encodingId());
            case UnknownArrayNode _ -> null;
        };
        if (encoding != null) {
            return encoding.decodeSegment(ctx);
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
}
