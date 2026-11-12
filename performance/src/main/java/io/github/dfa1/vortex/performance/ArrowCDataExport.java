package io.github.dfa1.vortex.performance;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;

/// Spike: export a Vortex column as an Apache Arrow array through the Arrow C-Data
/// Interface using only `java.lang.foreign` — no `arrow-vector` on the producer side,
/// and **zero copy**: the Arrow values buffer points straight at the Vortex
/// `MemorySegment`.
///
/// The producer ([#exportInt64], [#fillSchemaInt64]) hand-builds the two ABI structs
/// (`ArrowArray` + `ArrowSchema`) in off-heap memory and installs FFM upcall stubs as
/// their `release` callbacks. The values buffer pointer is the Vortex segment's own
/// address, so no element is copied.
///
/// `main` proves it round-trips: it imports the FFM-built structs back through
/// `arrow-c-data` (`Data.importVector`) — which adopts the foreign buffer by address —
/// and checks both the values and that the imported vector's data buffer address equals
/// the original Vortex segment address (confirming zero copy).
///
/// Lives in the performance module because only it may depend on Arrow (which uses
/// `sun.misc.Unsafe`); the producer half here is Unsafe-free FFM and could move to a
/// future `vortex-arrow` module (ADR 0016, Option B).
public final class ArrowCDataExport {

    private static final Linker LINKER = Linker.nativeLinker();

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // ── ArrowSchema struct (C-Data ABI, 64-bit) — 9 pointer/int64 slots ──────────
    private static final long SCHEMA_FORMAT = 0;
    private static final long SCHEMA_NAME = 8;
    private static final long SCHEMA_METADATA = 16;
    private static final long SCHEMA_FLAGS = 24;
    private static final long SCHEMA_N_CHILDREN = 32;
    private static final long SCHEMA_CHILDREN = 40;
    private static final long SCHEMA_DICTIONARY = 48;
    private static final long SCHEMA_RELEASE = 56;
    private static final long SCHEMA_PRIVATE = 64;
    private static final long SCHEMA_SIZE = 72;

    // ── ArrowArray struct (C-Data ABI, 64-bit) — 10 pointer/int64 slots ──────────
    private static final long ARRAY_LENGTH = 0;
    private static final long ARRAY_NULL_COUNT = 8;
    private static final long ARRAY_OFFSET = 16;
    private static final long ARRAY_N_BUFFERS = 24;
    private static final long ARRAY_N_CHILDREN = 32;
    private static final long ARRAY_BUFFERS = 40;
    private static final long ARRAY_CHILDREN = 48;
    private static final long ARRAY_DICTIONARY = 56;
    private static final long ARRAY_RELEASE = 64;
    private static final long ARRAY_PRIVATE = 72;
    private static final long ARRAY_SIZE = 80;

    private ArrowCDataExport() {
    }

    /// Hand-builds an `ArrowSchema` for a non-nullable Int64 column (format code `l`).
    ///
    /// @param arena allocator for the struct, its strings, and the release upcall stub
    /// @return a [MemorySegment] over the populated `ArrowSchema` struct
    public static MemorySegment fillSchemaInt64(Arena arena) {
        MemorySegment schema = arena.allocate(SCHEMA_SIZE);
        schema.set(ValueLayout.ADDRESS, SCHEMA_FORMAT, arena.allocateFrom("l"));
        schema.set(ValueLayout.ADDRESS, SCHEMA_NAME, arena.allocateFrom("col"));
        schema.set(ValueLayout.ADDRESS, SCHEMA_METADATA, MemorySegment.NULL);
        schema.set(ValueLayout.JAVA_LONG, SCHEMA_FLAGS, 0L);
        schema.set(ValueLayout.JAVA_LONG, SCHEMA_N_CHILDREN, 0L);
        schema.set(ValueLayout.ADDRESS, SCHEMA_CHILDREN, MemorySegment.NULL);
        schema.set(ValueLayout.ADDRESS, SCHEMA_DICTIONARY, MemorySegment.NULL);
        schema.set(ValueLayout.ADDRESS, SCHEMA_RELEASE, releaseStub(arena, "releaseSchema"));
        schema.set(ValueLayout.ADDRESS, SCHEMA_PRIVATE, MemorySegment.NULL);
        return schema;
    }

    /// Exports a non-nullable Int64 Vortex column as an `ArrowArray` whose values buffer
    /// is the Vortex segment itself — zero copy.
    ///
    /// @param values the materialised little-endian `i64` segment (Arrow values buffer)
    /// @param length element count
    /// @param arena  allocator for the struct, the 2-pointer buffer table, and the release stub
    /// @return a [MemorySegment] over the populated `ArrowArray` struct
    public static MemorySegment exportInt64(MemorySegment values, long length, Arena arena) {
        // buffers[] = { validity = NULL, values = &segment } — non-nullable, so validity is null.
        MemorySegment buffers = arena.allocate(2 * ValueLayout.ADDRESS.byteSize());
        buffers.setAtIndex(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
        buffers.setAtIndex(ValueLayout.ADDRESS, 1, values);

        MemorySegment array = arena.allocate(ARRAY_SIZE);
        array.set(ValueLayout.JAVA_LONG, ARRAY_LENGTH, length);
        array.set(ValueLayout.JAVA_LONG, ARRAY_NULL_COUNT, 0L);
        array.set(ValueLayout.JAVA_LONG, ARRAY_OFFSET, 0L);
        array.set(ValueLayout.JAVA_LONG, ARRAY_N_BUFFERS, 2L);
        array.set(ValueLayout.JAVA_LONG, ARRAY_N_CHILDREN, 0L);
        array.set(ValueLayout.ADDRESS, ARRAY_BUFFERS, buffers);
        array.set(ValueLayout.ADDRESS, ARRAY_CHILDREN, MemorySegment.NULL);
        array.set(ValueLayout.ADDRESS, ARRAY_DICTIONARY, MemorySegment.NULL);
        array.set(ValueLayout.ADDRESS, ARRAY_RELEASE, releaseStub(arena, "releaseArray"));
        array.set(ValueLayout.ADDRESS, ARRAY_PRIVATE, MemorySegment.NULL);
        return array;
    }

    /// Release callback for an exported `ArrowArray`: marks it released by nulling the
    /// `release` slot. The backing memory is owned by the producer's [Arena], so there is
    /// nothing else to free here.
    ///
    /// @param arrayPtr pointer to the `ArrowArray` struct the consumer is releasing
    private static void releaseArray(MemorySegment arrayPtr) {
        arrayPtr.reinterpret(ARRAY_SIZE).set(ValueLayout.ADDRESS, ARRAY_RELEASE, MemorySegment.NULL);
    }

    /// Release callback for an exported `ArrowSchema`: marks it released by nulling the
    /// `release` slot.
    ///
    /// @param schemaPtr pointer to the `ArrowSchema` struct the consumer is releasing
    private static void releaseSchema(MemorySegment schemaPtr) {
        schemaPtr.reinterpret(SCHEMA_SIZE).set(ValueLayout.ADDRESS, SCHEMA_RELEASE, MemorySegment.NULL);
    }

    private static MemorySegment releaseStub(Arena arena, String method) {
        try {
            MethodHandle handle = MethodHandles.lookup().findStatic(
                    ArrowCDataExport.class, method, MethodType.methodType(void.class, MemorySegment.class));
            return LINKER.upcallStub(handle, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), arena);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind release stub " + method, e);
        }
    }

    /// Builds a Vortex column, exports it via the C-Data Interface (zero copy), imports it
    /// back through arrow-c-data, and verifies values + buffer-address identity.
    ///
    /// @param args ignored
    public static void main(String[] args) {
        try (Arena arena = Arena.ofConfined();
             RootAllocator allocator = new RootAllocator()) {

            long n = 8;
            MemorySegment src = arena.allocate(n * 8L, 8);
            for (long i = 0; i < n; i++) {
                src.setAtIndex(LE_LONG, i, (i + 1) * 100L);
            }
            LongArray vortexColumn = new MaterializedLongArray(new DType.Primitive(PType.I64, false), n, src);

            // materialize() -> the values buffer; export points Arrow straight at it.
            MemorySegment values = vortexColumn.materialize(arena);
            MemorySegment schemaStruct = fillSchemaInt64(arena);
            MemorySegment arrayStruct = exportInt64(values, n, arena);

            System.out.println("Vortex values segment address = 0x" + Long.toHexString(values.address()));

            // Import the FFM-built structs back through arrow-c-data (adopts buffer by address).
            try (ArrowArray cArray = ArrowArray.wrap(arrayStruct.address());
                 ArrowSchema cSchema = ArrowSchema.wrap(schemaStruct.address());
                 FieldVector imported = Data.importVector(allocator, cArray, cSchema, null)) {

                BigIntVector vec = (BigIntVector) imported;
                long arrowBufAddr = vec.getDataBufferAddress();

                System.out.println("Imported Arrow vector  = " + vec);
                System.out.println("Arrow data buffer addr = 0x" + Long.toHexString(arrowBufAddr));
                System.out.println("zero-copy (addresses equal) = " + (arrowBufAddr == values.address()));
            }
        }
    }
}
