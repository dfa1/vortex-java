package io.github.dfa1.vortex.writer.zstdspike;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Spike: a thin FFM binding to the system `libzstd` (no JNI, no `Unsafe`).
///
/// Binds the four C entry points we need - `ZSTD_compressBound`, `ZSTD_compress`,
/// `ZSTD_decompress`, `ZSTD_isError` - via [Linker] downcalls. The library is resolved from a few
/// well-known paths (override with `-Dzstd.lib.path=...`); [#available()] reports whether it loaded
/// so callers can fall back to the pure-Java path when no native lib is present.
///
/// This is throwaway measurement code - it copies through heap `byte[]` for an apples-to-apples
/// comparison with aircompressor. A real encoder/decoder would work straight on arena segments.
final class ZstdNative {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = load();

    private static final MethodHandle COMPRESS_BOUND = bind(
            "ZSTD_compressBound", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
    private static final MethodHandle COMPRESS = bind(
            "ZSTD_compress", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle DECOMPRESS = bind(
            "ZSTD_decompress", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
    private static final MethodHandle IS_ERROR = bind(
            "ZSTD_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    private ZstdNative() {
    }

    static boolean available() {
        return LOOKUP != null;
    }

    static byte[] compress(byte[] src, int level) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(src.length);
            MemorySegment.copy(src, 0, in, JAVA_BYTE, 0, src.length);
            long bound = (long) COMPRESS_BOUND.invokeExact((long) src.length);
            MemorySegment out = arena.allocate(bound);
            long n = (long) COMPRESS.invokeExact(out, bound, in, (long) src.length, level);
            checkError(n, "ZSTD_compress");
            byte[] result = new byte[(int) n];
            MemorySegment.copy(out, JAVA_BYTE, 0, result, 0, (int) n);
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd compress failed", t);
        }
    }

    static byte[] decompress(byte[] src, int decompressedSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(src.length);
            MemorySegment.copy(src, 0, in, JAVA_BYTE, 0, src.length);
            MemorySegment out = arena.allocate(decompressedSize);
            long n = (long) DECOMPRESS.invokeExact(out, (long) decompressedSize, in, (long) src.length);
            checkError(n, "ZSTD_decompress");
            byte[] result = new byte[(int) n];
            MemorySegment.copy(out, JAVA_BYTE, 0, result, 0, (int) n);
            return result;
        } catch (Throwable t) {
            throw new IllegalStateException("zstd decompress failed", t);
        }
    }

    private static void checkError(long code, String fn) throws Throwable {
        int err = (int) IS_ERROR.invokeExact(code);
        if (err != 0) {
            throw new IllegalStateException(fn + " returned an error (code " + code + ")");
        }
    }

    @SuppressWarnings("restricted") // FFM native binding: downcalls into libzstd are this class's purpose
    private static MethodHandle bind(String name, FunctionDescriptor descriptor) {
        if (LOOKUP == null) {
            return null;
        }
        return LOOKUP.find(name)
                .map(addr -> LINKER.downcallHandle(addr, descriptor))
                .orElseThrow(() -> new IllegalStateException("libzstd missing symbol " + name));
    }

    @SuppressWarnings("restricted") // FFM native binding: loading libzstd is this class's purpose
    private static SymbolLookup load() {
        String[] candidates = {
                System.getProperty("zstd.lib.path"),
                "/opt/homebrew/lib/libzstd.dylib",
                "/usr/local/lib/libzstd.dylib",
                "/usr/lib/x86_64-linux-gnu/libzstd.so.1",
                "/lib/x86_64-linux-gnu/libzstd.so.1",
                "/usr/lib/aarch64-linux-gnu/libzstd.so.1",
                "libzstd.so.1",
                "zstd"
        };
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                return SymbolLookup.libraryLookup(candidate, Arena.global());
            } catch (IllegalArgumentException _) {
                // Not at this path / not loadable; try the next candidate.
            }
        }
        return null;
    }
}
