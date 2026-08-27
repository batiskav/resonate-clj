(ns user
  "REPL entry point."
  (:require
   [clojure.java.io :as io]
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

  (test/run-tests 'resonate.greeter-test)
  
  (def R (r/start! {:url "http://localhost:8001"}))
  (r/register R #'g/format-greeting)
  (r/register R #'g/greeter-workflow)
  
  (try 
    (-> (r/run R (r/id "greet-") #'g/greeter-workflow "Echo")
        (r/result))
    (catch Exception e
      (.printStackTrace e)))
  
  (r/stop! R)
  
  (repl/clear)
  (repl/refresh-all)

  )
