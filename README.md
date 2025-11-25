# transit-canon

**EXPERIMENTAL**: A Clojure library exploring canonical serialization for Transit data.

This library investigates whether we can achieve truly deterministic byte representations for Clojure data structures. Success is not guaranteed - documenting what doesn't work is as valuable as finding what does.

## Status

This library is experimental research. Do not use in production without understanding the limitations documented below.

## The Problem

Transit over JSON with RFC 8785 (JSON Canonicalization Scheme) does not provide fully deterministic serialization for all Clojure data structures:

1. **Maps are not canonical**: Same logical map can serialize differently based on construction order
   - `{:a 1 :b 2 :c 3}` built in different orders produces different byte sequences
   - Transit encodes maps as JSON arrays (not objects), so RFC 8785 doesn't sort them
   - Clojure hash-maps preserve insertion order, affecting serialization

2. **Integer/float distinction**: RFC 8785 uses ECMAScript rules where `1.0` -> `1`
   - Transit expects JSON to preserve int vs float distinction
   - Plain ints/longs can be confused with floats after canonicalization
   - BigInteger, BigDecimal work correctly due to explicit tagging

3. **Sets**: Rely on hashCode() ordering
   - No guarantee of stability across JVM versions or platforms

## The Solution

This library pre-normalizes data structures before Transit encoding:

- **Maps**: Entries sorted by a canonical key comparator
- **Sets**: Converted to sorted vectors
- **Integers**: Converted to BigInt to preserve type distinction through canonicalization
- **Metadata**: Stripped (intentional for canonicalization)

## Installation

Add to your `deps.edn`:

```clojure
{:deps
 {io.github.latacora/transit-canon {:git/tag "v0.1.0" :git/sha "..."}}}
```

## Usage

```clojure
(require '[com.latacora.transit-canon.core :as canon])

;; Serialize to canonical bytes
(def bytes (canon/serialize {:a 1 :b 2 :c 3}))

;; Deserialize back
(def value (canon/deserialize bytes))

;; Test if two values produce identical canonical bytes
(canon/canonical-bytes=
  (zipmap [:a :b :c] [1 2 3])
  (zipmap [:c :b :a] [3 2 1]))
;; => true

;; Serialize without compression
(canon/serialize-uncompressed {:a 1})

;; Options
(canon/serialize data {:compress? false        ; disable compression
                       :compression-level 9    ; zstd level 1-22
                       :strict? true})         ; throw on non-canonicalizable
```

## API

### `(serialize obj)` / `(serialize obj opts)`

Serializes a Clojure value to canonicalized bytes.

**Options:**
- `:compress?` - Apply zstd compression (default: `true`)
- `:compression-level` - Zstd level 1-22 (default: `3`)
- `:strict?` - Throw on non-canonicalizable values (default: `false`)

### `(deserialize bytes)`

Deserializes bytes back to a Clojure value.

### `(canonical?)`

Test if a value can be canonicalized.

### `(canonical-bytes= a b)`

Test if two values produce identical canonical bytes.

## Known Limitations

### Type Changes After Roundtrip

| Original Type | After Roundtrip |
|---------------|-----------------|
| `Long`/`Integer` | `BigInt` |
| `Set` | `Vector` |
| Metadata | Stripped |

### Values That Cannot Be Canonicalized

- Values with circular references
- Custom types without natural ordering (falls back to hash comparison)

### Numeric Equality

Plain integers become BigInt after roundtrip. Numeric equality is preserved, but type equality is not:

```clojure
(= 42 (-> 42 serialize deserialize))  ;; => true
(= (type 42) (type (-> 42 serialize deserialize)))  ;; => false (BigInt)
```

### Set Semantics

Sets become vectors after roundtrip. Elements are preserved but type changes:

```clojure
(= (set #{1 2 3}) (set (-> #{1 2 3} serialize deserialize)))  ;; => true
(set? (-> #{1 2 3} serialize deserialize))  ;; => false (vector)
```

## Compression Determinism

**Status: Under Investigation**

This library uses zstd compression. Our tests show compression is deterministic:
- Within a single JVM session
- With fixed compression parameters

Open questions (see GitHub issues):
- Cross-JVM determinism
- Cross-platform determinism
- zstd version sensitivity

## Performance

This library prioritizes correctness and determinism over performance. Expect overhead compared to plain Transit due to:
- Deep walking of data structures for normalization
- Sorting operations on maps and sets
- Additional canonicalization pass

Benchmarks TBD.

## Development

```bash
# Run tests
bb test

# Run linters
bb lint

# Check for outdated dependencies
bb maint
```

## Related Work

- [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) - JSON Canonicalization Scheme
- [RFC 8949 Section 4.2](https://www.rfc-editor.org/rfc/rfc8949#section-4.2) - CBOR Deterministic Encoding
- [Transit Format](https://github.com/cognitect/transit-format)

## License

Copyright 2024 Latacora

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
