package io.github.dfa1.vortex.cli.tui.term;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyTest {

    @Nested
    class CharRecord {

        @Test
        void exposesValue() {
            // Given / When
            Key.Char sut = new Key.Char('a');

            // Then
            assertThat(sut.value()).isEqualTo('a');
        }

        @Test
        void equalsOnValue() {
            // Given / When / Then — record equality must consider the codepoint,
            // not identity, so KeyDecoderTest assertions on Char values are stable.
            assertThat(new Key.Char('x')).isEqualTo(new Key.Char('x'));
            assertThat(new Key.Char('x')).isNotEqualTo(new Key.Char('y'));
        }
    }

    @Nested
    class SingletonEnums {

        @Test
        void allArrowsAreSealedKeyVariants() {
            // Given / When / Then — every arrow is an instance of the sealed Key interface.
            // Guards against accidentally moving a variant out of the permitted set.
            assertThat(Key.ArrowUp.INSTANCE).isInstanceOf(Key.class);
            assertThat(Key.ArrowDown.INSTANCE).isInstanceOf(Key.class);
            assertThat(Key.ArrowLeft.INSTANCE).isInstanceOf(Key.class);
            assertThat(Key.ArrowRight.INSTANCE).isInstanceOf(Key.class);
        }

        @Test
        void navigationKeysAreDistinct() {
            // Given / When / Then — each singleton must be its own type;
            // a switch in TUI code dispatches by identity-equivalent enum match.
            assertThat(Key.PageUp.INSTANCE).isNotSameAs(Key.PageDown.INSTANCE);
            assertThat(Key.Home.INSTANCE).isNotSameAs(Key.End.INSTANCE);
            assertThat(Key.Enter.INSTANCE).isNotSameAs(Key.Escape.INSTANCE);
            assertThat(Key.Eof.INSTANCE).isNotSameAs(Key.Escape.INSTANCE);
        }
    }

    @Nested
    class PatternMatching {

        @Test
        void sealedSwitchHandlesEveryVariant() {
            // Given / When / Then — exhaustive pattern switch compiles only because
            // every Key variant is permitted; this test would fail to compile if a
            // new variant were added without updating the switch.
            assertThat(label(Key.ArrowUp.INSTANCE)).isEqualTo("up");
            assertThat(label(Key.Enter.INSTANCE)).isEqualTo("enter");
            assertThat(label(new Key.Char('z'))).isEqualTo("char:z");
            assertThat(label(Key.Eof.INSTANCE)).isEqualTo("eof");
        }

        private static String label(Key key) {
            return switch (key) {
                case Key.ArrowUp _ -> "up";
                case Key.ArrowDown _ -> "down";
                case Key.ArrowLeft _ -> "left";
                case Key.ArrowRight _ -> "right";
                case Key.PageUp _ -> "pgup";
                case Key.PageDown _ -> "pgdn";
                case Key.Home _ -> "home";
                case Key.End _ -> "end";
                case Key.Enter _ -> "enter";
                case Key.Escape _ -> "esc";
                case Key.Eof _ -> "eof";
                case Key.Char(var c) -> "char:" + c;
            };
        }
    }
}
