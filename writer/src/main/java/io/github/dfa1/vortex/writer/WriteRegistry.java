package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.encoding.EncodingId;
import io.github.dfa1.vortex.extension.ExtensionId;
import io.github.dfa1.vortex.writer.encode.EncodingEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/// Write-side registry: maps {@link EncodingId} to {@link EncodingEncoder} implementations,
/// and {@link ExtensionId} to {@link ExtensionEncoder} implementations.
///
/// Instances are immutable after construction. Build one via {@link #builder()} or via the
/// {@link #loadAll()} and {@link #empty()} convenience factories.
///
/// Usage:
/// ```java
/// WriteRegistry wr = WriteRegistry.builder().registerServiceLoaded().build();
/// VortexWriter.create(channel, schema, WriteOptions.defaults(), wr);
/// ```
public final class WriteRegistry {

    private final Map<EncodingId, EncodingEncoder> encoders;
    private final Map<ExtensionId, ExtensionEncoder> extensions;

    private WriteRegistry(Map<EncodingId, EncodingEncoder> encoders,
            Map<ExtensionId, ExtensionEncoder> extensions) {
        this.encoders = Map.copyOf(encoders);
        this.extensions = Map.copyOf(extensions);
    }

    /// Loads all service-discovered {@link EncodingEncoder} and {@link ExtensionEncoder} implementations.
    ///
    /// @return an immutable {@link WriteRegistry} populated with all service-loaded entries
    public static WriteRegistry loadAll() {
        return builder().registerServiceLoaded().build();
    }

    /// Creates an empty registry with no encoders or extensions registered.
    ///
    /// @return a new empty immutable {@link WriteRegistry}
    public static WriteRegistry empty() {
        return builder().build();
    }

    /// Returns a new {@link Builder}.
    ///
    /// @return a fresh builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns the encoder map for use in {@link io.github.dfa1.vortex.writer.encode.EncodeContext}.
    ///
    /// @return immutable encoder map
    public Map<EncodingId, EncodingEncoder> encoderMap() {
        return encoders;
    }

    /// Returns the registered extension encoder for {@code extensionId}, or {@code null} if not registered.
    ///
    /// @param extensionId the extension id to look up
    /// @return the registered {@link ExtensionEncoder}, or {@code null}
    public ExtensionEncoder lookup(ExtensionId extensionId) {
        return extensions.get(extensionId);
    }

    /// Builder for {@link WriteRegistry}.
    ///
    /// Not thread-safe. Build once, use everywhere — the produced {@link WriteRegistry} is immutable.
    public static final class Builder {

        private final Map<EncodingId, EncodingEncoder> encoders = new HashMap<>();
        private final Map<ExtensionId, ExtensionEncoder> extensions = new HashMap<>();

        private Builder() {
        }

        /// Registers an encoder.
        ///
        /// @param encoder the {@link EncodingEncoder} to register
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
        /// @param extension the {@link ExtensionEncoder} to register
        /// @return this builder, for chaining
        /// @throws VortexException if an extension with the same id is already registered
        public Builder register(ExtensionEncoder extension) {
            ExtensionEncoder old = extensions.put(extension.extensionId(), extension);
            if (old != null) {
                throw new VortexException("extension %s already registered".formatted(extension.extensionId()));
            }
            return this;
        }

        /// Registers every {@link EncodingEncoder} and {@link ExtensionEncoder} discovered via
        /// {@link ServiceLoader}.
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a service-loaded entry collides with one already registered
        public Builder registerServiceLoaded() {
            for (EncodingEncoder encoder : ServiceLoader.load(EncodingEncoder.class)) {
                register(encoder);
            }
            for (ExtensionEncoder extension : ServiceLoader.load(ExtensionEncoder.class)) {
                register(extension);
            }
            return this;
        }

        /// Builds an immutable {@link WriteRegistry}.
        ///
        /// @return the immutable registry
        public WriteRegistry build() {
            return new WriteRegistry(encoders, extensions);
        }
    }
}
