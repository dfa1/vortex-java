# OSS-Fuzz integration (prepared, not submitted)

The files an [OSS-Fuzz](https://google.github.io/oss-fuzz/) project needs, to copy into
`projects/vortex-java/` of a google/oss-fuzz pull request (#493):

- `project.yaml`: fill in `primary_contact` before submitting.
- `Dockerfile`: the JVM builder image, plus JDK 25 (the image ships 17) and a clone of this repo.
- `build.sh`: builds the `fuzz` module and writes one fuzzer per `@FuzzTest` (reader open, scan,
  filtered scan, metadata blobs; FSST round trip), each seeded with its saved inputs. The
  `integration` targets need the vortex-jni native library and are left out.

Checked locally without Docker: `build.sh` (with a local JDK 25 as `$SRC/jdk25`) packages the
fuzzers, and a generated wrapper ran `scanMalformedFile` 20,000 times on Jazzer 0.30's standalone
driver. Not yet run through OSS-Fuzz's own tooling; before submitting, from a google/oss-fuzz
checkout with these files in `projects/vortex-java/`:

```bash
python3 infra/helper.py build_image vortex-java
python3 infra/helper.py build_fuzzers vortex-java
python3 infra/helper.py check_build vortex-java
python3 infra/helper.py run_fuzzer vortex-java VortexReaderFuzzTest_scanMalformedFile
```

OSS-Fuzz accepts projects that are widely used or critical infrastructure; the weekly `Fuzz`
GitHub workflow covers the same targets meanwhile.
