package io.github.dfa1.vortex.encoding;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/// Registry mapping encoding IDs to [Decoder] implementations.
public final class DecoderRegistry {

    private final Map<String, Decoder> decoders = new HashMap<>();

    private DecoderRegistry() {}

    /// Load all [Decoder]s registered via `ServiceLoader`.
    public static DecoderRegistry loadAll() {
        var registry = new DecoderRegistry();
		for (Decoder d : ServiceLoader.load(Decoder.class)) {
		    registry.register(d.encodingId(), d);
		}
		return registry;
    }

    public static DecoderRegistry empty() {
        return new DecoderRegistry();
    }

    public void register(String id, Decoder decoder) {
        decoders.put(id, decoder);
    }

    public void register(Decoder decoder) {
        decoders.put(decoder.encodingId(), decoder);
    }

    public Array decode(DecodeContext ctx)  {
        String id = ctx.node().encodingId();
        Decoder d = decoders.get(id);
        if (d == null) throw new IllegalArgumentException("no decoder for encoding: " + id);
        return d.decode(ctx);
    }

    public boolean has(String id) { return decoders.containsKey(id); }
}
