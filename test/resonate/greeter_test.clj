(ns resonate.greeter-test
  "Example durable functions: plain defns, ctx first.

  Smoke test against a local Resonate instance (docker compose up), exercising the
  library through its own wrapper functions rather than raw interop."
  (:require [resonate.resonate :as r]
            [clojure.test :refer [deftest is use-fixtures testing]])
  (:import [io.resonatehq.resonate Retry$Never]))

(def url "http://localhost:8001")

(defn format-greeting
  "A leaf function: computation only, no durable operations of its own."
  [_ name]
  (str "hello, " name "!"))

(defn greeter-workflow
  "A workflow: orchestrates children through ctx. Replays from the top on resume,
  so it performs no side effects outside the `run` calls."
  [ctx name]
  (-> (r/run-in ctx #'format-greeting name)
      (r/await)))

(def ^:dynamic *resonate* nil)

(defn with-resonate
  "One instance for the whole suite; each `->Resonate` starts a long-polling worker."
  [f]
  (let [engine (r/->Resonate {:url url})]
    (try
      (r/register engine #'format-greeting {:name "format-greeting"
                                            :retry (Retry$Never.)})
      (r/register engine #'greeter-workflow)
      (binding [*resonate* engine] (f))
      (finally (r/stop! engine)))))

(use-fixtures :once with-resonate)

(deftest workflow-with-child
  (testing "a workflow dispatches a child through ctx and awaits it"
    (is (= "hello, Ada!"
           (-> (r/run *resonate* (r/id "greet-") #'greeter-workflow "Ada")
               (r/result))))))

(deftest same-id-rejoins
  (testing "reusing a promise id returns the first result instead of re-running"
    (let [id (r/id "greet")
          once (-> (r/run *resonate* id #'format-greeting "first") (r/result))
          twice (-> (r/run *resonate* id #'format-greeting "second") (r/result))]
      (is (= "hello, first!" once))
      (is (= once twice) "the second run must rejoin the existing promise"))))

(deftest name-resolution
  (testing "a run can name its target by string"
    (is (= "hello, by-name!"
           (-> (r/run *resonate* (r/id) "format-greeting" "by-name")
               (r/result))))))

(deftest method-resolution 
  (testing "a run can refer to the fn itself"
    (is (= "hello, by-fn!"
           (-> (r/run *resonate* (r/id) greeter-workflow "by-fn")
               (r/result))))))
