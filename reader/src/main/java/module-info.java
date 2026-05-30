module io.github.dfa1.vortex.reader {
    requires io.github.dfa1.vortex.core;
    requires com.google.protobuf;
    requires flatbuffers.java;
    requires java.net.http;

    exports io.github.dfa1.vortex.io;
    exports io.github.dfa1.vortex.scan;
}
