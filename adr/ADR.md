# Architecture Decision Records

This directory contains ADRs following the
[MADR 3.0](https://adr.github.io/madr/) format (Markdown Architectural Decision Records).

## Format

Each ADR is a Markdown file named `NNNN-short-title.md`. Use `template.md` as the starting point.

**Status values:** Proposed → Accepted → Deprecated | Superseded — or Deferred / Rejected for
decisions not adopted. Status tracks only the decision; the **Released** column records the version
the decision shipped in (blank = not yet shipped).

## Index

| ADR  | Title                                         | Status    | Released |
|------|-----------------------------------------------|-----------|----------|
| 0001 | Split read and write runtimes out of core     | Accepted | 0.7.0 |
| 0002 | Pluggable DType, Layout, and Compute          | Deferred  |          |
| 0003 | VortexException sanitization + bounds typing  | Accepted  |          |
| 0004 | ResourceLimits + ReadOptions                  | Accepted  |          |
| 0005 | Vector API adoption                           | Deferred  |          |
| 0006 | Benchmark publishing                          | Accepted  |          |
| 0007 | Pure-Java vortex.pco encoder                  | Accepted   | 0.7.0 |
| 0008 | Domain primitives for unsigned integers       | Proposed  |          |
| 0009 | Write API ergonomics                          | Accepted | 0.7.0 |
| 0010 | Lazy decode                                   | Accepted   | 0.7.0 |
| 0011 | Writer zero-copy MemorySegment overload       | Deferred  |          |
| 0012 | Zero-copy layout decoding: lazy Chunked/Dict  | Accepted   | 0.7.0 |
| 0013 | Compute primitives: fused filter/aggregate kernels | Accepted  | 0.11.0 |
| 0014 | Variant encoding: chunked constants now, parquet.variant later | Accepted   | 0.8.0 |
| 0015 | Drop Materialized fallbacks once Lazy has shipped | Accepted | 0.8.0 |
| 0016 | vortex-arrow bridge module for Arrow interop  | Proposed  |          |
| 0017 | In-house FlatBuffers codegen + MemorySegment runtime | Accepted  |          |
| 0018 | Apache Calcite SQL adapter — push-down source | Accepted  |          |
| 0019 | Columnar transducer façade for compute        | Proposed  |          |
| 0020 | Jazzer fuzz testing infrastructure            | Proposed  |          |
| 0021 | Cardinality-bounded global dict buffering     | Accepted  |          |
