(ns com.latacora.transit-canon.impl.compress
  "Compression utilities for canonical serialization.

  ## Determinism investigation

  This namespace explores whether zstd compression can be deterministic:
  - Same input bytes -> same output bytes
  - Across multiple invocations
  - Across JVM restarts (same version)
  - Across different machines (same zstd version and parameters)

  ## Current findings

  PENDING: Need to verify experimentally with tests.

  Factors that could affect determinism:
  - Dictionary training (we don't use this)
  - Thread count / parallel compression (we use single-threaded)
  - Compression level (fixed)
  - Memory/buffer sizes (determined by input size)
  - Library version (zstd-jni)

  ## API

  Simple compress/decompress that uses fixed parameters for reproducibility."
  (:import
   (com.github.luben.zstd Zstd)))

(def ^:const default-compression-level
  "Default zstd compression level.
  Level 3 provides good balance of speed and compression ratio."
  3)

(defn compress
  "Compress bytes using zstd with fixed parameters.

  Uses single-threaded compression with a fixed compression level
  to maximize determinism. Returns compressed byte array."
  (^bytes [^bytes data]
   (compress data default-compression-level))
  (^bytes [^bytes data compression-level]
   (Zstd/compress data compression-level)))

(defn decompress
  "Decompress zstd-compressed bytes.

  Reads the decompressed size from the zstd frame header.
  Throws if the data is not valid zstd-compressed bytes."
  ^bytes [^bytes compressed]
  (let [decompressed-size (Zstd/decompressedSize compressed)]
    (when (neg? decompressed-size)
      (throw (ex-info "Invalid zstd frame or unknown decompressed size"
                      {:compressed-size (alength compressed)})))
    (Zstd/decompress compressed decompressed-size)))

(defn compressed-size
  "Returns the size of the compressed data for given bytes.
  Useful for deciding whether compression is beneficial."
  ^long [^bytes data compression-level]
  (alength (compress data compression-level)))
