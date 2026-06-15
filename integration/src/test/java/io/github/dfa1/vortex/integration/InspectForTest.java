package io.github.dfa1.vortex.integration;


import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InspectForTest {
    @Disabled("requires /tmp/for.vortex and /tmp/rle.vortex to be manually downloaded")
    @Test
    void inspect() throws Exception {
        for (String f : new String[]{"/tmp/for.vortex", "/tmp/rle.vortex"}) {
            try (VortexReader r = VortexReader.open(Path.of(f), ReadRegistry.loadAll())) {
                String report = VortexInspector.inspect(r);
                assertThat(report).isNotEmpty();
                try (var iter = r.scan(ScanOptions.all())) {
                    if (iter.hasNext()) {
                        try (var chunk = iter.next()) {
                            assertThat(chunk.columns()).isNotEmpty();
                        }
                    }
                }
            }
        }
    }
}
