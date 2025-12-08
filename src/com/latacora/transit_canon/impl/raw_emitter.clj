(ns com.latacora.transit-canon.impl.raw-emitter
  "Custom Transit emitter with raw JSON emission support.

  This namespace provides a JsonEmitter subclass (via gen-class) that can
  emit pre-serialized JSON strings directly, enabling the decorate-sort-undecorate
  pattern for canonical serialization without double-serialization.

  ## Why gen-class instead of proxy?

  Java's `proxy` cannot intercept internal method calls within a class hierarchy.
  When AbstractEmitter.emitArray() calls `this.marshal()`, it bypasses the proxy.
  gen-class creates a true Java subclass where virtual dispatch works correctly.

  ## Usage

  1. Wrap pre-serialized values in RawJson
  2. Use canonical-emitter to create the emitter
  3. When marshal() encounters RawJson, it emits the string directly"
  (:require
   [cognitect.transit :as transit])
  (:import
   [com.cognitect.transit.impl WriteHandlerMap WriteCache]
   [com.fasterxml.jackson.core JsonGenerator JsonFactory]
   [java.io OutputStream]))

;; Marker type for pre-serialized JSON that should be emitted verbatim
(deftype RawJson [^String json-str]
  Object
  (toString [_] (str "RawJson[" json-str "]")))

(defn raw-json
  "Create a RawJson wrapper for a pre-serialized JSON string."
  [^String s]
  (RawJson. s))

(defn raw-json?
  "Test if x is a RawJson instance."
  [x]
  (instance? RawJson x))

;; gen-class for a JsonEmitter subclass that handles RawJson
;; The :exposes-methods allows us to call the superclass marshal
(gen-class
  :name com.latacora.transit_canon.impl.CanonicalEmitter
  :extends com.cognitect.transit.impl.JsonEmitter
  :constructors {[com.fasterxml.jackson.core.JsonGenerator
                  com.cognitect.transit.impl.WriteHandlerMap
                  com.cognitect.transit.WriteHandler]
                 [com.fasterxml.jackson.core.JsonGenerator
                  com.cognitect.transit.impl.WriteHandlerMap
                  com.cognitect.transit.WriteHandler]}
  :exposes {gen {:get getGen}}
  :exposes-methods {marshal superMarshal}
  :prefix "emitter-")

(defn emitter-marshal
  "Override marshal to handle RawJson specially.
  When we encounter RawJson, emit its string directly without re-serialization."
  [this o as-map-key ^WriteCache cache]
  (if (instance? RawJson o)
    ;; Emit pre-serialized JSON directly
    (.writeRawValue (.getGen this) ^String (.json-str ^RawJson o))
    ;; Delegate to parent for normal values
    (.superMarshal this o as-map-key cache)))

(defn- clj-map->java-map
  "Convert a Clojure map to a java.util.HashMap.
  Required because WriteHandlerMap constructor expects Java maps."
  ^java.util.HashMap [m]
  (let [result (java.util.HashMap.)]
    (doseq [[k v] m]
      (.put result k v))
    result))

(defn- build-handler-map
  "Build a WriteHandlerMap from custom handlers merged with Transit defaults."
  ^WriteHandlerMap [custom-handlers]
  (let [clj-handlers @#'cognitect.transit/default-write-handlers
        merged (if (seq custom-handlers)
                 (merge clj-handlers custom-handlers)
                 clj-handlers)
        java-handlers (clj-map->java-map merged)]
    (WriteHandlerMap. java-handlers)))

(defn canonical-emitter
  "Create a CanonicalEmitter that supports raw JSON emission.

  Options:
  - :handlers - Map of types to WriteHandler instances
  - :default-handler - Handler for unknown types"
  [^JsonGenerator gen {:keys [handlers default-handler]}]
  (let [handler-map (build-handler-map handlers)]
    (com.latacora.transit_canon.impl.CanonicalEmitter.
     gen handler-map default-handler)))

;; ---------------------------------------------------------------------------
;; Decorated maps for decorate-sort-undecorate pattern
;; ---------------------------------------------------------------------------

;; Thread-local ByteArrayOutputStream for efficient key serialization.
(def ^:private ^ThreadLocal thread-local-out
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] (java.io.ByteArrayOutputStream. 256)))))

(defn- escape-json-string
  "Escape a string for JSON output. Handles common escape sequences."
  ^String [^String s]
  (let [sb (StringBuilder. (+ (count s) 2))]
    (.append sb \")
    (dotimes [i (count s)]
      (let [c (.charAt s i)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \newline (.append sb "\\n")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          (.append sb c))))
    (.append sb \")
    (.toString sb)))

(defn- fast-transit-json
  "Fast Transit JSON serialization for common key types.
  Handles keywords, strings, numbers, and symbols directly without
  creating a Transit writer. Falls back to full serialization for
  complex types."
  ^String [obj]
  (cond
    ;; Keywords: ["~#'","~:keyword"] or ["~#'","~:ns/name"]
    (keyword? obj)
    (str "[\"~#'\",\"~:" (if-let [ns (namespace obj)]
                          (str ns "/" (name obj))
                          (name obj)) "\"]")

    ;; Strings: ["~#'","string"] with escaping
    (string? obj)
    (str "[\"~#'\"," (escape-json-string obj) "]")

    ;; Integers -> BigInt: ["~#n","123"]
    (integer? obj)
    (str "[\"~#n\",\"" obj "\"]")

    ;; Symbols: ["~#'","~$symbol"] or ["~#'","~$ns/name"]
    (symbol? obj)
    (str "[\"~#'\",\"~$" (if-let [ns (namespace obj)]
                          (str ns "/" (name obj))
                          (name obj)) "\"]")

    ;; Fall back to full Transit serialization for complex types
    :else
    (let [^java.io.ByteArrayOutputStream out (.get thread-local-out)]
      (.reset out)
      (transit/write (transit/writer out :json) obj)
      (String. (.toByteArray out) java.nio.charset.StandardCharsets/UTF_8))))

(defn decorate
  "Decorate a value for the decorate-sort-undecorate pattern.

  Uses pr-str as the sort key (fast, deterministic for all Clojure values).
  The actual Transit JSON is computed lazily during undecorate to avoid
  paying serialization cost for discarded values.

  Note: pr-str ordering differs from Transit JSON ordering, but both are
  deterministic. The final output is still valid canonical Transit."
  [x]
  {::value x
   ::sort-key (pr-str x)})

(defn decorated-sort-key
  "Get the sort key from a decorated map."
  ^String [d]
  (::sort-key d))

(defn undecorate->raw
  "Convert a decorated map to RawJson for emission.
  This is the final step of decorate-sort-undecorate:
  serialize the original value to Transit JSON for raw emission."
  [d]
  (raw-json (fast-transit-json (::value d))))
