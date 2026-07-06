package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.array.Array;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/// Read-side registry mapping [LayoutId] to [LayoutDecoder] implementations, making layout decode
/// pluggable the way [io.github.dfa1.vortex.reader.ReadRegistry] makes encodings pluggable.
///
/// Instances are immutable after construction. Build one via [#builder()], or take the four
/// built-ins directly via [#defaults()].
///
/// Unlike encodings, layouts register programmatically only — there is no [java.util.ServiceLoader]
/// discovery (mirroring `ExtensionDecoder`). Unknown layout ids fail loudly: there is no
/// allow-unknown mode, matching the Rust reference.
public final class LayoutRegistry {

    // Keyed by the typed id — Layout already carries a parsed LayoutId, so dispatch never
    // round-trips through the wire string. Ordered by that string (LayoutId is not Comparable;
    // a Custom key must not throw): order is not load-bearing, but stable ordering keeps the
    // registries consistent.
    private final Map<LayoutId, LayoutDecoder> decoders;

    private LayoutRegistry(Map<LayoutId, LayoutDecoder> decoders) {
        var sorted = new TreeMap<LayoutId, LayoutDecoder>(Comparator.comparing(LayoutId::id));
        sorted.putAll(decoders);
        this.decoders = Collections.unmodifiableMap(sorted);
    }

    /// Returns a registry populated with the built-in layout decoders (flat, chunked,
    /// zoned/stats, dict, struct).
    ///
    /// @return an immutable [LayoutRegistry] with the built-in decoders registered
    public static LayoutRegistry defaults() {
        return builder().registerDefaults().build();
    }

    /// Returns a new [Builder].
    ///
    /// @return a fresh builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns `true` if a decoder is registered for the given layout id.
    ///
    /// @param layoutId the layout id to query
    /// @return `true` if a decoder is registered
    public boolean hasDecoder(LayoutId layoutId) {
        return decoders.containsKey(layoutId);
    }

    /// Decodes `layout` into an [Array] of `dtype` by dispatching to the decoder registered for
    /// its [LayoutId]. An unregistered id fails loudly.
    ///
    /// @param ctx    the decode context bound to the current chunk epoch
    /// @param layout the layout node to decode
    /// @param dtype  logical type the layout decodes to
    /// @return the decoded [Array]
    /// @throws VortexException if no decoder is registered for `layout`'s id
    public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
        LayoutDecoder decoder = decoders.get(layout.layoutId());
        if (decoder == null) {
            throw new VortexException("cannot decode layout " + layout.layoutId());
        }
        return decoder.decode(ctx, layout, dtype);
    }

    /// Builder for [LayoutRegistry].
    ///
    /// Not thread-safe. Build once, use everywhere — the produced [LayoutRegistry] is immutable.
    public static final class Builder {

        private final Map<LayoutId, LayoutDecoder> decoders = new TreeMap<>(Comparator.comparing(LayoutId::id));

        private Builder() {
        }

        /// Registers a decoder under every id it declares via [LayoutDecoder#layoutIds()], so an
        /// aliased decoder (e.g. the zoned decoder under both `vortex.zoned` and `vortex.stats`)
        /// registers once and resolves under either id.
        ///
        /// @param decoder the [LayoutDecoder] to register
        /// @return this builder, for chaining
        /// @throws VortexException if any of the decoder's ids is already registered
        public Builder register(LayoutDecoder decoder) {
            for (LayoutId id : decoder.layoutIds()) {
                LayoutDecoder old = decoders.put(id, decoder);
                if (old != null) {
                    throw new VortexException("layout decoder %s already registered".formatted(id));
                }
            }
            return this;
        }

        /// Registers the built-in layout decoders (flat, chunked, zoned/stats, dict, struct).
        ///
        /// @return this builder, for chaining
        public Builder registerDefaults() {
            return register(new FlatLayoutDecoder())
                    .register(new ChunkedLayoutDecoder())
                    .register(new ZonedLayoutDecoder())
                    .register(new DictLayoutDecoder())
                    .register(new StructLayoutDecoder());
        }

        /// Builds an immutable [LayoutRegistry].
        ///
        /// @return the immutable registry
        public LayoutRegistry build() {
            return new LayoutRegistry(decoders);
        }
    }
}
