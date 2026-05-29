package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.VortexException;

import java.util.List;

/// Decoded struct array: holds one [Array] per field, keyed by position.
///
/// <p>Returned by [io.github.dfa1.vortex.encoding.StructEncoding] when decoding a
/// multi-field {@code vortex.struct} segment (all columns packed into one flat).
public final class StructArray implements Array {

	private final DType.Struct dtype;
	private final long length;
	private final List<Array> fields;

	public StructArray(DType.Struct dtype, long length, List<Array> fields) {
		this.dtype = dtype;
		this.length = length;
		this.fields = List.copyOf(fields);
	}

	@Override
	public long length() {
		return length;
	}

	@Override
	public DType dtype() {
		return dtype;
	}

	@Override
	public ArrayStats stats() {
		return ArrayStats.empty();
	}

	public int fieldCount() {
		return fields.size();
	}

	public Array field(int i) {
		return fields.get(i);
	}

	public Array field(String name) {
		List<String> names = dtype.fieldNames();
		for (int i = 0; i < names.size(); i++) {
			if (names.get(i).equals(name)) {
				return fields.get(i);
			}
		}
		throw new VortexException("no field '" + name + "' in struct");
	}
}
