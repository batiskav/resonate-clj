(ns user
  "REPL entry point."
  (:require
   [clojure.repl.deps :as deps]
   [clojure.test :as test]
   [clojure.tools.namespace.repl :as repl]
   [resonate.resonate :as r]
   [resonate.greeter-test :as g])
  (:import 
   [io.resonatehq.resonate Retry$Never]))

(comment 
  
  (require '[resonate.resonate :as r] :reload)
  (require '[resonate.greeter-test :as g] :reload)
  (require '[resonate.serialization-test] :reload)

  (test/run-tests 'resonate.greeter-test)
  (test/run-tests 'resonate.serialization-test)
  
  (def R (r/start! {:url "http://localhost:8001"}))
  (r/register R #'g/format-greeting)
  (r/register R #'g/greeter-workflow)

  (try
    (-> (r/rpc R (r/id "greet-") #'g/greeter-workflow {:a 123})
        (r/result))
    (catch Exception e
      (.printStackTrace e)))
  
  (r/stop! R)
  
  (repl/clear)
  (repl/refresh-all)

  (deps/sync-deps)

  )
