(ns user
  "REPL entry point."
  (:require
   [clojure.repl.deps :as deps] [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [resonate.core :as r]
   [resonate.greeter-test :as g]
   [resonate.data-test :as d]))

(comment 
  
  (do
    (require '[resonate.interop :as i] :reload)
    (require '[resonate.codec :as c] :reload)
    (require '[resonate.core :as r] :reload)
    (require '[resonate.greeter-test :as g] :reload)
    (require '[resonate.data-test :as d] :reload)
    (require '[resonate.serialization-test] :reload))

  (test/run-tests 'resonate.greeter-test)
  (test/run-tests 'resonate.serialization-test)
  
  (def R (r/start! {:url "http://localhost:8001"}))
  (r/register R g/format-greeting {:replace? true :name :d/format})
  (r/register R #'g/greeter-workflow {:replace? true :name :wf/greeter})
  (r/register R #'g/rpc-workflow {:name :rpc/greeter})
  (r/register R #'d/data-workflow {:name :wf/data})

  (-> (r/run! R (r/id "test-") #'g/greeter-workflow "Echo")
      (r/result))
  
  (-> (r/run! R (r/id "test-") g/greeter-workflow "Echo")
      (r/result))
  
  (-> (r/run! R (r/id "test-") :wf/greeter "Echo")
      (r/result))
  
  (time ;; about 90ms
   (-> (r/run! R (r/id "test-") :wf/greeter "Echo")
       (r/result)))
  
  (time ;; about 360ms
   (-> (r/rpc! R (r/id "test-") :rpc/greeter "Echo")
       (r/result)))
  
  (-> (r/run! R (r/id "data-") :wf/data {:foo "bar"})
      (r/result))
  
  (r/stop! R)

  ;; REPL tools
  
  (repl/clear) 
  (repl/refresh-all) 
  (deps/sync-deps) 

  )
