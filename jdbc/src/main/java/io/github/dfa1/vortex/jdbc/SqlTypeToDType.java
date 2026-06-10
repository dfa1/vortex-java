package io.github.dfa1.vortex.jdbc;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.extension.DateExtension;
import io.github.dfa1.vortex.extension.TimeExtension;
import io.github.dfa1.vortex.extension.TimestampExtension;
import io.github.dfa1.vortex.extension.UuidExtension;

import java.sql.Types;

final class SqlTypeToDType {

    private SqlTypeToDType() {
    }

    static DType map(int sqlType, String typeName, boolean nullable) {
        // JDBC has no Types.UUID constant; drivers expose UUID columns under varying
        // sqlType values (PostgreSQL Types.OTHER, H2 Types.BINARY or a driver-private
        // value). The vendor type name is "uuid" / "UUID" across both — detect on that.
        if (typeName != null && typeName.equalsIgnoreCase("uuid")) {
            return UuidExtension.INSTANCE.dtype(nullable);
        }
        return switch (sqlType) {
            case Types.BIGINT -> new DType.Primitive(PType.I64, nullable);
            case Types.INTEGER -> new DType.Primitive(PType.I32, nullable);
            case Types.SMALLINT -> new DType.Primitive(PType.I16, nullable);
            case Types.TINYINT -> new DType.Primitive(PType.I8, nullable);
            case Types.DOUBLE, Types.DECIMAL, Types.NUMERIC -> new DType.Primitive(PType.F64, nullable);
            case Types.REAL, Types.FLOAT -> new DType.Primitive(PType.F32, nullable);
            case Types.BOOLEAN, Types.BIT -> new DType.Bool(nullable);
            case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR,
                 Types.NVARCHAR, Types.NCHAR, Types.LONGNVARCHAR, Types.CLOB -> new DType.Utf8(nullable);
            case Types.DATE -> DateExtension.INSTANCE.dtype(nullable);
            case Types.TIME -> TimeExtension.INSTANCE.dtype(nullable);
            case Types.TIMESTAMP -> TimestampExtension.INSTANCE.dtype(nullable);
            default -> throw new UnsupportedOperationException("unsupported SQL type: " + sqlType + " (" + typeName + ")");
        };
    }
}
