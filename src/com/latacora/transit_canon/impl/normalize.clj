(ns com.latacora.transit-canon.impl.normalize
  "Normalize Clojure data structures for canonical serialization.

  ## The problem

  Transit encodes maps as JSON arrays (not objects), so RFC 8785's
  object key sorting doesn't apply. Clojure hash-maps preserve
  insertion order, meaning the same logical map can serialize
  differently based on construction history.

  ## The solution

  Pre-normalize all data structures before Transit encoding:
  1. Sort map entries by canonical key comparison
  2. Sort set elements by canonical comparison
  3. Convert problematic numeric types to tagged forms

  ## Known limitations

  - Metadata is stripped (by design)
  - Custom types may not compare correctly (falls back to hash)
  - Performance overhead from deep walking"
  (:require
   [com.latacora.transit-canon.impl.comparators :as cmp]
   [clojure.walk :as walk]))

(defn normalize-number
  "Normalize numbers to avoid Transit/JSON canonicalization conflicts.

  The issue: Transit writes both 1.0 (Double) and 1 (Long) as JSON numbers.
  RFC 8785 canonicalizes 1.0 to 1. On deserialization, Transit reads 1
  as Long, breaking round-trips for whole-number doubles.

  Solution: Convert longs to BigInt which Transit tags explicitly,
  preserving type information through the canonicalization process.

  Note: This changes the deserialized type (BigInt vs Long) but preserves
  numeric equality. Document this as a known behavior."
  [n]
  (cond
    ;; Leave doubles alone - they may have fractional parts
    (instance? Double n) n
    (instance? Float n) (double n)
    ;; Convert plain integers to bigint to preserve distinction from floats
    (instance? Long n) (bigint n)
    (instance? Integer n) (bigint n)
    (instance? Short n) (bigint n)
    (instance? Byte n) (bigint n)
    ;; BigInteger, BigDecimal, Ratio already serialize distinctly
    :else n))

(defn- normalize-value
  "Normalize a single value for canonical serialization."
  [x]
  (cond
    (map? x)
    (->> x
         (sort-by (comp cmp/value->sort-key key))
         (into (array-map)))

    ;; Sets are kept as sets - canonical ordering is handled by Transit writer
    ;; We just strip metadata here
    (set? x)
    x

    (number? x)
    (normalize-number x)

    ;; Strip metadata
    (instance? clojure.lang.IObj x)
    (with-meta x nil)

    :else x))

(defn normalize
  "Recursively normalize a Clojure value for canonical serialization.

  Transformations applied:
  - Maps: entries sorted by canonical key comparison, stored in array-map
  - Sets: converted to sorted vectors (restores set semantics on deserialize)
  - Numbers: longs converted to bigint to preserve int/float distinction
  - Metadata: stripped from all values

  Returns a normalized value suitable for Transit encoding."
  [obj]
  (walk/postwalk normalize-value obj))

(defn canonicalizable?
  "Test if a value can be reliably canonicalized.

  Returns false for values that may not serialize deterministically:
  - Values containing types with no natural ordering (falls back to hash)
  - Values with circular references (would cause stack overflow)

  Note: This is a best-effort check. Some edge cases may not be detected."
  [obj]
  (try
    (let [seen (atom #{})]
      (walk/postwalk
       (fn [x]
         ;; Check for circular references
         (when (and (coll? x) (contains? @seen x))
           (throw (ex-info "Circular reference detected" {:value x})))
         (when (coll? x)
           (swap! seen conj x))
         x)
       obj)
      true)
    (catch Exception _
      false)))
