(ns user
  "REPL entry point."
  (:require
   [clojure.repl.deps :as deps] [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [resonate.core :as r]
   [resonate.greeter-test :as g]))

(comment 
  
  (require '[resonate.interop :as i] :reload)
  (require '[resonate.codec :as c] :reload)
  (require '[resonate.core :as r] :reload)
  (require '[resonate.greeter-test :as g] :reload)
  (require '[resonate.serialization-test] :reload)

  (test/run-tests 'resonate.greeter-test)
  (test/run-tests 'resonate.serialization-test)
  
  (def R (r/start! {:url "http://localhost:8001"}))
  (r/register R #'g/format-greeting)
  (r/register R #'g/greeter-workflow {:name :greeter})
  
  (-> (r/run! R (r/id "test-") #'g/greeter-workflow "Echo, var")
      (r/result)) 
  
  (-> (r/run! R (r/id "test-") g/greeter-workflow "Echo, fn")
      (r/result)) 
  
  (-> (r/run! R (r/id "test-") :greeter "Echo, keyword")
      (r/result)) 
  
  (-> (r/run! R (r/id "test-") "greeter" "Echo, string")
      (r/result)) 
  
  (-> (r/rpc! R (r/id "test-") "greeter" "Echo, string")
      (r/result))
  
  (r/stop! R)
  
  (repl/clear) 
  
  (try
    (repl/refresh-all)
    (catch Exception e
      (.printStackTrace e)))

  (deps/sync-deps)

  )
