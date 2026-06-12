package io.github.dfa1.vortex.writer.encode;

/// Input data for DateTimePartsEncodingEncoder input data.
///
/// @param timestamps raw i64 timestamps (number of time units since Unix epoch)
/// @param nullable   whether the array has a validity (null) dimension
public record DateTimePartsData(long[] timestamps, boolean nullable) {
}
