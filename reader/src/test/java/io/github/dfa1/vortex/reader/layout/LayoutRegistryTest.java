package io.github.dfa1.vortex.reader.layout;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.UnknownArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/// Drives the [LayoutRegistry] dispatch surface: default population (including the zoned dual
/// alias), duplicate-registration rejection, the loud unknown-layout failure, and custom-decoder
/// dispatch — the last both at the registry level and end-to-end through a real scan.
class LayoutRegistryTest {

    @ParameterizedTest
    @ValueSource(strings = {"vortex.flat", "vortex.chunked", "vortex.zoned", "vortex.stats", "vortex.dict", "vortex.struct"})
    void defaults_containTheBuiltins_bothZonedAliasesResolve(String wireId) {
        // Given — the registry populated with the built-in decoders
        LayoutRegistry sut = LayoutRegistry.defaults();

        // When
        boolean result = sut.hasDecoder(LayoutId.parse(wireId));

        // Then — flat, chunked, dict, struct, and BOTH zoned aliases (vortex.zoned + legacy
        // vortex.stats) resolve to a decoder
        assertThat(result).isTrue();
    }

    @Test
    void register_duplicateId_throwsVortexException() {
        // Given — a decoder claiming vortex.flat, an id the defaults already own
        LayoutDecoder duplicate = stubDecoder(LayoutId.FLAT, sentinelArray());
        LayoutRegistry.Builder builder = LayoutRegistry.builder().registerDefaults();

        // When / Then — re-registering an occupied id is rejected loudly
        assertThatThrownBy(() -> builder.register(duplicate))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("already registered")
                .hasMessageContaining("vortex.flat");
    }

    @Test
    void decode_unknownLayoutId_throwsVortexExceptionWithExactMessage() {
        // Given — a layout carrying a Custom id no decoder handles
        LayoutRegistry sut = LayoutRegistry.defaults();
        Layout unknown = new Layout(new LayoutId.Custom("acme.frobnicate"), 0L, null, List.of(), List.of());
        LayoutDecodeContext ctx = mock(LayoutDecodeContext.class);
        DType dtype = primitive();

        // When / Then — no allow-unknown mode: the message is byte-identical to the pre-SPI throw
        assertThatThrownBy(() -> sut.decode(ctx, unknown, dtype))
                .isInstanceOf(VortexException.class)
                .hasMessage("cannot decode layout acme.frobnicate");
    }

    @Test
    void decode_customLayoutId_dispatchesToRegisteredDecoder() {
        // Given — a custom decoder registered under a Custom id, returning a sentinel array
        LayoutId customId = new LayoutId.Custom("acme.custom");
        Array sentinel = sentinelArray();
        LayoutRegistry sut = LayoutRegistry.builder()
                .registerDefaults()
                .register(stubDecoder(customId, sentinel))
                .build();
        Layout custom = new Layout(customId, 0L, null, List.of(), List.of());
        LayoutDecodeContext ctx = mock(LayoutDecodeContext.class);

        // When
        Array result = sut.decode(ctx, custom, primitive());

        // Then — the custom decoder handled the node, proving id-keyed dispatch
        assertThat(result).isSameAs(sentinel);
    }

    @Test
    void openWithCustomRegistry_reachesTheCustomDecoderDuringScan() throws URISyntaxException, IOException {
        // Given — a LayoutRegistry whose built-ins are wrapped in counting delegators, so any decode
        // dispatch during a real scan is observable. This proves the open(path, reg, layoutReg)
        // overload threads the custom registry all the way into ScanIterator's decode path.
        AtomicInteger flatDispatches = new AtomicInteger();
        AtomicInteger otherDispatches = new AtomicInteger();
        LayoutRegistry custom = LayoutRegistry.builder()
                .register(new CountingLayoutDecoder(new FlatLayoutDecoder(), flatDispatches))
                .register(new CountingLayoutDecoder(new ChunkedLayoutDecoder(), otherDispatches))
                .register(new CountingLayoutDecoder(new ZonedLayoutDecoder(), otherDispatches))
                .register(new CountingLayoutDecoder(new DictLayoutDecoder(), otherDispatches))
                .build();
        Path fixture = fixtureFile("primitives.vortex");

        // When — a full scan decodes every chunk through the custom registry
        long rows = 0L;
        try (var reader = VortexReader.open(fixture, ReadRegistry.loadAll(), custom);
             var iter = reader.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    rows += chunk.rowCount();
                }
            }
        }

        // Then — the scan produced rows, and the FLAT delegator itself fired: leaf decode under a
        // container layout goes through registry dispatch too (a custom flat decoder is honored),
        // not through a direct built-in call that would bypass the registry.
        assertThat(rows).isPositive();
        assertThat(flatDispatches.get()).isPositive();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DType primitive() {
        return new DType.Primitive(PType.I32, false);
    }

    /// A concrete stand-in [Array] — the sealed interface cannot be mocked, and these tests only
    /// need object identity, never its contents.
    private static Array sentinelArray() {
        return new UnknownArray(EncodingId.parse("stub"), primitive(), 0L, null,
                new MemorySegment[0], new Array[0]);
    }

    private static LayoutDecoder stubDecoder(LayoutId id, Array result) {
        return new LayoutDecoder() {
            @Override
            public LayoutId layoutId() {
                return id;
            }

            @Override
            public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
                return result;
            }
        };
    }

    private Path fixtureFile(String name) throws URISyntaxException {
        var url = getClass().getResource("/fixtures/" + name);
        assertThat(url).as("fixture not found: " + name).isNotNull();
        return Path.of(url.toURI());
    }

    /// Wraps a built-in [LayoutDecoder], tallying every dispatch before delegating, so a real scan
    /// can assert its layout decodes flowed through this registry.
    private record CountingLayoutDecoder(LayoutDecoder delegate, AtomicInteger count)
            implements LayoutDecoder {

        @Override
        public LayoutId layoutId() {
            return delegate.layoutId();
        }

        @Override
        public Set<LayoutId> layoutIds() {
            return delegate.layoutIds();
        }

        @Override
        public Array decode(LayoutDecodeContext ctx, Layout layout, DType dtype) {
            count.incrementAndGet();
            return delegate.decode(ctx, layout, dtype);
        }
    }
}
