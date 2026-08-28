(ns resonate.codec
  "Clojure data over the wire.

   Everything crossing the wire is JSON, and `Codec.decode` is `readValue(.., Object.class)`.
   A Clojure signature carries no declared parameter types, so `Durable` takes its passthrough
   branch and hands whatever that produced straight to the function -- which is why teaching
   the SDK's own mapper is what reaches a durable function's arguments. Nothing we could do at
   our own boundary gets there.

   `Codec.MAPPER` is private static final with no builder knob, so we reach it reflectively and
   register jsonista's Clojure codec on it: keywords, symbols and ratios out; persistent maps
   and vectors in. One hook, both directions, no hand-written serializers.

   Requiring this namespace is what installs the codec -- `resonate.resonate` does so on your
   behalf."
  (:import
   [com.fasterxml.jackson.core JsonParser]
   [com.fasterxml.jackson.databind BeanProperty DeserializationContext JsonDeserializer
    ObjectMapper]
   [com.fasterxml.jackson.databind.deser ContextualDeserializer]
   [com.fasterxml.jackson.databind.module SimpleModule]
   [io.resonatehq.resonate Codec]
   [java.lang.reflect Field]
   [jsonista.jackson FunctionalKeyDeserializer KeywordSerializer
    PersistentHashMapDeserializer PersistentVectorDeserializer
    RatioSerializer SymbolSerializer]))

(def ^:private ^Field f-mapper
  (doto (.getDeclaredField Codec "MAPPER") (.setAccessible true)))

(def ^:private ^ObjectMapper mapper (.get f-mapper nil))

;; `Codec/deserializeError` is the one place the SDK reads a decoded payload with String keys
;; (`map.get("message")`, `map.get("__java_serialized")`), so an error envelope must stay
;; string-keyed or a rejected promise loses its cause and surfaces as "unknown error".
;;
;; The discriminator is a property of the whole map, not of a key: `Codec/encodeError` stamps
;; every envelope `__type: "error"`. Deciding per key -- reserving the *names* `message` and
;; `__java_serialized` globally -- would collide with ordinary data, and `{:message ...}` is
;; far too common a shape to quietly hand back string-keyed.

(defn- error-envelope? [m]
  (= "error" (get m :__type)))

(defn- string-keys [m]
  (persistent! (reduce-kv (fn [acc k v] (assoc! acc (subs (str k) 1) v)) (transient {}) m)))

(defn- envelope-aware
  "jsonista's map reader, with the SDK's own error envelope handed back string-keyed.

   Jackson resolves a deserializer's key and value readers lazily through `createContextual`,
   so a wrapper has to forward that call and re-wrap what it returns -- calling `deserialize`
   on an uncontextualized delegate NPEs on its null key reader."
  [^JsonDeserializer delegate]
  (proxy [JsonDeserializer ContextualDeserializer] []
    (createContextual [^DeserializationContext ctx ^BeanProperty prop]
      (envelope-aware (.createContextual ^ContextualDeserializer delegate ctx prop)))
    (deserialize [^JsonParser p ^DeserializationContext ctx]
      (let [m (.deserialize delegate p ctx)]
        (cond-> m (error-envelope? m) string-keys)))))

;; defonce: registering a module mutates shared state; a namespace reload must not repeat it.
(defonce installed?
  (do (.registerModule mapper
                       (doto (SimpleModule. "resonate-clj")
                         (.addSerializer clojure.lang.Keyword (KeywordSerializer. false))
                         (.addSerializer clojure.lang.Symbol (SymbolSerializer.))
                         (.addSerializer clojure.lang.Ratio (RatioSerializer.))
                         ;; Map keys take a separate path in Jackson; a value serializer
                         ;; never sees them. `true` is KeywordSerializer's writeFieldName mode.
                         (.addKeySerializer clojure.lang.Keyword (KeywordSerializer. true))
                         (.addKeyDeserializer Object (FunctionalKeyDeserializer. keyword))
                         (.addDeserializer java.util.Map (envelope-aware (PersistentHashMapDeserializer.)))
                         (.addDeserializer java.util.List (PersistentVectorDeserializer.))))
      true))
