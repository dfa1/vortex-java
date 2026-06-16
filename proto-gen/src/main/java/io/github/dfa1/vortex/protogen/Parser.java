package io.github.dfa1.vortex.protogen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Recursive-descent parser for the proto3 subset used by vortex.
/// Accepts: `syntax`, `package`, `import`, `option` (only `java_package`),
/// `message`, `enum`, `oneof`, `repeated`, `optional`, and the proto3 scalar types
/// `uint32`, `uint64`, `sint64`, `int32`, `bool`, `float`, `double`,
/// `string`, `bytes`, plus user-declared message/enum refs.
/// Rejects: `service`, `rpc`, `extend`, `reserved`, `map<,>`, `editions`, custom options.
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    /// @param tokens token stream produced by {@link Lexer}
    public Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /// Parses one `.proto` file.
    /// @return the parsed file AST
    public Ast.ProtoFile parseFile() {
        String protoPackage = "";
        String javaPackage = "";
        List<String> imports = new ArrayList<>();
        List<Ast.TopDecl> decls = new ArrayList<>();

        // syntax = "proto3"; — required
        expectIdent("syntax");
        expect(Token.Kind.EQUALS);
        Token syn = expect(Token.Kind.STRING_LITERAL);
        if (!"proto3".equals(syn.text())) {
            throw new ProtoParseException("only proto3 is supported, got '" + syn.text() + "' at line " + syn.line());
        }
        expect(Token.Kind.SEMICOLON);

        while (peek().kind() != Token.Kind.EOF) {
            Token t = peek();
            if (t.kind() == Token.Kind.IDENT) {
                switch (t.text()) {
                    case "package" -> protoPackage = parsePackage();
                    case "import" -> imports.add(parseImport());
                    case "option" -> {
                        OptionEntry opt = parseOption();
                        if ("java_package".equals(opt.name())) {
                            javaPackage = opt.value();
                        }
                    }
                    case "message" -> decls.add(parseMessage());
                    case "enum" -> decls.add(parseEnum());
                    default -> throw new ProtoParseException("unexpected top-level token '" + t.text() + "' at line " + t.line());
                }
            } else {
                throw new ProtoParseException("expected top-level declaration at line " + t.line() + ", got " + t.kind());
            }
        }
        return new Ast.ProtoFile(protoPackage, javaPackage, List.copyOf(imports), List.copyOf(decls));
    }

    private String parsePackage() {
        expectIdent("package");
        StringBuilder sb = new StringBuilder(expect(Token.Kind.IDENT).text());
        while (peek().kind() == Token.Kind.DOT) {
            advance();
            sb.append('.').append(expect(Token.Kind.IDENT).text());
        }
        expect(Token.Kind.SEMICOLON);
        return sb.toString();
    }

    private String parseImport() {
        expectIdent("import");
        Token path = expect(Token.Kind.STRING_LITERAL);
        expect(Token.Kind.SEMICOLON);
        return path.text();
    }

    private OptionEntry parseOption() {
        expectIdent("option");
        String name = expect(Token.Kind.IDENT).text();
        while (peek().kind() == Token.Kind.DOT) {
            advance();
            name += "." + expect(Token.Kind.IDENT).text();
        }
        expect(Token.Kind.EQUALS);
        String value;
        Token v = peek();
        if (v.kind() == Token.Kind.STRING_LITERAL) {
            value = advance().text();
        } else if (v.kind() == Token.Kind.IDENT || v.kind() == Token.Kind.INT_LITERAL) {
            value = advance().text();
        } else {
            throw new ProtoParseException("expected option value at line " + v.line());
        }
        expect(Token.Kind.SEMICOLON);
        return new OptionEntry(name, value);
    }

    private Ast.MessageDecl parseMessage() {
        expectIdent("message");
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.LBRACE);
        List<Ast.MessageMember> members = new ArrayList<>();
        while (peek().kind() != Token.Kind.RBRACE) {
            Token t = peek();
            if (t.kind() != Token.Kind.IDENT) {
                throw new ProtoParseException("expected message member at line " + t.line() + ", got " + t.kind());
            }
            switch (t.text()) {
                case "message" -> members.add(parseMessage());
                case "enum" -> members.add(parseEnum());
                case "oneof" -> members.add(parseOneOf());
                case "reserved" -> skipReserved();
                case "option" -> parseOption(); // ignored inside messages
                default -> members.add(parseField());
            }
        }
        expect(Token.Kind.RBRACE);
        return new Ast.MessageDecl(name, List.copyOf(members));
    }

    private Ast.OneOfDecl parseOneOf() {
        expectIdent("oneof");
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.LBRACE);
        List<Ast.FieldDecl> fields = new ArrayList<>();
        while (peek().kind() != Token.Kind.RBRACE) {
            // oneof members are always SINGLE; no `optional` or `repeated` keyword.
            fields.add(parseFieldBody(Ast.Rule.SINGLE));
        }
        expect(Token.Kind.RBRACE);
        return new Ast.OneOfDecl(name, List.copyOf(fields));
    }

    private Ast.FieldDecl parseField() {
        Token first = peek();
        Ast.Rule rule = switch (first.text()) {
            case "repeated" -> {
                advance();
                yield Ast.Rule.REPEATED;
            }
            case "optional" -> {
                advance();
                yield Ast.Rule.OPTIONAL;
            }
            default -> Ast.Rule.SINGLE;
        };
        return parseFieldBody(rule);
    }

    private Ast.FieldDecl parseFieldBody(Ast.Rule rule) {
        Ast.FieldType type = parseFieldType();
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.EQUALS);
        int number = Integer.parseInt(expect(Token.Kind.INT_LITERAL).text());
        Optional<Boolean> packed = Optional.empty();
        if (peek().kind() == Token.Kind.LBRACKET) {
            advance();
            while (peek().kind() != Token.Kind.RBRACKET) {
                String key = expect(Token.Kind.IDENT).text();
                expect(Token.Kind.EQUALS);
                String val = advance().text();
                if ("packed".equals(key)) {
                    packed = Optional.of(Boolean.parseBoolean(val));
                }
                if (peek().kind() == Token.Kind.COMMA) {
                    advance();
                }
            }
            expect(Token.Kind.RBRACKET);
        }
        expect(Token.Kind.SEMICOLON);
        return new Ast.FieldDecl(rule, type, name, number, packed);
    }

    private Ast.FieldType parseFieldType() {
        Token t = expect(Token.Kind.IDENT);
        Ast.Scalar scalar = switch (t.text()) {
            case "uint32" -> Ast.Scalar.UINT32;
            case "uint64" -> Ast.Scalar.UINT64;
            case "sint64" -> Ast.Scalar.SINT64;
            case "int32" -> Ast.Scalar.INT32;
            case "bool" -> Ast.Scalar.BOOL;
            case "float" -> Ast.Scalar.FLOAT;
            case "double" -> Ast.Scalar.DOUBLE;
            case "string" -> Ast.Scalar.STRING;
            case "bytes" -> Ast.Scalar.BYTES;
            default -> null;
        };
        if (scalar != null) {
            return scalar;
        }
        // Qualified or unqualified type reference.
        StringBuilder sb = new StringBuilder(t.text());
        while (peek().kind() == Token.Kind.DOT) {
            advance();
            sb.append('.').append(expect(Token.Kind.IDENT).text());
        }
        return new Ast.Ref(sb.toString());
    }

    private Ast.EnumDecl parseEnum() {
        expectIdent("enum");
        String name = expect(Token.Kind.IDENT).text();
        expect(Token.Kind.LBRACE);
        List<Ast.EnumValue> values = new ArrayList<>();
        while (peek().kind() != Token.Kind.RBRACE) {
            Token t = peek();
            if (t.kind() == Token.Kind.IDENT && "option".equals(t.text())) {
                parseOption();
                continue;
            }
            if (t.kind() == Token.Kind.IDENT && "reserved".equals(t.text())) {
                skipReserved();
                continue;
            }
            String vn = expect(Token.Kind.IDENT).text();
            expect(Token.Kind.EQUALS);
            int num = Integer.parseInt(expect(Token.Kind.INT_LITERAL).text());
            if (peek().kind() == Token.Kind.LBRACKET) {
                while (advance().kind() != Token.Kind.RBRACKET) {
                    // discard enum value options
                }
            }
            expect(Token.Kind.SEMICOLON);
            values.add(new Ast.EnumValue(vn, num));
        }
        expect(Token.Kind.RBRACE);
        return new Ast.EnumDecl(name, List.copyOf(values));
    }

    private void skipReserved() {
        // reserved <int>[, <int>]* ; OR reserved "field"[, "field"]* ;
        expectIdent("reserved");
        while (peek().kind() != Token.Kind.SEMICOLON) {
            advance();
        }
        expect(Token.Kind.SEMICOLON);
    }

    private Token expect(Token.Kind kind) {
        Token t = peek();
        if (t.kind() != kind) {
            throw new ProtoParseException("expected " + kind + " at line " + t.line() + ", got " + t.kind() + " '" + t.text() + "'");
        }
        return advance();
    }

    private void expectIdent(String text) {
        Token t = expect(Token.Kind.IDENT);
        if (!text.equals(t.text())) {
            throw new ProtoParseException("expected '" + text + "' at line " + t.line() + ", got '" + t.text() + "'");
        }
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token advance() {
        return tokens.get(pos++);
    }

    private record OptionEntry(String name, String value) {
    }
}
