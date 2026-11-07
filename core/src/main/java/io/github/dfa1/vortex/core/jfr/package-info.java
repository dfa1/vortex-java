/// JDK Flight Recorder events emitted by the reader and the writer.
///
/// Each event is a public class extending `jdk.jfr.Event`, named `io.github.dfa1.vortex.<Name>`.
/// With recording off, emitting one costs a field read.
package io.github.dfa1.vortex.core.jfr;
