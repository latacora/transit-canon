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

  This library uses Schwartzian transform (decorate-sort-undecorate):
  - Compute sort keys once per element using pr-str
  - Sort by the pre-computed keys
  - Let Transit serialize the sorted result
  - Integers are converted to BigInt to preserve type distinction

  ## API

  - `serialize` - Convert a value to canonical bytes
  - `deserialize` - Convert bytes back to a value
  - `canonical?` - Test if a value can be canonicalized

  ## Known limitations

  - Metadata is stripped (intentional for canonicalization)
  - Plain integers deserialize as BigInt
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

;; String-based sort key for cross-type or non-Comparable values.
;; Type prefixes ensure consistent cross-type ordering.
(defn- str-sort-key [x]
  (cond
    (nil? x)     "0"
    (keyword? x) (str "1" (when-let [ns (namespace x)] (str ns "/")) (name x))
    (string? x)  (str "2" x)
    (integer? x) (str "3" x)
    (symbol? x)  (str "4" (when-let [ns (namespace x)] (str ns "/")) (name x))
    :else        (str "9" (pr-str x))))

;; String-based sorting using Schwartzian transform.
;; Used as fallback when native compare fails.
(defn- string-sort-entries [m]
  (->> m
       (map (fn [[k v]] [(str-sort-key k) k v]))
       (sort-by first)
       (map (fn [[_ k v]] (clojure.lang.MapEntry. k v)))))

;; Wrapper type for sorted sequences.
;; Transit needs a non-seq type to emit as plain JSON array (seqs get ~#list tag).
;; This wrapper holds the seq without copying, and has its own handler.
(deftype SortedSeqWrapper [s]
  clojure.lang.Seqable
  (seq [_] s))

(defn- string-sort-elements [s]
  (->> s
       (map (fn [x] [(str-sort-key x) x]))
       (sort-by first)
       (map second)
       ->SortedSeqWrapper))

;; Custom Transit write handler for maps.
;; Optimistically tries native sorting (fast path for homogeneous Comparable keys),
;; falls back to string-based Schwartzian transform on ClassCastException.
(def ^:private canonical-map-handler
  (transit/write-handler
   (constantly "map")
   (fn [m]
     (try
       (sort-by key m)
       (catch ClassCastException _
         (string-sort-entries m))))))

;; Custom Transit write handler for sets.
;; Optimistically tries native sorting, falls back to string-based on failure.
;; Returns SortedSeqWrapper to avoid copying (wrapper handler emits seq as array).
(def ^:private canonical-set-handler
  (transit/write-handler
   (constantly "set")
   (fn [s]
     (try
       (->SortedSeqWrapper (sort s))
       (catch ClassCastException _
         (string-sort-elements s))))))

;; Handler for SortedSeqWrapper - emits contents as plain JSON array.
;; The "array" tag tells Transit to serialize as [...] without any wrapper tag.
(def ^:private sorted-seq-wrapper-handler
  (transit/write-handler
   (constantly "array")
   seq))

;; Number handlers that convert to BigInt to preserve int/float distinction.
;; Without this, Transit serializes Long as JSON numbers which RFC 8785
;; could canonicalize differently than BigInt strings.
(def ^:private long-handler
  (transit/write-handler
   (constantly "n")  ;; BigInt tag
   (fn [n] (str (bigint n)))))

(def ^:private integer-handler
  (transit/write-handler
   (constantly "n")
   (fn [n] (str (bigint n)))))

(def ^:private short-handler
  (transit/write-handler
   (constantly "n")
   (fn [n] (str (bigint n)))))

(def ^:private byte-handler
  (transit/write-handler
   (constantly "n")
   (fn [n] (str (bigint n)))))

(def ^:private canonical-handlers
  {;; Maps - all map types need sorting
   clojure.lang.PersistentArrayMap canonical-map-handler
   clojure.lang.PersistentHashMap canonical-map-handler
   clojure.lang.PersistentTreeMap canonical-map-handler
   ;; Sets - all set types need sorting
   clojure.lang.PersistentHashSet canonical-set-handler
   clojure.lang.PersistentTreeSet canonical-set-handler
   ;; Wrapper for sorted sequences (emits as plain array, no copy)
   SortedSeqWrapper sorted-seq-wrapper-handler
   ;; Numbers - convert to BigInt to preserve int/float distinction
   java.lang.Long long-handler
   java.lang.Integer integer-handler
   java.lang.Short short-handler
   java.lang.Byte byte-handler})

(defn- transit-encode
  "Encode a value to Transit JSON bytes using canonical handlers.
  Maps and sets are sorted by Schwartzian transform for efficiency."
  ^bytes [obj]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json {:handlers canonical-handlers}) obj)
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
  1. Transit encode: convert to JSON with canonical handlers
     - Maps sorted by serialized key
     - Sets sorted by serialized element
     - Integers converted to BigInt (preserves int/float distinction)
  2. JSON canonicalize: apply RFC 8785 (number formatting)
  3. Compress: apply zstd compression (optional)

  Options:
  - :compress?         - Apply compression (default: true)
  - :compression-level - Zstd level 1-22 (default: 3)
  - :strict?           - Throw on non-canonicalizable values (default: false)

  Returns a byte array, or throws if :strict? is true and the value
  cannot be canonicalized.

  Note: Metadata is ignored by Transit and does not affect output."
  (^bytes [obj]
   (serialize obj {}))
  (^bytes [obj opts]
   (let [{:keys [compress? compression-level strict?]}
         (merge default-opts opts)]
     (when (and strict? (not (canonical? obj)))
       (throw (ex-info "Value cannot be canonicalized"
                       {:value obj})))
     (let [transit-bytes (transit-encode obj)
           canonical-bytes (json-canonicalize transit-bytes)]
       (if compress?
         (compress/compress canonical-bytes compression-level)
         canonical-bytes)))))

(defn deserialize
  "Deserializes a byte array to a Clojure value.

  Handles both compressed and uncompressed canonical bytes.
  Uses zstd frame detection to determine if decompression is needed.

  Note: The deserialized value may differ from the original in type:
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
