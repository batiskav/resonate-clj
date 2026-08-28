(ns resonate.data-test
  (:require
   [clojure.test :refer [deftest is use-fixtures testing]]
   [resonate.core :as r]))

(defn enrich-data
  "A leaf function: computation only, no durable operations of its own."
  [_ data]
  (assoc data :enriched true))

(defn data-workflow
  "A workflow: orchestrates children through ctx. Replays from the top on resume,
  so it performs no side effects outside the `run` calls."
  [ctx my-data]
  (-> (r/run ctx #'enrich-data my-data)
      (r/await)))
