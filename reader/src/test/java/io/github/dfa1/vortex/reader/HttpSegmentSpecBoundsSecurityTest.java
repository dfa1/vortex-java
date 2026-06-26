package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.io.VortexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

import static io.github.dfa1.vortex.reader.MalformedFiles.buildFlatLayout;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildFooter;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildI64Dtype;
import static io.github.dfa1.vortex.reader.MalformedFiles.buildPostscript;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/// Pins that the HTTP reader validates footer `segmentSpecs` against the file size, mirroring the
/// local-file path. `VortexHttpReader.open` calls `PostscriptParser.parseBlobs` directly (not
/// `parse`), so it must run `validateSegmentSpecs` itself — otherwise an out-of-bounds remote
/// footer turns into out-of-range HTTP Range requests at scan time instead of a [VortexException].
@ExtendWith(MockitoExtension.class)
class HttpSegmentSpecBoundsSecurityTest {

    @Mock
    private HttpClient client;

    private static final URI URI = java.net.URI.create("http://example.com/oob_segment.vortex");

    @Test
    void open_segmentSpecPastEof_throwsVortexException() throws Exception {
        // Given — a well-formed remote file whose single footer segmentSpec declares an offset far
        // beyond the file, served as a 206 that fits the tail window.
        byte[] file = buildFileWithOobSegment();
        doReturn(response206("bytes 0-" + (file.length - 1) + "/" + file.length, file))
                .when(client).send(any(), any());

        // When / Then
        assertThatThrownBy(() -> VortexHttpReader.open(URI, ReadRegistry.empty(), client))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("out of bounds");
    }

    private static byte[] buildFileWithOobSegment() throws Exception {
        byte[] body = new byte[8]; // unused placeholder
        // Segment offset 2^40 sits far past any real file size, so validateSegmentSpecs must reject.
        ByteBuffer footerBuf = buildFooter(
                new String[]{"vortex.primitive"},
                new String[]{"vortex.flat"},
                new long[]{1L << 40},
                new long[]{0L});
        ByteBuffer dtypeBuf = buildI64Dtype();
        ByteBuffer layoutBuf = buildFlatLayout(0, 1L, 0);

        long footerOff = body.length;
        long dtypeOff = footerOff + footerBuf.remaining();
        long layoutOff = dtypeOff + dtypeBuf.remaining();

        ByteBuffer psBuf = buildPostscript(
                footerOff, footerBuf.remaining(),
                dtypeOff, dtypeBuf.remaining(),
                layoutOff, layoutBuf.remaining());

        int psLen = psBuf.remaining();
        ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putShort((short) 1);
        trailer.putShort((short) psLen);
        trailer.put(VortexFormat.MAGIC.asByteBuffer());
        trailer.flip();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(body);
        writeBuf(out, footerBuf);
        writeBuf(out, dtypeBuf);
        writeBuf(out, layoutBuf);
        writeBuf(out, psBuf);
        out.write(trailer.array());
        return out.toByteArray();
    }

    private static void writeBuf(ByteArrayOutputStream out, ByteBuffer buf) {
        buf = buf.duplicate();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        out.write(bytes, 0, bytes.length);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response206(String contentRange, byte[] body) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return 206;
            }

            @Override
            public byte[] body() {
                return body;
            }

            @Override
            public HttpHeaders headers() {
                Map<String, List<String>> map = contentRange == null
                        ? Map.of()
                        : Map.of("content-range", List.of(contentRange));
                return HttpHeaders.of(map, (k, v) -> true);
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<byte[]>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public java.net.URI uri() {
                return URI;
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
