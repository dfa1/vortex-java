# OSS-Fuzz builder for vortex-java (#493). The base image's JDK is 17; the project needs 25, which
# build.sh uses to build and ships next to the fuzzers for them to run on.
FROM gcr.io/oss-fuzz-base/base-builder-jvm
RUN curl -sSL -o jdk.tar.gz \
        "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse" && \
    mkdir $SRC/jdk25 && tar -xzf jdk.tar.gz -C $SRC/jdk25 --strip-components=1 && rm jdk.tar.gz
RUN git clone --depth 1 https://github.com/dfa1/vortex-java vortex-java
COPY build.sh $SRC/
WORKDIR $SRC/vortex-java
