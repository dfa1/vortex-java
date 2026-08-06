package io.github.dfa1.vortex.reader;

import java.lang.foreign.MemorySegment;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dfa1.vortex.core.error.VortexException;
import org.junit.jupiter.api.Tag;

/// Coverage-guided fuzzing of the postscript/footer/layout/dtype parse boundary (ADR 0020).
/// [PostscriptParser#parseBlobs] already rewraps every [RuntimeException] as a [VortexException],
/// but [Error] subtypes escape uncaught — a crafted FlatBuffer vtable claiming a huge
/// `arraySpecsLength()` / `layoutSpecsLength()` reaches an `ArrayList` constructor before any
/// bounds check runs, so [OutOfMemoryError] and [StackOverflowError] are exactly the class of bug
/// this target hunts for (`37d19637` fixed one of those by hand).
///
/// Tagged `fuzz` so a routine build skips it; run with
/// `./mvnw test -pl reader -am -Dtest=PostscriptParserFuzzTest -Dvortex.reader.excludedGroups=`
/// (add `JAZZER_FUZZ=1` to actually explore new inputs rather than replay the corpus).
@Tag("fuzz")
class PostscriptParserFuzzTest {

    @FuzzTest
    void parseBlobs(byte[] footer, byte[] layout, byte[] dtype) {
        // Jazzer's mutator may generate null for any reference parameter, but parseBlobs never
        // sees a null footer or layout in production — PostscriptParser.parse rejects a postscript
        // missing either segment before reaching here — so fold null into the empty-blob case.
        // MemorySegment.ofArray is deliberate: these are fuzzer-supplied *input* bytes, not decode
        // output, so the arena-allocation rule for decode buffers does not apply.
        MemorySegment footerSeg = MemorySegment.ofArray(footer == null ? new byte[0] : footer);
        MemorySegment layoutSeg = MemorySegment.ofArray(layout == null ? new byte[0] : layout);
        // A null dtype blob is a real production input ("file carries no dtype"); map the empty
        // case onto it so the fuzzer reaches that branch too.
        MemorySegment dtypeSeg = (dtype == null || dtype.length == 0) ? null : MemorySegment.ofArray(dtype);
        try {
            PostscriptParser.parseBlobs(footerSeg, layoutSeg, dtypeSeg);
        } catch (VortexException expected) {
            // Malformed footer/layout/dtype blob correctly rejected.
        }
    }
}
