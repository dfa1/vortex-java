#!/bin/bash -eu
# OSS-Fuzz build for vortex-java (#493): every JUnit @FuzzTest of the `fuzz` module becomes one
# fuzzer, run by Jazzer's driver with --target_class/--target_method, seeded with its saved inputs.
# The `integration` targets are left out: they need the vortex-jni native library.

export JAVA_HOME=$SRC/jdk25
export PATH=$JAVA_HOME/bin:$PATH

# One reactor invocation: the sibling modules are packaged, never installed, so copy-dependencies
# must resolve them from this same build. Jazzer comes from OSS-Fuzz, not Maven.
./mvnw -B -q package dependency:copy-dependencies -pl fuzz -am -DskipTests \
    -DincludeScope=test -DexcludeGroupIds=com.code-intelligence -DoutputDirectory="$OUT/libs"
cp -r fuzz/target/test-classes "$OUT/classes"
cp -r "$JAVA_HOME" "$OUT/jdk"

INPUTS=fuzz/src/test/resources/io/github/dfa1/vortex/fuzz
for target in \
    VortexReaderFuzzTest:openMalformedFile \
    VortexReaderFuzzTest:scanMalformedFile \
    VortexReaderFuzzTest:filterMalformedFile \
    VortexReaderFuzzTest:parseMetadataBlobs \
    FsstFuzzTest:compressDecompressRoundTrip; do
  class=${target%%:*}
  method=${target##*:}
  fuzzer=${class}_${method}

  if compgen -G "$INPUTS/${class}Inputs/$method/*" > /dev/null; then
    zip -jq "$OUT/${fuzzer}_seed_corpus.zip" "$INPUTS/${class}Inputs/$method"/*
  fi

  # The JDK 25 shipped above runs the fuzzer; OSS-Fuzz's own jazzer jars are on the classpath.
  cat > "$OUT/$fuzzer" <<WRAPPER
#!/bin/bash
# LLVMFuzzerTestOneInput for fuzzer detection.
this_dir=\$(dirname "\$0")
JAVA_HOME=\$this_dir/jdk \$this_dir/jdk/bin/java \\
    -cp "\$this_dir/classes:\$this_dir/jazzer_agent_deploy.jar:\$this_dir/jazzer_junit.jar:\$this_dir/libs/*" \\
    com.code_intelligence.jazzer.Jazzer \\
    --target_class=io.github.dfa1.vortex.fuzz.$class \\
    --target_method=$method \\
    --instrumentation_includes='io.github.dfa1.vortex.**' \\
    "\$@"
WRAPPER
  chmod +x "$OUT/$fuzzer"
done
