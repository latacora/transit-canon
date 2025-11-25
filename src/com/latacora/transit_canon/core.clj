(ns com.latacora.transit-canon.core
  "Canonical serialization for Clojure data using Transit.

  **EXPERIMENTAL**: This library explores whether deterministic serialization
  is achievable for Clojure data structures. Results are not guaranteed.

  ## Problem

  Transit over JSON with RFC 8785 does not provide fully deterministic
  serialization because:
  1. Maps serialize in insertion order (Transit uses JSON arrays, not objects)
  2. Integer/float distinction can be lost through JSON canonicalization
  3. Set ordering depends on hashCode() which may vary across JVMs

  ## Solution

  This library pre-normalizes data structures before Transit encoding:
  - Maps are sorted by a canonical key comparator
  - Sets are converted to sorted vectors
  - Integers are converted to BigInt to preserve type distinction

  ## API

  - `serialize` - Convert a value to canonical bytes
  - `deserialize` - Convert bytes back to a value
  - `canonical?` - Test if a value can be canonicalized

  ## Known limitations

  - Metadata is stripped (intentional for canonicalization)
  - Plain integers deserialize as BigInt
  - Sets deserialize as vectors (same elements, different type)
  - Custom types may not compare correctly"
  (:require
   [cognitect.transit :as transit]
   [com.latacora.transit-canon.impl.normalize :as normalize]
   [com.latacora.transit-canon.impl.compress :as compress])
  (:import
   (org.erdtman.jcs JsonCanonicalizer)
   (java.io ByteArrayOutputStream ByteArrayInputStream)))

(declare canonical?)

(def ^:private default-opts
  {:compress? true
   :compression-level 3
   :strict? false})

(defn- transit-encode
  "Encode a value to Transit JSON bytes."
  ^bytes [obj]
  (let [out (ByteArrayOutputStream.)]
    (-> out
        (transit/writer :json)
        (transit/write obj))
    (.toByteArray out)))

(defn- json-canonicalize
  "Apply RFC 8785 canonicalization to JSON bytes."
  ^bytes [^bytes json-bytes]
  (-> json-bytes
      JsonCanonicalizer.
      .getEncodedUTF8))

(defn- transit-decode
  "Decode Transit JSON bytes to a value."
  [^bytes json-bytes]
  (-> json-bytes
      ByteArrayInputStream.
      (transit/reader :json)
      transit/read))

(defn serialize
  "Serializes a Clojure value to a canonicalized byte array.

  The serialization pipeline:
  1. Normalize: sort maps, convert sets to vectors, fix numeric types
  2. Transit encode: convert to JSON with Transit's type preservation
  3. JSON canonicalize: apply RFC 8785 canonicalization
  4. Compress: apply zstd compression (optional)

  Options:
  - :compress?         - Apply compression (default: true)
  - :compression-level - Zstd level 1-22 (default: 3)
  - :strict?           - Throw on non-canonicalizable values (default: false)

  Returns a byte array, or throws if :strict? is true and the value
  cannot be canonicalized.

  Note: Metadata is ignored for canonicalization purposes."
  (^bytes [obj]
   (serialize obj {}))
  (^bytes [obj opts]
   (let [{:keys [compress? compression-level strict?]}
         (merge default-opts opts)]
     (when (and strict? (not (canonical? obj)))
       (throw (ex-info "Value cannot be canonicalized"
                       {:value obj})))
     (let [normalized (normalize/normalize obj)
           transit-bytes (transit-encode normalized)
           canonical-bytes (json-canonicalize transit-bytes)]
       (if compress?
         (compress/compress canonical-bytes compression-level)
         canonical-bytes)))))

(defn deserialize
  "Deserializes a byte array to a Clojure value.

  Handles both compressed and uncompressed canonical bytes.
  Uses zstd frame detection to determine if decompression is needed.

  Note: The deserialized value may differ from the original in type:
  - Sets become vectors (elements preserved)
  - Plain integers become BigInt (numeric equality preserved)
  - Metadata is not preserved"
  [^bytes bs]
  (let [;; Try to detect if compressed by checking zstd magic number
        ;; Zstd magic: 0x28 0xB5 0x2F 0xFD
        is-zstd? (and (>= (alength bs) 4)
                      (= (aget bs 0) (unchecked-byte 0x28))
                      (= (aget bs 1) (unchecked-byte 0xB5))
                      (= (aget bs 2) (unchecked-byte 0x2F))
                      (= (aget bs 3) (unchecked-byte 0xFD)))
        json-bytes (if is-zstd?
                     (compress/decompress bs)
                     bs)]
    (transit-decode json-bytes)))

(defn canonical?
  "Test if a value can be canonicalized.

  Returns true if the value has a deterministic serialization.
  Returns false for values with circular references or types
  that cannot be reliably ordered.

  Note: This is a best-effort check. Edge cases may not be detected."
  [obj]
  (normalize/canonicalizable? obj))

(defn serialize-uncompressed
  "Convenience function for uncompressed canonical serialization.
  Equivalent to (serialize obj {:compress? false})."
  ^bytes [obj]
  (serialize obj {:compress? false}))

(defn canonical-bytes=
  "Test if two values produce identical canonical bytes.

  Useful for testing whether two values are canonically equal,
  even if they have different construction histories or types."
  [a b]
  (java.util.Arrays/equals (serialize a) (serialize b)))
