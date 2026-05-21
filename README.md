# transit-canon

**EXPERIMENTAL**: A Clojure library exploring canonical serialization for Transit data.

This library investigates whether we can achieve truly deterministic byte representations for Clojure data structures. Success is not guaranteed - documenting what doesn't work is as valuable as finding what does.

## Status

This library is experimental research. Do not use in production without understanding the limitations documented below.

## Canonical serializer requirements

When transit-canon is used as a cache key (e.g. by [sqlite-cache](https://github.com/latacora/sqlite-cache)), it must behave as a *canonical serializer*: equal inputs must always produce identical bytes, regardless of where or when serialization runs.

| Property | Status | Notes |
|---|---|---|
| Determinism within a JVM | Verified | Covered by `core-test` (maps, sets, complex keys, metadata). |
| Determinism across JVM restarts | Partially verified | Transit output is stable; zstd compression frames are not yet confirmed stable across restarts (see [#1](https://github.com/latacora/transit-canon/issues/1)). |
| Determinism across JVM versions | Unverified | No CI matrix yet; relies on JDK string/number formatting being stable, which is not guaranteed. |
| Determinism across platforms | Unverified | Same as above; native zstd may differ across OS/arch (see [#1](https://github.com/latacora/transit-canon/issues/1)). |
| Type round-trip fidelity | Known-broken | `Long`/`Integer` deserialize as `BigInt` by design. RFC 8785 also collapses `1.0` -> `1`, losing the int/float distinction (see [#6](https://github.com/latacora/transit-canon/issues/6)). Metadata is stripped intentionally. |

Until the unverified rows above are exercised by tests or CI, treat transit-canon's cross-environment determinism as a working hypothesis, not a guarantee. See [#5](https://github.com/latacora/transit-canon/issues/5) for production-readiness criteria.

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
- **Sets**: Elements serialized in canonical order (custom Transit handler)
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
- `:handlers` - Map of `Class -> transit/write-handler` for types the built-in canonical handlers don't cover. See [Custom handlers](#custom-handlers).

### `(deserialize bytes)` / `(deserialize bytes opts)`

Deserializes bytes back to a Clojure value.

**Options:**
- `:handlers` - Map of `tag-string -> transit/read-handler`, merged with the reader's defaults.

### `(canonical?)`

Test if a value can be canonicalized.

### `(canonical-bytes= a b)`

Test if two values produce identical canonical bytes.

## Custom handlers

The built-in canonical handlers cover Clojure collections, strings, keywords, symbols, and numbers. Other types (e.g. `java.time.LocalDate`, `java.util.regex.Pattern`, `java.nio.ByteBuffer`) require caller-supplied handlers:

```clojure
(require '[cognitect.transit :as transit])
(import '(java.time LocalDate))

(def write-handlers
  {LocalDate (transit/write-handler (constantly "local-date") str)})

(def read-handlers
  {"local-date" (transit/read-handler #(LocalDate/parse %))})

(def bs (canon/serialize {:date (LocalDate/of 2026 5 20)}
                         {:handlers write-handlers}))

(canon/deserialize bs {:handlers read-handlers})
;; => {:date #object[java.time.LocalDate ... "2026-05-20"]}
```

User-supplied write handlers are merged *under* the built-in canonical handlers — built-ins for maps, sets, and integers always win, so callers cannot accidentally break canonicalization by registering a handler for `clojure.lang.PersistentArrayMap`.

### `java.time` via time-literals

[`time-literals`](https://github.com/henryw374/time-literals) (`com.widdindustries/time-literals`) is the community-standard library for `java.time` transit handlers. Its `time-literals.read-write/tags` map (tag-symbol -> read-fn) can be wrapped into transit read handlers directly:

```clojure
(require '[time-literals.read-write :as tl])

(def java-time-read-handlers
  (into {} (map (fn [[tag f]] [(str tag) (transit/read-handler f)])) tl/tags))

(canon/deserialize bs {:handlers java-time-read-handlers})
```

Write handlers need an explicit `Class -> tag` mapping (the class names don't follow a single naming convention — `time/date` is `LocalDate`, `time/date-time` is `LocalDateTime`, etc.). Build a table that mirrors the types you actually serialize:

```clojure
(import '(java.time Instant LocalDate LocalDateTime))

(def java-time-write-handlers
  {Instant       (transit/write-handler (constantly "time/instant") str)
   LocalDate     (transit/write-handler (constantly "time/date") str)
   LocalDateTime (transit/write-handler (constantly "time/date-time") str)})

(canon/serialize obj {:handlers java-time-write-handlers})
```

**Tag compatibility caveat.** `com.latacora.formats.transit` (in the `libraries` monorepo) uses the tag `"instant"` for `java.time.Instant`; time-literals uses `"time/instant"`. Bytes written with one set of tags will not deserialize with the other. If you're migrating, register both as read handlers and pick one tag set for new writes.

## Known Limitations

### Type Changes After Roundtrip

| Original Type | After Roundtrip |
|---------------|-----------------|
| `Long`/`Integer` | `BigInt` |
| Metadata | Stripped |

### Values That Cannot Be Canonicalized

- Values with circular references
- Custom types without natural ordering (falls back to hash comparison)

### Types Requiring Custom Handlers

These types throw `Not supported` from Transit unless you register a handler via `:handlers`:

| Type | Recommended approach |
|---|---|
| `java.time.Instant`, `LocalDate`, `LocalDateTime`, `ZonedDateTime`, etc. | [`time-literals`](#javatime-via-time-literals) |
| `java.util.regex.Pattern` | Tag `"regex"`, write `str`, read `re-pattern` |
| `java.nio.ByteBuffer` | Tag `"bin"` (Transit binary) or base64-encode |
| `clojure.lang.TaggedLiteral` | Tag `"taggedliteral"` with explicit `tag`/`form` round-trip |

`com.latacora.formats.transit` (in the Latacora `libraries` monorepo) ships handlers for `Instant`, `Pattern`, `ByteBuffer`, and `TaggedLiteral` that can be passed straight to `:handlers`.

### Numeric Equality

Plain integers become BigInt after roundtrip. Numeric equality is preserved, but type equality is not:

```clojure
(= 42 (-> 42 serialize deserialize))  ;; => true
(= (type 42) (type (-> 42 serialize deserialize)))  ;; => false (BigInt)
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

### How We Solved Transit's Top-Down Serialization

Transit serializes top-down: parent handlers run before children are serialized. To sort map keys or set elements canonically, we need their serialized form—but that form isn't available until after we've already decided on ordering.

This creates a fundamental tension:

1. To sort, we need serialized forms
2. To serialize, Transit walks top-down
3. Children aren't serialized when parent handler runs

**Solution: decorate-sort-undecorate with raw emission**

We solved this with a custom `JsonEmitter` subclass (via Clojure's `gen-class`) that can emit pre-serialized JSON directly:

1. **Decorate**: Serialize each sortable key/element to get its sort key, wrap in `Decorated{value, json-string}`
2. **Sort**: Order entries by the decorated sort key
3. **Undecorate**: Convert `Decorated` → `RawJson` which holds the pre-serialized string
4. **Emit**: Our custom emitter writes `RawJson` content directly without re-serialization

This eliminates double-serialization while leveraging Transit's existing machinery for recursion, type dispatch, and string caching.

**Why gen-class instead of proxy?**

Java's `proxy` cannot intercept internal method calls within a class hierarchy. When `AbstractEmitter.emitArray()` calls `this.marshal()`, it bypasses proxy overrides. Clojure's `gen-class` creates a true Java subclass where virtual dispatch works correctly, allowing us to intercept all `marshal()` calls including recursive ones.

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

Copyright Latacora

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
