package io.github.dfa1.vortex.core.simd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SimdOperationsSupportTest {

    @Test
    void preferred_withTheVectorModulePresent_isTheVectorApiImplementation() {
        // Given the test JVM launched with --add-modules jdk.incubator.vector (the surefire argLine)
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "run without the module");
        assumeTrue(VectorApiSimdOperations.isUsable(), "CPU vectors narrower than 128 bits");

        // When
        SimdOperations result = SimdOperationsSupport.preferred();

        // Then the whole suite exercises the Vector API path, not only its own differential test
        assertThat(result).isInstanceOf(VectorApiSimdOperations.class);
    }

    @Test
    void preferred_withoutTheVectorModule_isTheAutoVectorizedImplementation() {
        // Given a JVM launched without --add-modules jdk.incubator.vector (the without-vector-module pass)
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty(), "run with the module");

        // When
        SimdOperations result = SimdOperationsSupport.preferred();

        // Then the fallback works: the Vector API class is never loaded, so nothing fails to link
        assertThat(result).isInstanceOf(AutoVectorizedSimdOperations.class);
    }

    @Test
    void preferred_isTheSameInstanceEveryCall() {
        // Given / When
        SimdOperations first = SimdOperationsSupport.preferred();
        SimdOperations result = SimdOperationsSupport.preferred();

        // Then
        assertThat(result).isSameAs(first);
    }
}
