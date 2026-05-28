package io.github.dfa1.vortex.encoding;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Strongly-typed encoding identifier used in place of raw strings.
public enum EncodingId {
	VORTEX_PRIMITIVE("vortex.primitive"),
	VORTEX_BOOL("vortex.bool"),
	VORTEX_DICT("vortex.dict"),
	VORTEX_SPARSE("vortex.sparse"),
	VORTEX_SEQUENCE("vortex.sequence"),
	VORTEX_RUNEND("vortex.runend"),
	VORTEX_CONSTANT("vortex.constant"),
	VORTEX_ALP("vortex.alp"),
	VORTEX_BITPACKED("vortex.bitpacked"),
	VORTEX_VARBIN("vortex.varbin"),
	VORTEX_NULL("vortex.null"),

	// Layout encoding IDs included so parser/registry can represent them safely
	VORTEX_FLAT("vortex.flat"),
	VORTEX_CHUNKED("vortex.chunked"),
	VORTEX_STRUCT("vortex.struct"),
	VORTEX_STATS("vortex.stats"),

	FASTLANES_BITPACKED("fastlanes.bitpacked"),
	FASTLANES_FOR("fastlanes.for"),
	FASTLANES_DELTA("fastlanes.delta");

	// O(1) access to EncodingId by its string representation
	private static final Map<String, EncodingId> LOOKUP = Stream.of(EncodingId.values())
			.collect(Collectors.toUnmodifiableMap(EncodingId::id, Function.identity()));
	private final String id;

	EncodingId(String id) {
		this.id = id;
	}

	public static EncodingId from(String id) {
		EncodingId result = LOOKUP.get(id);
		if (result == null) {
			throw new IllegalArgumentException("unknown encoding id: " + id);
		}
		return result;
	}

	public String id() {
		return id;
	}

	@Override
	public String toString() {
		return id;
	}
}
