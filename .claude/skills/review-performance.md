---
name: review-performance
description: Code review skill for high-throughput Java data processing, focusing on memory bounds (FFM API), zero-copy I/O, and hot-loop optimization. Tailored to the vortex-java columnar reader/writer (confined Arena, ctx.arena() for codec output).
---

## Overview

This skill provides specialized instructions for reviewing high-performance Java code, particularly data processing
pipelines, columnar decoders, and hot-loop execution. When asked to review Java code for performance, apply these
advanced heuristics rather than generic advice.

---

## Core Optimization Rules

### 1. Hot-Loop Optimization

- **Loop Unswitching:** Hoist polymorphic branches (e.g., `switch` on primitive types) *outside* tight loops to improve
  branch predictability and enable vectorization.
- **Strength Reduction:** Replace repeated multiplications (`base + i * stride`) with incremental additions (
  `v += stride`).
    - Warn about IEEE‑754 drift when accumulating `float`/`double` over large iteration counts.
- **Invariant Hoisting:** Move loop-invariant loads, bounds, and metadata outside the loop.
- **Predictability:** Flag unpredictable, data-dependent branches inside hot loops.
- **Bounds Check Elimination:** Encourage patterns that allow the JIT to remove array bounds checks (e.g., using indexed
  loops with prevalidated ranges).
- **Mark opportunities for Java Vector API.**

---

### 2. Memory Rules: Heap, Direct, and FFM

- **2GB Limit:** Flag any `(int) (n * elemBytes)` or similar size computation that may overflow `Integer.MAX_VALUE` (~
  2.14 GB).
- **Prefer FFM for Large Buffers:** Recommend migrating large `ByteBuffer` allocations to the Foreign Function & Memory
  API (`MemorySegment`).
    - **Codec output allocation rule (hard, from `CLAUDE.md`):** never allocate `byte[]` + wrap with
      `MemorySegment.ofArray()` for decode output. Always allocate from the `DecodeContext` arena:
      `ctx.arena().allocate(n * elemBytes, alignment)`. The buffer's lifetime is the confined `Arena` owned
      by the `VortexFile` — `Arena.ofAuto()` / `Arena.ofShared()` violate that ownership model and leak / drop
      the segment at the wrong time.
    - If the allocation lives in a private static helper without `DecodeContext`, add an `Arena arena`
      parameter and pass `ctx.arena()` at the call site.
- **Alignment & VarHandles:**
    - Warn when using `ValueLayout.JAVA_INT` or similar on potentially unaligned addresses.
    - Prefer `ValueLayout` constants over manual offset math.
    - Flag aliasing between overlapping `MemorySegment` slices.
- **Mixed Heap/Off‑Heap Pipelines:**
    - Flag transitions between heap arrays, direct buffers, and `MemorySegment` unless explicitly justified.
    - Require clear ownership and lifetime rules when mixing arenas, slices, and native handles.

---

### 3. Zero-Copy I/O and Protobuf

- **Eliminate Intermediate `byte[]`:** Flag any heap array created solely to bridge between `ByteBuffer` and parsing
  frameworks.
- **Direct Protobuf Parsing:**
    - Recommend `CodedInputStream.newInstance(ByteBuffer)` for metadata parsing.
    - Note: Protobuf does not support `MemorySegment`; `ByteBuffer` is optimal for small metadata payloads.

---

## Profiling-Driven Overrides

When the user provides profiling data (JFR, async-profiler, perf, flamegraphs):

- Prioritize empirical evidence over static heuristics.
- If a rule contradicts profiling data, warn but defer to the measurements.
- Encourage validating changes with before/after profiles.

---

## Conflict Resolution Rules

- **Correctness > Performance:** If an optimization risks semantic drift, warn instead of rewriting.
- **FFM vs ByteBuffer:** Prefer FFM for large or long-lived buffers; prefer `ByteBuffer` for tiny metadata payloads or
  Protobuf interop.
- **Precision vs Speed:** When strength reduction introduces floating-point drift, warn and let the user decide.
- **Safety First:** If memory aliasing, lifetime, or alignment is unclear, flag it even if performance would improve.

---

## When to Apply

Apply these guidelines automatically whenever the user asks to:

- Review array, columnar, or sequence decoders.
- Audit large dataset memory allocations (I/O, buffers).
- Review code using FFM (`MemorySegment`), NIO (`ByteBuffer`), or JNI.
- Optimize hot loops, vectorizable loops, or tight decode/encode paths.
- Improve zero-copy I/O, Protobuf parsing, or off-heap memory usage.


