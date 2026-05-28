package io.github.dfa1.vortex.scan;

import java.util.List;

/// Options controlling a file scan.
///
/// Empty `columns` = read all columns.
/// Null `rowFilter` = no zone-map pruning.
public record ScanOptions(
		List<String> columns,
		RowFilter rowFilter,
		long limit
) {
	public static final long NO_LIMIT = Long.MAX_VALUE;

	public static ScanOptions all() {
		return new ScanOptions(List.of(), null, NO_LIMIT);
	}

	public static ScanOptions columns(String... names) {
		return new ScanOptions(List.of(names), null, NO_LIMIT);
	}

	public boolean hasProjection() {
		return !columns.isEmpty();
	}

	public boolean hasFilter() {
		return rowFilter != null;
	}

	public boolean hasLimit() {
		return limit != NO_LIMIT;
	}
}
