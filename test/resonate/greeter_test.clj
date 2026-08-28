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
  (-> (r/run ctx #'format-greeting name)
      (r/await)))

(defn rpc-workflow
  "The same shape as `greeter-workflow`, but dispatching the child over the wire rather
  than running it inline on this instance."
  [ctx name]
  (-> (r/rpc ctx #'format-greeting name)
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
      (r/register engine #'rpc-workflow)
      (binding [*resonate* engine] (f))
      (finally (r/stop! engine)))))

(use-fixtures :once with-resonate)

(deftest workflow-with-child
  (testing "a workflow dispatches a child through ctx and awaits it"
    (is (= "hello, Ada!"
           (-> (r/run! *resonate* (r/id "greet-") #'greeter-workflow "Ada")
               (r/result))))))

(deftest same-id-rejoins
  (testing "reusing a promise id returns the first result instead of re-running"
    (let [id (r/id "greet")
          once (-> (r/run! *resonate* id #'format-greeting "first") (r/result))
          twice (-> (r/run! *resonate* id #'format-greeting "second") (r/result))]
      (is (= "hello, first!" once))
      (is (= once twice) "the second run must rejoin the existing promise"))))

(deftest name-resolution
  (testing "a run can name its target by string"
    (is (= "hello, by-name!"
           (-> (r/run! *resonate* (r/id) "format-greeting" "by-name")
               (r/result))))))

(deftest method-resolution 
  (testing "a run can refer to the fn itself"
    (is (= "hello, by-fn!"
           (-> (r/run! *resonate* (r/id) greeter-workflow "by-fn")
               (r/result))))))

;; -- rpc ----------------------------------------------------------------------
;;
;; `run` executes here; `rpc` hands the task to whichever worker claims it. With one
;; instance registered that is this process again, so these assert the dispatch path
;; rather than distribution.

(deftest rpc-by-var
  (testing "rpc dispatches a registered function named by its var"
    (is (= "hello, Echo!"
           (-> (r/rpc! *resonate* (r/id "rpc-") #'format-greeting "Echo")
               (r/result))))))

(deftest rpc-by-name
  (testing "rpc takes a String, which need not be registered locally to be dispatched"
    (is (= "hello, by-name!"
           (-> (r/rpc! *resonate* (r/id) "format-greeting" "by-name")
               (r/result))))))

(deftest rpc-in-child
  (testing "a workflow dispatches a child over the wire and awaits it"
    (is (= "hello, Echo!"
           (-> (r/run! *resonate* (r/id) #'rpc-workflow "Echo")
               (r/result))))))

(deftest rpc-same-id-rejoins
  (testing "reusing a promise id rejoins rather than dispatching again"
    (let [id (r/id "rpc-rejoin")
          once (-> (r/rpc! *resonate* id #'format-greeting "first") (r/result))
          twice (-> (r/rpc! *resonate* id #'format-greeting "second") (r/result))]
      (is (= "hello, first!" once))
      (is (= once twice)))))

(deftest rpc-unregistered-fn-is-rejected
  (testing "a var carries no name of its own on the wire, so it must be registered here"
    (let [unregistered (fn [_ctx x] x)]
      (is (thrown? Exception (r/rpc! *resonate* (r/id) unregistered "x"))))))
