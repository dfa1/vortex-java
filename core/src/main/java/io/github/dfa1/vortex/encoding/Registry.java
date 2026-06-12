package io.github.dfa1.vortex.encoding;

import io.github.dfa1.vortex.core.VortexException;
import io.github.dfa1.vortex.extension.Extension;
import io.github.dfa1.vortex.extension.ExtensionId;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/// Extension registry: maps {@link ExtensionId} to {@link Extension} implementations.
///
/// <p>Encoding dispatch lives in {@code ReadRegistry} (reader module).
/// Instances are immutable after construction. Build one via {@link #builder()} or
/// via the {@link #loadAll()}, {@link #empty()} convenience factories.
public final class Registry {

    private final Map<ExtensionId, Extension> extensions;

    private Registry(Map<ExtensionId, Extension> extensions) {
        this.extensions = Map.copyOf(extensions);
    }

    /// Returns a new {@link Builder}.
    ///
    /// @return a fresh builder
    public static Builder builder() {
        return new Builder();
    }

    /// Loads all service-discovered {@link Extension} implementations.
    ///
    /// @return an immutable {@link Registry} populated with all service-loaded extensions
    public static Registry loadAll() {
        return builder().registerServiceLoaded().build();
    }

    /// Creates an empty registry with no extensions registered.
    ///
    /// @return a new empty immutable {@link Registry}
    public static Registry empty() {
        return builder().build();
    }

    /// Returns the registered extension for {@code extensionId}, or {@code null} if not registered.
    ///
    /// @param extensionId the extension id to look up
    /// @return the registered {@link Extension}, or {@code null}
    public Extension lookup(ExtensionId extensionId) {
        return extensions.get(extensionId);
    }

    /// Builder for {@link Registry}.
    ///
    /// Not thread-safe. Build once, use everywhere — the produced {@link Registry} is immutable.
    public static final class Builder {

        private final Map<ExtensionId, Extension> extensions = new HashMap<>();

        private Builder() {
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

        /// Registers every {@link Extension} discovered via {@link ServiceLoader}.
        ///
        /// @return this builder, for chaining
        /// @throws VortexException if a service-loaded entry collides with one already registered
        public Builder registerServiceLoaded() {
            for (Extension extension : ServiceLoader.load(Extension.class)) {
                register(extension);
            }
            return this;
        }

        /// Builds an immutable {@link Registry}.
        ///
        /// @return the immutable registry
        public Registry build() {
            return new Registry(extensions);
        }
    }
}
