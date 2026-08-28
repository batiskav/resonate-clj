# resonate-clj
Clojure wrapper for the [Resonate Java SDK](https://docs.resonatehq.io/develop/java).

Very early alpha stage, no guarantees.

## Example

```clojure

(require '[resonate.core :as r] :reload)
(require '[resonate.greeter-test :as g] :reload)

(def R (r/start! {:url "http://localhost:8001"}))
(r/register R #'g/format-greeting)
(r/register R #'g/greeter-workflow {:name :wf/greeter})

(-> (r/run! R (r/id "test-") :wf/greeter "Echo")
    (r/result))
;; => "Hello, Echo!"

(r/stop! R)

```

## Roadmap

- Proper build pipeline, make builds available on [Clojars](https://clojars.org/).
- Clojure macro `defdurable` that enables auto-registration of durable functions.
- Non-trivial example web app with HITL workflow simulation.
- Non-trivial example with heterogeneous services (Clojure, Python).
- Non-trivial example with durable loop interpreting behaviour defined in a DSL ([BThreads](https://thomascothran.tech/2024/10/a-new-paradigm/), [Statecharts](https://github.com/fulcrologic/statecharts), etc).

## License

Apache-2.0 — see [Resonate Java SDK license](https://github.com/resonatehq/resonate-sdk-java/blob/main/LICENSE).
