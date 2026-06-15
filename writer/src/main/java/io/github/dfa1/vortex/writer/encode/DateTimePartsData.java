package io.github.dfa1.vortex.writer.encode;

/// Input data for DateTimePartsEncodingEncoder input data.
///
/// @param timestamps raw i64 timestamps (number of time units since Unix epoch)
/// @param nullable   whether the array has a validity (null) dimension
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record DateTimePartsData(long[] timestamps, boolean nullable) {
}
