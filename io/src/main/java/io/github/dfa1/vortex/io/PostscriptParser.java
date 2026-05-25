package io.github.dfa1.vortex.io;

import io.github.dfa1.vortex.core.*;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/**
 * Parses the Vortex postscript (FlatBuffer) into footer, dtype, and layout.
 *
 * <p>Stub — implement once FlatBuffer generated sources are in place.
 * The postscript describes offsets + lengths for the footer, dtype, and layout
 * FlatBuffer/Protobuf blobs within the file.
 *
 * <p>Reference: {@code schemas/flatbuffers/footer.fbs} (Postscript table)
 * <ul>
 *   <li>dtype_seg  — PostscriptSegment → offset+length of Protobuf DType blob</li>
 *   <li>layout_seg — PostscriptSegment → offset+length of FlatBuffer Layout blob</li>
 *   <li>stats_seg  — PostscriptSegment → offset+length of FileStatistics blob</li>
 *   <li>footer_seg — PostscriptSegment → offset+length of Footer FlatBuffer</li>
 * </ul>
 */
final class PostscriptParser {

    record ParsedFile(Footer footer, DType dtype, Layout layout) {}

    static ParsedFile parse(byte[] postscriptBytes, MemorySegment fileSegment) throws IOException {
        throw new UnsupportedOperationException("PostscriptParser not yet implemented");
    }

    private PostscriptParser() {}
}
