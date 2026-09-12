package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Parses one `name:type:generator(args)` column descriptor string, e.g.
/// `"price:f64:range(50,150)"` or `"active:bool:bool()"`.
///
/// Grammar:
/// ```
/// descriptor ::= name ":" type ":" generator
/// type       ::= "i8" | "i16" | "i32" | "i64" | "u8" | "u16" | "u32" | "u64"
///              | "f32" | "f64" | "utf8" | "bool"
/// generator  ::= "series(" start "," step ")"
///              | "range(" min "," max ")"
///              | "normal(" mean "," stddev ")"
///              | "enum(" prefix "," count ")"
///              | "constant(" literal ")"
///              | "bool()"
/// ```
public final class DescriptorParser {

    private static final Pattern DESCRIPTOR =
            Pattern.compile("^([^:]+):([a-zA-Z0-9]+):([a-zA-Z]+)\\(([^)]*)\\)$");

    private DescriptorParser() {
    }

    /// Parses one column descriptor string.
    ///
    /// @param descriptor the raw descriptor, e.g. `"symbol:utf8:enum(SYM,30)"`
    /// @return the parsed column descriptor
    /// @throws IllegalArgumentException if `descriptor` doesn't match the grammar, names an
    ///                                  unknown type or generator, or the generator's arguments
    ///                                  don't fit that column's type
    public static ColumnDescriptor parse(String descriptor) {
        Matcher m = DESCRIPTOR.matcher(descriptor.strip());
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "malformed column descriptor '%s' -- expected name:type:generator(args)".formatted(descriptor));
        }
        ColumnName name = ColumnName.of(m.group(1));
        DType dtype = parseType(m.group(2), descriptor);
        List<String> args = splitArgs(m.group(4));
        GeneratorSpec generator = parseGenerator(m.group(3), args, dtype, descriptor);
        return new ColumnDescriptor(name, dtype, generator);
    }

    private static DType parseType(String type, String descriptor) {
        return switch (type) {
            case "i8" -> DType.I8;
            case "i16" -> DType.I16;
            case "i32" -> DType.I32;
            case "i64" -> DType.I64;
            case "u8" -> DType.U8;
            case "u16" -> DType.U16;
            case "u32" -> DType.U32;
            case "u64" -> DType.U64;
            case "f32" -> DType.F32;
            case "f64" -> DType.F64;
            case "utf8" -> DType.UTF8;
            case "bool" -> DType.BOOL;
            default -> throw new IllegalArgumentException(
                    "unknown type '%s' in descriptor '%s'".formatted(type, descriptor));
        };
    }

    private static GeneratorSpec parseGenerator(String function, List<String> args, DType dtype, String descriptor) {
        GeneratorSpec generator = switch (function) {
            case "series" -> new GeneratorSpec.Series(argAsDouble(args, 0, descriptor), argAsDouble(args, 1, descriptor));
            case "range" -> new GeneratorSpec.Range(argAsDouble(args, 0, descriptor), argAsDouble(args, 1, descriptor));
            case "normal" -> new GeneratorSpec.Normal(argAsDouble(args, 0, descriptor), argAsDouble(args, 1, descriptor));
            case "enum" -> new GeneratorSpec.EnumLabels(args.get(0), (int) argAsDouble(args, 1, descriptor));
            case "constant" -> new GeneratorSpec.Constant(args.isEmpty() ? "" : args.get(0));
            case "bool" -> new GeneratorSpec.RandomBool();
            default -> throw new IllegalArgumentException(
                    "unknown generator '%s' in descriptor '%s'".formatted(function, descriptor));
        };
        ColumnMaterializer.validateCompatible(dtype, generator, descriptor);
        return generator;
    }

    private static double argAsDouble(List<String> args, int index, String descriptor) {
        if (index >= args.size()) {
            throw new IllegalArgumentException(
                    "generator in descriptor '%s' needs at least %d argument(s)".formatted(descriptor, index + 1));
        }
        try {
            return Double.parseDouble(args.get(index).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "argument '%s' in descriptor '%s' is not a number".formatted(args.get(index), descriptor), e);
        }
    }

    private static List<String> splitArgs(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(trimmed.split(",", -1)).map(String::strip).toList();
    }
}
