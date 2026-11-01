package io.github.dfa1.vortex.core.model;

/// The metadata of a [DType.Extension], as Rust holds an extension's `ExtVTable::Metadata` rather
/// than its bytes. The reader parses it from the bytes a file stores and the writer serializes it
/// back; nothing in between sees the bytes.
///
/// An extension point: a third-party extension defines its own. It must be a **record**, so a
/// dtype compares and hashes by value ([DType.Extension] refuses anything else), with components
/// that do too: a `byte[]` or `MemorySegment` component compares by identity unless the record
/// overrides `equals` and `hashCode`, as [OpaqueMetadata] does. The well-known
/// temporal extensions use [TimeUnitMetadata] and [TimestampMetadata]; every other id keeps its
/// bytes as [OpaqueMetadata] (Rust's `ForeignExtMetadata`).
public interface ExtensionMetadata {
}
