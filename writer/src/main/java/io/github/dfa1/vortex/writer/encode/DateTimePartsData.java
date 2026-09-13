package io.github.dfa1.vortex.writer.encode;

import io.github.dfa1.vortex.core.model.PType;

/// Input data for DateTimePartsEncodingEncoder input data.
///
/// Implements [ComparableValues]: the carrier's comparable form for zone-map min/max is the raw
/// pre-split timestamp, not the carrier itself -- signed order matches chronological order
/// regardless of how the encoding later splits it into days/seconds/subseconds.
///
/// @param timestamps raw i64 timestamps (number of time units since Unix epoch)
/// @param nullable   whether the array has a validity (null) dimension
@SuppressWarnings("java:S6218") // internal data carrier; record components are arrays of immutable primitives or refs that flow through pipelines without ever being compared.
public record DateTimePartsData(long[] timestamps, boolean nullable) implements ComparableValues {

    @Override
    public PType ptype() {
        return PType.I64;
    }

    @Override
    public Object values() {
        return timestamps;
    }
}
