(ns resonate.serialization-test
  "The wire codec, exercised directly.

  Loading `resonate.resonate` registers jsonista's Clojure codec on the SDK's own
  `Codec/MAPPER`, so these run against the real encoder and decoder every payload goes
  through -- arguments, return values and errors alike. No server required."
  (:require [resonate.resonate]
            [clojure.test :refer [deftest is testing]])
  (:import [io.resonatehq.resonate Codec Codec$NoopEncryptor]
           [java.util Base64]))

(def codec (Codec. (Codec$NoopEncryptor.)))

(defn round-trip
  "Clojure value -> promise Value -> Clojure value, the way a durable function's arguments
  and return value travel."
  [x]
  (.decode codec (.encode codec x)))

(defn json-of
  "The JSON actually stored in the promise, so a test can assert the wire format itself and
  not merely that we can read our own writing."
  ^String [x]
  (String. (.decode (Base64/getDecoder) ^String (.data (.encode codec x)))))

;; -- keys ---------------------------------------------------------------------

(deftest keyword-keys-round-trip
  (testing "keywords lose the colon on the way out and come back as keywords"
    (is (= "{\"name\":\"ada\"}" (json-of {:name "ada"})))
    (is (= {:name "ada"} (round-trip {:name "ada"})))))

(deftest namespaced-keys-keep-their-namespace
  (testing "a namespace is part of the key, not decoration"
    (is (= "{\"a/b\":1}" (json-of {:a/b 1})))
    (is (= {:a/b 1} (round-trip {:a/b 1})))))

(deftest string-keys-stay-strings-on-the-way-out
  (testing "a string key is written as-is, but decodes to a keyword like any other"
    (is (= "{\"plain\":1}" (json-of {"plain" 1})))
    (is (= {:plain 1} (round-trip {"plain" 1})))))

;; -- values -------------------------------------------------------------------

(deftest keyword-values-become-strings
  (testing "JSON cannot tell a keyword from the string it was written as, so only keys
            round-trip -- values come back as strings"
    (is (= "{\"k\":\"v\"}" (json-of {:k :v})))
    (is (= {:k "v"} (round-trip {:k :v})))))

(deftest symbols-and-ratios-are-valid-json
  (testing "Jackson alone bean-serializes a Symbol and emits a Ratio as 1/3, which is not
            JSON and cannot be read back; the codec renders both properly"
    (is (= "{\"sym\":\"a/b\"}" (json-of {:sym 'a/b})))
    (is (= {:sym "a/b"} (round-trip {:sym 'a/b})))
    (is (= "{\"r\":0.3333333333333333}" (json-of {:r 1/3})))
    (is (= {:r 0.3333333333333333} (round-trip {:r 1/3})))))

(deftest collections-become-arrays
  (testing "vectors, lists, seqs and sets all serialize as JSON arrays"
    (is (= "{\"v\":[1,2],\"l\":[3],\"s\":[\"only\"]}"
           (json-of {:v [1 2] :l (list 3) :s #{:only}})))
    (is (= {:v [1 2] :l [3] :s ["only"]}
           (round-trip {:v [1 2] :l (list 3) :s #{:only}})))))

(deftest scalars-and-nil
  (testing "scalars pass through untouched"
    (is (= {:s "x" :i 1 :d 1.5 :t true :f false :n nil}
           (round-trip {:s "x" :i 1 :d 1.5 :t true :f false :n nil})))
    (is (nil? (round-trip nil)))))

(deftest nesting-is-recursive
  (testing "the codec applies all the way down, not just at the top level"
    (is (= {:a [{:b/c [{:d "e"}]}]}
           (round-trip {:a [{:b/c [{:d :e}]}]})))))

;; -- shapes -------------------------------------------------------------------

(deftest decoded-values-are-clojure-collections
  (testing "the SDK decodes straight into persistent collections, which is what lets a
            durable function receive Clojure data without any wrapper"
    (let [decoded (round-trip {:m {:x 1} :v [1 2]})]
      (is (instance? clojure.lang.IPersistentMap decoded))
      (is (instance? clojure.lang.IPersistentMap (:m decoded)))
      (is (instance? clojure.lang.IPersistentVector (:v decoded))))))

;; -- the error envelope -------------------------------------------------------

(deftest error-envelope-keys-stay-strings
  (testing "the SDK reads its own error envelope back with String keys, so keywordizing one
            would cost a rejected promise its cause; those three names are reserved"
    (let [envelope {"__type" "error"
                    "message" "boom"
                    "__java_serialized" "rO0ABXNy"}
          decoded (round-trip envelope)]
      (is (= envelope decoded))
      (is (= "boom" (.get ^java.util.Map decoded "message"))
          "Codec/deserializeError looks the message up by String key"))))

(deftest reserved-names-only-apply-to-those-keys
  (testing "everything else keywordizes as usual, including keys that merely look internal"
    (is (= {:type "x" :messages ["a"] :__other 1}
           (round-trip {:type "x" :messages ["a"] :__other 1})))))
