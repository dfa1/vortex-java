package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.writer.encode.AlpEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.AlpRdEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BitpackedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ByteBoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ChunkedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DateExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.DateTimePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DecimalBytePartsEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DecimalEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DictEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ExtEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FixedSizeListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FrameOfReferenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListViewEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MapEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.MaskedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PatchedEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PcoEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PrimitiveEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RleEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RunEndEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SequenceEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SparseEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.StructEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinViewEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VariantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZigZagEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZstdEncodingEncoder;

import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/// Write-side registry: maps [EncodingId] to [EncodingEncoder] implementations,
/// and [ExtensionId] to [ExtensionEncoder] implementations.
///
/// Instances are immutable after construction. Build one via [#builder()] or via the
/// [#loadAll()] and [#empty()] convenience factories.
///
/// Usage:
/// ```java
/// WriteRegistry wr = WriteRegistry.builder().registerDefaults().build();
/// VortexWriter.create(channel, schema, WriteOptions.defaults(), wr);
/// ```
public final class WriteRegistry {

    private final Map<EncodingId, EncodingEncoder> encoders;
    private final Map<ExtensionId, ExtensionEncoder> extensions;

    private WriteRegistry(Map<EncodingId, EncodingEncoder> encoders,
            Map<ExtensionId, ExtensionEncoder> extensions) {
        // Order by encoding name so it is stable regardless of enum declaration order. (The id
        // enums sort by ordinal naturally — Enum.compareTo is final — so a Comparator is required.)
        // VortexWriter.create(.., WriteRegistry) selects the first encoder whose accepts() matches
        // the dtype, so iteration order is significant: a HashMap would make selection vary across
        // runs and platforms. Sorting by id makes the order a pure function of the registered set,
        // independent of how it was assembled.
        this.encoders = sortedByName(encoders, EncodingId::id);
        this.extensions = sortedByName(extensions, ExtensionId::id);
    }

    private static <K, V> Map<K, V> sortedByName(Map<K, V> src, Function<K, String> name) {
        var sorted = new TreeMap<K, V>(Comparator.comparing(name));
        sorted.putAll(src);
        return Collections.unmodifiableMap(sorted);
    }

    /// Loads all built-in [EncodingEncoder] and [ExtensionEncoder] implementations.
    ///
    /// @return an immutable [WriteRegistry] populated with all built-in entries
    public static WriteRegistry loadAll() {
        return builder().registerDefaults().build();
    }

    /// Creates an empty registry with no encoders or extensions registered.
    ///
    /// @return a new empty immutable [WriteRegistry]
    public static WriteRegistry empty() {
        return builder().build();
    }

    /// Returns a new [Builder].
    ///
    /// @return a fresh builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the encoder map for use in [io.github.dfa1.vortex.writer.encode.EncodeContext].
    ///
    /// @return immutable encoder map
    public Map<EncodingId, EncodingEncoder> encoderMap() {
        return encoders;
    }

    /// Returns the registered extension encoder for `extensionId`, or `null` if not registered.
    ///
    /// @param extensionId the extension id to look up
    /// @return the registered [ExtensionEncoder], or `null`
    public ExtensionEncoder lookup(ExtensionId extensionId) {
        return extensions.get(extensionId);
    }

    /// Builder for [WriteRegistry].
    ///
    /// Not thread-safe. Build once, use everywhere — the produced [WriteRegistry] is immutable.
    public static final class Builder {

        // EncodingId is not Comparable (a Custom id would throw on natural ordering); order by
        // wire string, matching the constructor's sortedByName.
        private final Map<EncodingId, EncodingEncoder> encoders = new TreeMap<>(Comparator.comparing(EncodingId::id));
        private final Map<ExtensionId, ExtensionEncoder> extensions = new TreeMap<>();

        private Builder() {
        }

        /// Registers an encoder.
        ///
        /// @param encoder the [EncodingEncoder] to register
        /// @return this builder, for chaining
        /// @throws VortexException if an encoder for the same id is already registered
        public Builder register(EncodingEncoder encoder) {
            EncodingEncoder old = encoders.put(encoder.encodingId(), encoder);
            if (old != null) {
                throw new VortexException("encoder %s already registered".formatted(encoder.encodingId()));
            }
            return this;
        }

        /// Registers an extension encoder.
        ///
        /// @param extension the [ExtensionEncoder] to register
        /// @return this builder, for chaining
        /// @throws VortexException if an extension with the same id is already registered
        public Builder register(ExtensionEncoder extension) {
            ExtensionEncoder old = extensions.put(extension.extensionId(), extension);
            if (old != null) {
                throw new VortexException("extension %s already registered".formatted(extension.extensionId()));
            }
            return this;
        }

        /// Registers all built-in [EncodingEncoder] and [ExtensionEncoder] implementations.
        ///
        /// Registration order does not matter: [#build()] sorts the registered set by id, so the
        /// resulting registry has the same deterministic order no matter how it was populated.
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a built-in entry collides with one already registered
        public Builder registerDefaults() {
            register(new AlpEncodingEncoder())
                    .register(new AlpRdEncodingEncoder())
                    .register(new BitpackedEncodingEncoder())
                    .register(new BoolEncodingEncoder())
                    .register(new ByteBoolEncodingEncoder())
                    .register(new ChunkedEncodingEncoder())
                    .register(new ConstantEncodingEncoder())
                    .register(new DateTimePartsEncodingEncoder())
                    .register(new DecimalBytePartsEncodingEncoder())
                    .register(new DecimalEncodingEncoder())
                    .register(new DeltaEncodingEncoder())
                    .register(new DictEncodingEncoder())
                    .register(new ExtEncodingEncoder())
                    .register(new FixedSizeListEncodingEncoder())
                    .register(new FrameOfReferenceEncodingEncoder())
                    .register(new FsstEncodingEncoder())
                    .register(new ListEncodingEncoder())
                    .register(new ListViewEncodingEncoder())
                    .register(new MapEncodingEncoder())
                    .register(new MaskedEncodingEncoder())
                    .register(new NullEncodingEncoder())
                    .register(new PatchedEncodingEncoder())
                    .register(new PcoEncodingEncoder())
                    .register(new PrimitiveEncodingEncoder())
                    .register(new RleEncodingEncoder())
                    .register(new RunEndEncodingEncoder())
                    .register(new SequenceEncodingEncoder())
                    .register(new SparseEncodingEncoder())
                    .register(new StructEncodingEncoder())
                    .register(new VarBinEncodingEncoder())
                    .register(new VariantEncodingEncoder())
                    .register(new VarBinViewEncodingEncoder())
                    .register(new ZigZagEncodingEncoder())
                    .register(new ZstdEncodingEncoder());
            register(DateExtensionEncoder.INSTANCE)
                    .register(TimeExtensionEncoder.INSTANCE)
                    .register(TimestampExtensionEncoder.INSTANCE)
                    .register(UuidExtensionEncoder.INSTANCE);
            return this;
        }

        /// Builds an immutable [WriteRegistry].
        ///
        /// @return the immutable registry
        public WriteRegistry build() {
            return new WriteRegistry(encoders, extensions);
        }
    }
}
