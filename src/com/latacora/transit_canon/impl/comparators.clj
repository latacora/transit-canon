(ns com.latacora.transit-canon.impl.comparators
  "Comparators for canonical ordering of heterogeneous values.

  ## Approach: serialize-once string comparison

  For sorting map keys and set elements, we:
  1. Serialize each element to Transit JSON string once
  2. Sort by string comparison (lexicographic)
  3. Build the sorted structure

  This is efficient because each element is serialized exactly once
  during the sort, not O(n log n) times as with a naive comparator."
  (:require
   [cognitect.transit :as transit])
  (:import
   (org.erdtman.jcs JsonCanonicalizer)
   (java.io ByteArrayOutputStream)))

(defn value->sort-key
  "Serialize a value to a canonical string for sorting.
  Uses Transit JSON with RFC 8785 canonicalization."
  ^String [obj]
  (let [out (ByteArrayOutputStream.)]
    (-> out
        (transit/writer :json)
        (transit/write obj))
    (-> out .toByteArray JsonCanonicalizer. .getEncodedString)))

(defn canonical-compare
  "A total ordering comparator for heterogeneous Clojure values.

  Serializes values to canonical strings and compares lexicographically.
  For better performance during sorting, prefer using `sort-by` with
  `value->sort-key` as the key function - this serializes each element
  only once instead of O(n log n) times.

  Returns negative if a < b, zero if a = b, positive if a > b."
  [a b]
  (compare (value->sort-key a) (value->sort-key b)))
