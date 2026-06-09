package io.github.dfa1.vortex.integration;


import io.github.dfa1.vortex.encoding.Registry;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.io.VortexReader;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class InspectForTest {
    @Disabled("requires /tmp/for.vortex and /tmp/rle.vortex to be manually downloaded")
    @Test
    void inspect() throws Exception {
        for (String f : new String[]{"/tmp/for.vortex", "/tmp/rle.vortex"}) {
            try (VortexReader r = VortexReader.open(Path.of(f), Registry.loadAll())) {
                System.out.println("=== " + f + " ===");
                System.out.println(VortexInspector.inspect(r));
                try (var iter = r.scan(io.github.dfa1.vortex.scan.ScanOptions.all())) {
                    if (iter.hasNext()) {
                        try (var chunk = iter.next()) {
                            chunk.columns().forEach((name, arr) ->
                                    System.out.println("  col=" + name + " type=" + arr.getClass().getSimpleName() + " dtype=" + arr.dtype()));
                        }
                    }
                }
            }
        }
    }
}
