# Vortex HTTP range demo

Shows that a filtered, projected `VortexHttpReader` scan over HTTP fetches only the bytes it
actually touches — not the whole file — using nothing but plain HTTP `Range` requests. No cloud
account, no query service: just a `.vortex` file sitting behind a minimal object-storage server.

Three standalone tools:

| Module | Artifact | What it does |
|---|---|---|
| `fakedata-generator` | `vortex-fakedata-generator.jar` | Generates a synthetic `.vortex` file from a compact column-description grammar |
| `server` | `vortex-server.jar` | Minimal object-storage HTTP server — `GET`/`HEAD` with byte-range support, `PUT`, listing |
| `client` | `vortex-demo.jar` | Uploads a file, runs a time-range-filtered/projected scan, reports bytes fetched vs. the file's full size |

## Build once

```bash
./mvnw package -pl demo/server,demo/fakedata-generator,demo/client -am -DskipTests
```

Produces:

```
demo/server/target/vortex-server.jar
demo/fakedata-generator/target/vortex-fakedata-generator.jar
demo/client/target/vortex-demo.jar
```

## Run the live demo (two terminals)

**Terminal 1 — start the server** (serves whatever directory you point it at, on whatever port):

```bash
java -jar demo/server/target/vortex-server.jar 8080 /tmp/vortex-demo-data
```

Leave it running — it logs every request it serves, which is the point: you'll watch it print a
handful of small `Range` fetches instead of one big download.

**Terminal 2 — generate the dataset:**

```bash
java -jar demo/fakedata-generator/target/vortex-fakedata-generator.jar \
    --rows 2000000 --out /tmp/trades.vortex \
    "timestamp:i64:series(1700000000000,1000)" \
    "symbol:utf8:enum(SYM,30)" \
    "price:f64:range(50,150)" \
    "volume:i64:range(100,10000)"
```

Prints a live progress bar with ETA to stderr while writing (throttled, so it won't flood the
terminal — only really visible on larger row counts). 2,000,000 rows, real compression
(`cascading(3)`, the generator's default) — no clustering trick needed. The `timestamp` column is
a `series(...)`, so it's naturally in row order simply because that's the order it was generated
in, exactly like a real append-only ingestion stream.

**Terminal 2 — upload it, then query it:**

```bash
java -jar demo/client/target/vortex-demo.jar --upload /tmp/trades.vortex http://127.0.0.1:8080/
java -jar demo/client/target/vortex-demo.jar http://127.0.0.1:8080/trades.vortex
```

The first command just copies the file to the server and exits — no query. The second queries the
object directly by URL: no local file, no upload, it's already there. Run the second command again
with different `--filter-column`/`--filter-min`/`--filter-max`/`--project` values to try other
queries against the same uploaded object without re-uploading it.

By default this filters `timestamp` to a narrow window (50,000 of the 2,000,000 rows) and
projects `price` — a realistic "give me this time range" query, not an equality match on some
other column. While the scan runs, a live "bytes downloaded so far" counter updates in place on
stderr — the audience watches it climb a little, then stop well short of the file's full size,
rather than just seeing a single number appear at the end. It's mostly visible on larger row
counts, since a small scan can finish before the first redraw.

Expected output (numbers will vary slightly with row count):

```
Scanning for 1701000000000 <= timestamp <= 1701049999000, projecting 'price' over HTTP...

  Downloaded so far: 401,644 / 24,552,440 bytes (1.6%)
Matched rows: 131072
Bytes fetched over HTTP during the scan: 1,363,118 / 24,552,440 (5.55% of the object)
```

Switch back to **Terminal 1** — you'll see the `PUT` (the upload) followed by a small number of
`GET ... range=bytes=...` lines, each a few hundred KB, not one 24 MB download.

## One-terminal version (no server to manage)

Pass a local file path instead of a URL and the client embeds its own server, uploads to it, and
queries it in one shot:

```bash
java -jar demo/client/target/vortex-demo.jar /tmp/trades.vortex
```

Good for a quick local check; the two-terminal version is more compelling live, since the
audience can watch the server's request log update in real time.

## Customizing the story

- **Different filter/projection** — `vortex-demo` accepts `--filter-column`, `--filter-min`,
  `--filter-max`, and `--project` (numeric-range filter, not equality — see below for why). Match
  these to whatever schema you generate.
- **Different dataset shape** — `vortex-fakedata-generator`'s column grammar is
  `name:type:generator(args)`. Run it with no arguments for the full grammar reference
  (types, generators, an example). The `series(start,step)` generator is a direct nod to SQL's
  `generate_series`.
- **Why a range filter on `timestamp`, not equality on `symbol`** — there is deliberately no
  "sort the rows before writing" option anywhere in this demo: real data never arrives pre-sorted
  by whatever column a later query happens to filter on, so faking that clustering would
  misrepresent the workload this is meant to demonstrate. A `series(...)` column is naturally
  ordered by row position without any sorting, which zone-map pruning can exploit for a
  range query; an `enum(...)` column's values are scattered uniformly across every chunk by
  design, so an equality filter on it (e.g. `symbol == "SYM015"`) has nothing to prune — every
  chunk could contain a match.

## Bugs this demo surfaced (now fixed upstream)

Building this surfaced three real gaps in zone-map pruning, filed and since fixed in vortex-java's
`reader`/`writer` modules:

- [#378](https://github.com/dfa1/vortex-java/issues/378) — `WriteOptions`'s default
  `globalDict=true` silently defeated zone-map pruning for `Utf8` columns.
- [#379](https://github.com/dfa1/vortex-java/issues/379) — a cascade-selected encoder
  (`cascading(depth) > 0`) dropped zone-map min/max stats, keeping only `sum`.
- [#380](https://github.com/dfa1/vortex-java/issues/380) — checking whether an HTTP-backed chunk
  could be pruned fetched that chunk's *entire* segment first, costing as much bandwidth as just
  reading it.

This demo's numbers reflect the fixed behavior. If you're running against an older vortex-java
build, pruning may not work and the byte percentage will be much higher than shown above.
