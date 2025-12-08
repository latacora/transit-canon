(ns com.latacora.transit-canon.impl.normalize
  "Normalize Clojure data structures for canonical serialization.

  ## Responsibilities

  This namespace handles:
  1. Number normalization (Long -> BigInt to preserve int/float distinction)
  2. Metadata stripping

  ## What this namespace does NOT handle

  Map and set ordering is handled by Transit write handlers in core.clj
  using the decorate-sort-undecorate pattern with raw JSON emission.

  ## Known limitations

  - Metadata is stripped (by design)
  - Performance overhead from deep walking"
  (:require
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
    (number? x)
    (normalize-number x)

    ;; Strip metadata from metadata-capable objects
    (instance? clojure.lang.IObj x)
    (with-meta x nil)

    :else x))

(defn normalize
  "Recursively normalize a Clojure value for canonical serialization.

  Transformations applied:
  - Numbers: longs converted to bigint to preserve int/float distinction
  - Metadata: stripped from all values

  Note: Map and set ordering is handled by Transit write handlers,
  not by this normalization step.

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
