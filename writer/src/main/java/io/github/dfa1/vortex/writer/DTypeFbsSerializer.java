package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.fbs.FbsBinary;
import io.github.dfa1.vortex.core.fbs.FbsBool;
import io.github.dfa1.vortex.core.fbs.FbsBuilder;
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
import io.github.dfa1.vortex.core.fbs.FbsVariant;
import io.github.dfa1.vortex.core.model.DType;

import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/// Serializes a [DType] tree to its FlatBuffer wire encoding (the file's DType blob, pointed to
/// by the postscript).
final class DTypeFbsSerializer {

    private DTypeFbsSerializer() {
    }

    /// Serializes `dtype` to a standalone, finished FlatBuffer.
    ///
    /// @param dtype the schema (or nested type) to serialize
    /// @return the finished FlatBuffer bytes, little-endian
    static ByteBuffer buildDType(DType dtype) {
        var fbb = new FbsBuilder(128);
        int off = serializeDType(fbb, dtype);
        FbsDType.finishFbsDTypeBuffer(fbb, off);
        return fbb.dataSegment().asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /// Serializes `dtype` into `fbb`, returning the offset of the resulting `FbsDType` table.
    /// Recurses into nested types (struct fields, list/map elements, extension storage) bottom-up,
    /// as FlatBuffers construction requires.
    ///
    /// @param fbb   the builder to serialize into
    /// @param dtype the type to serialize
    /// @return the offset of the written `FbsDType` table
    static int serializeDType(FbsBuilder fbb, DType dtype) {
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
                int metaOff = 0;
                if (e.metadata() != null) {
                    byte[] metaBytes = e.metadata().toArray(ValueLayout.JAVA_BYTE);
                    metaOff = FbsExtension.createMetadataVector(fbb, metaBytes);
                }
                int inner = FbsExtension.createFbsExtension(fbb, idOff, storageDtypeOff, metaOff);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsExtension, inner);
            }
            case DType.Variant(var nullable) -> {
                int inner = FbsVariant.createFbsVariant(fbb, nullable);
                yield FbsDType.createFbsDType(fbb, FbsType.FbsVariant, inner);
            }
            default -> throw new UnsupportedOperationException("unsupported DType: " + dtype);
        };
    }
}
