package io.github.dfa1.vortex.fuzz;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import io.github.dfa1.vortex.core.fbs.FbsBuilder;
import io.github.dfa1.vortex.core.fbs.FbsPostscript;
import io.github.dfa1.vortex.core.fbs.FbsPostscriptSegment;
import io.github.dfa1.vortex.core.io.VortexFormat;

/// Moves a Vortex file's three metadata blobs (footer, layout, dtype FlatBuffers) between their
/// place in a file and the flat form [VortexReaderFuzzTest#parseMetadataBlobs(byte[])] fuzzes.
final class MetadataBlobs {

    private MetadataBlobs() {
    }

    /// Lays the blobs `data[from..]` (footer, layout, then dtype) out as a file: the blobs, a
    /// postscript pointing at them, and the trailer.
    ///
    /// @param data         the bytes holding the blobs
    /// @param from         where the footer starts in `data`
    /// @param footerLength footer length
    /// @param layoutLength layout length
    /// @param dtypeLength  dtype length (0 for none)
    /// @return the file's bytes
    static byte[] assemble(byte[] data, int from, int footerLength, int layoutLength, int dtypeLength) {
        int body = footerLength + layoutLength + dtypeLength;
        FbsBuilder builder = new FbsBuilder(256);
        int footer = FbsPostscriptSegment.createFbsPostscriptSegment(builder, 0, footerLength, 0, 0, 0);
        int layout = FbsPostscriptSegment.createFbsPostscriptSegment(builder, footerLength, layoutLength, 0, 0, 0);
        int dtype = FbsPostscriptSegment.createFbsPostscriptSegment(builder, footerLength + layoutLength,
                dtypeLength, 0, 0, 0);
        FbsPostscript.finishFbsPostscriptBuffer(builder, FbsPostscript.createFbsPostscript(builder, dtype, layout, 0, footer));
        byte[] postscript = builder.sizedByteArray();

        ByteBuffer file = ByteBuffer.allocate(body + postscript.length + VortexFormat.TRAILER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        file.put(data, from, body).put(postscript)
                .putShort((short) VortexFormat.VERSION).putShort((short) postscript.length)
                .put(VortexFormat.MAGIC.asByteBuffer());
        return file.array();
    }

    /// The flat fuzz input for a valid file: the blob-length header, then its footer, layout and
    /// dtype blobs as its postscript locates them.
    ///
    /// @param file a Vortex file's bytes
    /// @return the header and the three blobs
    static byte[] extract(byte[] file) {
        ByteBuffer trailer = ByteBuffer.wrap(file, file.length - VortexFormat.TRAILER_SIZE, VortexFormat.TRAILER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN);
        trailer.getShort();
        int postscriptLength = Short.toUnsignedInt(trailer.getShort());
        int postscriptStart = file.length - VortexFormat.TRAILER_SIZE - postscriptLength;
        var postscript = FbsPostscript.getRootAsFbsPostscript(
                java.lang.foreign.MemorySegment.ofArray(file).asSlice(postscriptStart, postscriptLength));
        FbsPostscriptSegment[] segments = {postscript.footer(), postscript.layout(), postscript.dtype()};
        ByteBuffer out = ByteBuffer.allocate(VortexReaderFuzzTest.BLOB_HEADER_BYTES
                + (int) (segments[0].length() + segments[1].length() + segments[2].length()));
        out.putShort((short) segments[0].length()).putShort((short) segments[1].length());
        for (FbsPostscriptSegment segment : segments) {
            out.put(file, (int) segment.offset(), (int) segment.length());
        }
        return out.array();
    }
}
