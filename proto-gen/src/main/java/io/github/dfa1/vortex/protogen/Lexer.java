package io.github.dfa1.vortex.protogen;

import java.util.ArrayList;
import java.util.List;

/// Tokenizes a proto3 source file into a list of [Token]s.
/// Comments (`//` line, `/* ... */` block) and whitespace are dropped.
public final class Lexer {

    private final String src;
    private int pos;
    private int line;

    /// @param src raw `.proto` source text
    public Lexer(String src) {
        this.src = src;
        this.pos = 0;
        this.line = 1;
    }

    /// Lex the entire source and return the token stream, terminated by a single `EOF`.
    /// @return immutable token list
    public List<Token> tokenize() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                out.add(new Token(Token.Kind.EOF, "", line));
                return List.copyOf(out);
            }
            char c = src.charAt(pos);
            if (isIdentStart(c)) {
                out.add(readIdent());
            } else if (Character.isDigit(c)) {
                out.add(readNumber());
            } else if (c == '"') {
                out.add(readString());
            } else {
                out.add(readPunct());
            }
        }
    }

    private Token readIdent() {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        return new Token(Token.Kind.IDENT, src.substring(start, pos), line);
    }

    private Token readNumber() {
        int start = pos;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            pos++;
        }
        return new Token(Token.Kind.INT_LITERAL, src.substring(start, pos), line);
    }

    private Token readString() {
        int startLine = line;
        pos++; // skip opening "
        StringBuilder sb = new StringBuilder();
        while (pos < src.length() && src.charAt(pos) != '"') {
            char c = src.charAt(pos++);
            if (c == '\\' && pos < src.length()) {
                char esc = src.charAt(pos++);
                sb.append(switch (esc) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> esc;
                });
            } else {
                if (c == '\n') {
                    line++;
                }
                sb.append(c);
            }
        }
        if (pos >= src.length()) {
            throw new ProtoParseException("unterminated string at line " + startLine);
        }
        pos++; // skip closing "
        return new Token(Token.Kind.STRING_LITERAL, sb.toString(), startLine);
    }

    private Token readPunct() {
        char c = src.charAt(pos++);
        Token.Kind kind = switch (c) {
            case '{' -> Token.Kind.LBRACE;
            case '}' -> Token.Kind.RBRACE;
            case '(' -> Token.Kind.LPAREN;
            case ')' -> Token.Kind.RPAREN;
            case '[' -> Token.Kind.LBRACKET;
            case ']' -> Token.Kind.RBRACKET;
            case '<' -> Token.Kind.LT;
            case '>' -> Token.Kind.GT;
            case ';' -> Token.Kind.SEMICOLON;
            case ',' -> Token.Kind.COMMA;
            case '=' -> Token.Kind.EQUALS;
            case '.' -> Token.Kind.DOT;
            default -> throw new ProtoParseException("unexpected character '" + c + "' at line " + line);
        };
        return new Token(kind, String.valueOf(c), line);
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '\n') {
                line++;
                pos++;
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '/') {
                pos += 2;
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (c == '/' && pos + 1 < src.length() && src.charAt(pos + 1) == '*') {
                pos += 2;
                while (pos + 1 < src.length() && !(src.charAt(pos) == '*' && src.charAt(pos + 1) == '/')) {
                    if (src.charAt(pos) == '\n') {
                        line++;
                    }
                    pos++;
                }
                if (pos + 1 >= src.length()) {
                    throw new ProtoParseException("unterminated block comment");
                }
                pos += 2;
            } else {
                return;
            }
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
