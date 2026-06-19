package io.github.dfa1.vortex.protogen;

/// A token emitted by [Lexer].
///
/// @param kind  token kind
/// @param text  source text — meaningful for [Kind#IDENT], [Kind#INT_LITERAL], [Kind#STRING_LITERAL]
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
