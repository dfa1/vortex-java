package io.github.dfa1.vortex.encoding;

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

    private final String id;

    EncodingId(String id) {
        this.id = id;
    }

    public String id() { return id; }

    @Override
    public String toString() { return id; }

    public static EncodingId from(String id) {
        for (EncodingId e : values()) {
            if (e.id.equals(id)) return e;
        }
        throw new IllegalArgumentException("unknown encoding id: " + id);
    }
}
