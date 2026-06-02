package io.github.dfa1.vortex.encoding;

/// Input data for {@link DateTimePartsEncoding} encoder.
///
/// @param timestamps raw i64 timestamps (number of time units since Unix epoch)
/// @param nullable   whether the array has a validity (null) dimension
public record DateTimePartsData(long[] timestamps, boolean nullable) {
}
