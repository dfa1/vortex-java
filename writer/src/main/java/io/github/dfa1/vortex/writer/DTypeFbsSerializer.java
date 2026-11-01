package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.fbs.FbsBinary;
import io.github.dfa1.vortex.core.fbs.FbsBool;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsDecimal;
import io.github.dfa1.vortex.core.fbs.FbsDType;
import io.github.dfa1.vortex.core.fbs.FbsExtension;
import io.github.dfa1.vortex.core.fbs.FbsFixedSizeList;
import io.github.dfa1.vortex.core.fbs.FbsList;
import io.github.dfa1.vortex.core.fbs.FbsMap;
import io.github.dfa1.vortex.core.fbs.FbsNull;
import io.github.dfa1.vortex.core.fbs.FbsPrimitive;
import io.github.dfa1.vortex.core.fbs.FbsStruct;
import io.github.dfa1.vortex.core.fbs.FbsType;
import io.github.dfa1.vortex.core.fbs.FbsUtf8;
import io.github.dfa1.vortex.core.fbs.FbsUnion;
import io.github.dfa1.vortex.core.fbs.FbsVariant;
import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.ExtensionId;
import io.github.dfa1.vortex.core.model.ExtensionMetadata;
import io.github.dfa1.vortex.core.model.OpaqueMetadata;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimeUnitMetadata;
import io.github.dfa1.vortex.core.model.TimestampMetadata;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/// Serializes a [DType] tree to its FlatBuffer wire encoding (the file's DType blob, pointed to
/// by the postscript).
final class DTypeFbsSerializer {

    private final WriteRegistry extensions;

    /// @param extensions the extension encoders, which serialize metadata of their own types
    DTypeFbsSerializer(WriteRegistry extensions) {
        this.extensions = extensions;
    }

    /// Serializes `dtype` to a standalone, finished FlatBuffer.
    ///
    /// @param dtype the schema (or nested type) to serialize
    /// @return the finished FlatBuffer bytes, little-endian
    ByteBuffer buildDType(DType dtype) {
        var fbb = new FbsBuilder(128);
        int off = serializeDType(fbb, dtype);
        FbsDType.finishFbsDTypeBuffer(fbb, off);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Serializes an extension's metadata, as Rust's `serialize_metadata`: the inverse of the
    /// reader's `ExtensionMetadataParser`. The built-in records and [OpaqueMetadata] are written
    /// here; any other record by the extension's registered encoder.
    ///
    /// @param ext an extension dtype
    /// @return the metadata's wire bytes
    /// @throws VortexException if the metadata is a record no registered encoder serializes, or a
    ///         timezone longer than the wire's u16 length
    byte[] metadataBytes(DType.Extension ext) {
        return switch (ext.metadata()) {
            case TimeUnitMetadata(TimeUnit unit) -> new byte[]{(byte) unit.ordinal()};
            case TimestampMetadata(TimeUnit unit, ZoneId zone) -> timestampBytes(unit, zone);
            case OpaqueMetadata(MemorySegment bytes) -> bytes.toArray(ValueLayout.JAVA_BYTE);
            case ExtensionMetadata other -> {
                ExtensionEncoder encoder = extensions.lookup(ExtensionId.parse(ext.extensionId()));
                if (encoder == null) {
                    throw new VortexException("no extension encoder registered for " + ext.extensionId()
                            + " to serialize its metadata of type " + other.getClass().getName());
                }
                yield encoder.serializeMetadata(other);
            }
        };
    }

    /// `byte[0] = TimeUnit tag, bytes[1..3] = tz_len (u16 LE), bytes[3..3+tz_len] = tz UTF-8`.
    private static byte[] timestampBytes(TimeUnit unit, ZoneId zone) {
        byte[] tz = zone == null ? new byte[0] : zone.getId().getBytes(StandardCharsets.UTF_8);
        if (tz.length > 0xFFFF) {
            throw new VortexException("timestamp timezone longer than 65535 bytes: " + zone);
        }
        byte[] out = new byte[3 + tz.length];
        out[0] = (byte) unit.ordinal();
        out[1] = (byte) tz.length;
        out[2] = (byte) (tz.length >>> 8);
        System.arraycopy(tz, 0, out, 3, tz.length);
        return out;
    }

    /// Serializes `dtype` into `fbb`, returning the offset of the resulting `FbsDType` table.
    /// Recurses into nested types (struct fields, list/map elements, extension storage) bottom-up,
    /// as FlatBuffers construction requires.
    ///
    /// @param fbb   the builder to serialize into
    /// @param dtype the type to serialize
    /// @return the offset of the written `FbsDType` table
    int serializeDType(FbsBuilder fbb, DType dtype) {
        return switch (dtype) {
            case DType.Null _ -> {
                FbsNull.startFbsNull(fbb);
                int inner = FbsNull.endFbsNull(fbb);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsNull, inner);
            }
            case DType.Bool(var nullable) -> {
                int inner = FbsBool.createFbsBool(fbb, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsBool, inner);
            }
            case DType.Primitive(var ptype, var nullable) -> {
                int inner = FbsPrimitive.createFbsPrimitive(fbb, ptype.ordinal(), nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsPrimitive, inner);
            }
            case DType.Decimal(var precision, var scale, var nullable) -> {
                int inner = FbsDecimal.createFbsDecimal(fbb, precision, scale, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsDecimal, inner);
            }
            case DType.Struct(var fieldNames, var fieldTypes, var nullable) -> {
                // Build child DType tables first (FlatBuffers bottom-up requirement)
                int[] fieldOffsets = new int[fieldTypes.size()];
                for (int i = 0; i < fieldOffsets.length; i++) {
                    fieldOffsets[i] = serializeDType(fbb, fieldTypes.get(i));
                }
                int[] nameOffsets = new int[fieldNames.size()];
                for (int i = 0; i < nameOffsets.length; i++) {
                    nameOffsets[i] = fbb.createString(fieldNames.get(i).value());
                }
                int namesVec = FbsStruct.createNamesVector(fbb, nameOffsets);
                int dtypesVec = FbsStruct.createDtypesVector(fbb, fieldOffsets);
                int inner = FbsStruct.createFbsStruct(fbb, namesVec, dtypesVec, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsStruct, inner);
            }
            case DType.Utf8(var nullable) -> {
                int inner = FbsUtf8.createFbsUtf8(fbb, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsUtf8, inner);
            }
            case DType.Binary(var nullable) -> {
                int inner = FbsBinary.createFbsBinary(fbb, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsBinary, inner);
            }
            case DType.List(var elementType, var nullable) -> {
                int elemTypeOff = serializeDType(fbb, elementType);
                int inner = FbsList.createFbsList(fbb, elemTypeOff, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsList, inner);
            }
            case DType.FixedSizeList(var elementType, var fixedSize, var nullable) -> {
                int elemTypeOff = serializeDType(fbb, elementType);
                int inner = FbsFixedSizeList.createFbsFixedSizeList(fbb, elemTypeOff, fixedSize, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsFixedSizeList, inner);
            }
            case DType.Map(var keyType, var valueType, var keysSorted, var nullable) -> {
                int keyTypeOff = serializeDType(fbb, keyType);
                int valueTypeOff = serializeDType(fbb, valueType);
                int inner = FbsMap.createFbsMap(fbb, keyTypeOff, valueTypeOff, keysSorted, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsMap, inner);
            }
            case DType.Extension e -> {
                int idOff = fbb.createString(e.extensionId());
                int storageDtypeOff = serializeDType(fbb, e.storageDType());
                // Always present, empty when there is none: Rust refuses an extension dtype whose
                // metadata field is absent ("failed to parse extension metadata"), failing the file.
                int metaOff = FbsExtension.createMetadataVector(fbb, metadataBytes(e));
                int inner = FbsExtension.createFbsExtension(fbb, idOff, storageDtypeOff, metaOff);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsExtension, inner);
            }
            case DType.Variant(var nullable) -> {
                int inner = FbsVariant.createFbsVariant(fbb, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsVariant, inner);
            }
            case DType.Union(var names, var variantTypes, var typeIds, var nullable) -> {
                int[] typeOffsets = new int[variantTypes.size()];
                for (int i = 0; i < typeOffsets.length; i++) {
                    typeOffsets[i] = serializeDType(fbb, variantTypes.get(i));
                }
                int[] nameOffsets = new int[names.size()];
                for (int i = 0; i < nameOffsets.length; i++) {
                    nameOffsets[i] = fbb.createString(names.get(i).value());
                }
                byte[] ids = new byte[typeIds.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = typeIds.get(i).byteValue();
                }
                int inner = FbsUnion.createFbsUnion(fbb, FbsUnion.createNamesVector(fbb, nameOffsets),
                        FbsUnion.createDtypesVector(fbb, typeOffsets), FbsUnion.createTypeIdsVector(fbb, ids), nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsUnion, inner);
            }
            default -> throw new UnsupportedOperationException("unsupported DType: " + dtype);
        };
    }
}
