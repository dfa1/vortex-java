package io.github.dfa1.vortex.protogen;

/// A token emitted by {@link Lexer}.
///
/// @param kind  token kind
/// @param text  source text — meaningful for {@link Kind#IDENT}, {@link Kind#INT_LITERAL}, {@link Kind#STRING_LITERAL}
/// @param line  1-based source line number for diagnostics
public record Token(Kind kind, String text, int line) {

    /// Token classification.
    public enum Kind {
        IDENT,
        INT_LITERAL,
        STRING_LITERAL,
        LBRACE,
        RBRACE,
        LPAREN,
        RPAREN,
        LBRACKET,
        RBRACKET,
        LT,
        GT,
        SEMICOLON,
        COMMA,
        EQUALS,
        DOT,
        EOF
    }
}
