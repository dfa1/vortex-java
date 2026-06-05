# Tutorial: Your first Vortex file

This tutorial walks you through writing and reading a Vortex file from scratch.
You will end up with a working Maven project that stores time-series data in Vortex format
and reads it back column by column.

**Prerequisites:** Java 25+, Maven 3.9+.

---

## 1. Create a Maven project

```bash
mvn archetype:generate \
  -DgroupId=com.example \
  -DartifactId=vortex-demo \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DarchetypeVersion=1.5 \
  -DinteractiveMode=false
cd vortex-demo
```

Add the dependency to `pom.xml` (inside `<dependencies>`):

```xml
<dependency>
  <groupId>io.github.dfa1</groupId>
  <artifactId>vortex-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Set the compiler to Java 25:

```xml
<properties>
  <maven.compiler.release>25</maven.compiler.release>
</properties>
```

---

## 2. Define a schema

A Vortex file is a typed struct — every column has a declared type before any data is written.

```java
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import java.util.List;

DType.Struct schema = new DType.Struct(
    List.of("timestamp", "symbol", "price", "volume"),
    List.of(
        new DType.Primitive(PType.I64, false),   // unix epoch millis, non-nullable
        DType.UTF8,                               // ticker symbol
        new DType.Primitive(PType.F64, false),   // trade price
        new DType.Primitive(PType.I64, false)    // shares traded
    ),
    false  // the struct itself is non-nullable
);
```

`PType` mirrors Arrow's physical types: `I8`, `I16`, `I32`, `I64`, `U8`…`U64`, `F32`, `F64`.
Passing `true` as the second argument to `Primitive` makes the column nullable.

---

## 3. Write data

```java
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;
import static java.nio.file.StandardOpenOption.*;

Path outPath = Path.of("trades.vortex");

try (FileChannel ch = FileChannel.open(outPath, CREATE, WRITE, TRUNCATE_EXISTING);
     VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {

    writer.writeChunk(Map.of(
        "timestamp", new long[]   {1_700_000_000_000L, 1_700_000_001_000L, 1_700_000_002_000L},
        "symbol",    new String[]  {"AAPL", "AAPL", "MSFT"},
        "price",     new double[]  {189.95, 190.10, 374.20},
        "volume",    new long[]    {100L,   250L,   175L}
    ));
}
```

`writeChunk` takes one batch of rows. Call it multiple times to write multiple chunks —
each chunk is compressed independently and can be skipped during a scan if zone-map
statistics rule it out.

The file is complete and readable as soon as `VortexWriter` is closed.

---

## 4. Read it back

```java
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.core.array.DoubleArray;
import io.github.dfa1.vortex.core.array.LongArray;

try (VortexReader vf = VortexReader.open(outPath);
     var iter = vf.scan(ScanOptions.all())) {

    while (iter.hasNext()) {
        var chunk = iter.next();   // advances to the next batch

        LongArray  ts     = chunk.column("timestamp");
        DoubleArray price = chunk.column("price");

        for (long i = 0; i < chunk.rowCount(); i++) {
            System.out.printf("%d  %.2f%n", ts.getLong(i), price.getDouble(i));
        }
        // ⚠ do not store references past this point —
        //   iter.hasNext() frees the chunk's memory
    }
}
```

**Important:** every chunk lives in an off-heap `Arena`. Calling `iter.hasNext()` closes
that arena and releases the memory. Read all values before advancing the iterator.

Expected output:

```
1700000000000  189.95
1700000001000  190.10
1700000002000  374.20
```

---

## 5. Project columns and limit rows

Reading every column is wasteful when you only need two. Use `withColumns` to project, and
`withLimit` to stop after `n` rows:

```java
ScanOptions opts = ScanOptions.all()
    .withColumns("symbol", "price")
    .withLimit(2);

try (VortexReader vf = VortexReader.open(outPath);
     var iter = vf.scan(opts)) {

    while (iter.hasNext()) {
        var chunk = iter.next();
        // chunk only contains "symbol" and "price"
    }
}
```

---

## 6. Inspect with the CLI

Build the CLI fat jar once:

```bash
./mvnw package -pl cli -am -DskipTests
```

Then use it on any file without writing code:

```bash
# what columns and types does the file have?
java -jar cli/target/vortex.jar schema trades.vortex
# → struct<timestamp: I64, symbol: utf8, price: F64, volume: I64>

# how many rows?
java -jar cli/target/vortex.jar count trades.vortex
# → 3

# dump to CSV
java -jar cli/target/vortex.jar export trades.vortex
```

---

## What's next

- [docs/compatibility.md](compatibility.md) — which encodings are supported
- [docs/explanation.md](explanation.md) — memory model, testing strategy, benchmarks
- The `ScanOptions` API supports row filters: `new RowFilter.Gte("volume", 200)` — only rows where volume ≥ 200
