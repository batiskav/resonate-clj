(ns resonate.resonate
  (:refer-clojure :exclude [await])
  (:require
   [clojure.string :as str])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.fasterxml.jackson.databind.module SimpleModule]
           [jsonista.jackson FunctionalKeyDeserializer FunctionalKeywordSerializer
            KeywordSerializer PersistentHashMapDeserializer PersistentVectorDeserializer
            RatioSerializer SymbolSerializer]
           [io.resonatehq.resonate Codec Codec$Encryptor Context Context$ResonateFuture
            Handle$ResonateHandle Heartbeat$Hb Network Registry
            Resonate Resonate$Builder Retry$RetryPolicy]
           [java.lang.reflect InvocationTargetException Method Modifier Type]
           [java.time Duration]))

(defn method-of
  "The `java.lang.reflect.Method` behind a Clojure fn: the static `invokeStatic`
   the compiler emits for every top-level `defn`.
   
   Accepts a var or a fn value. 
   Throws unless the fn has exactly one `invokeStatic`, i.e. it is neither a closure nor multi-arity."
  ^Method [f]
  (let [target (if (var? f) @f f)
        c (class target)
        statics (filterv (fn [^Method m]
                           (and (= "invokeStatic" (.getName m))
                                (Modifier/isStatic (.getModifiers m))))
                         (.getDeclaredMethods c))]
    (when-not (= 1 (count statics))
      (throw (ex-info (if (empty? statics)
                        "no invokeStatic: not a top-level defn (closure, reify, or interop fn)"
                        "ambiguous invokeStatic: multi-arity fn")
                      {:fn c :candidates (mapv str statics)})))
    (first statics)))

(defn- fq-name
  "The default registration name for a var: its fully qualified symbol."
  ^String [f]
  (when (var? f)
    (let [m (meta f)]
      (str (ns-name (:ns m)) "/" (:name m)))))

;; Registry interop

(def ObjectArray (class (object-array 0)))
(def RegistryKey (Class/forName "io.resonatehq.resonate.Registry$Key"))
(def ContextState (Class/forName "io.resonatehq.resonate.Context$State"))

(def f-registry 
  (doto (.getDeclaredField Resonate "registry") 
    (.setAccessible true)))

(def m-run-resolved 
  (let [param-types (into-array Class [String String Integer/TYPE ObjectArray])]
    (doto (.getDeclaredMethod Resonate "runResolved" param-types)
      (.setAccessible true))))

(def m-register
  (let [param-types (into-array Class [String Method Integer/TYPE Retry$RetryPolicy])]
    (doto (.getDeclaredMethod Registry "register" param-types)
      (.setAccessible true))))

(def m-reverse
  (let [param-types (into-array Class [Method])]
    (doto (.getDeclaredMethod Registry "reverse" param-types)
      (.setAccessible true))))

(def by-key-field
  (doto (.getDeclaredField Registry "byKey") 
    (.setAccessible true)))
                                                                     
(def f-state
  (doto (.getDeclaredField Context "state")
    (.setAccessible true)))

(def m-run-method
  (let [param-types (into-array Class [Method ObjectArray])]
    (doto (.getDeclaredMethod Context "run" param-types)
      (.setAccessible true))))

(def m-rpc-resolved
  (let [param-types (into-array Class [String String Integer/TYPE Type ObjectArray])]
    (doto (.getDeclaredMethod Resonate "rpcResolved" param-types)
      (.setAccessible true))))

(def m-rpc-method
  (let [param-types (into-array Class [Method ObjectArray])]
    (doto (.getDeclaredMethod Context "rpc" param-types)
      (.setAccessible true))))

(def f-state-registry
  (doto (.getDeclaredField ContextState "registry")
    (.setAccessible true)))

(def c-key
  (let [param-types (into-array Class [String Integer/TYPE])]
    (doto (.getDeclaredConstructor RegistryKey param-types)
      (.setAccessible true))))

(defn ->Key [name version]
  (.newInstance c-key (object-array [name (int version)])))

(defn get-registry
  "The Registry behind a Resonate instance or a Context."
  ^Registry [target]
  (condp instance? target
    Resonate (.get f-registry target)
    Context (.get f-state-registry (.get f-state target))
    (throw (ex-info "no registry: expected a Resonate instance or a Context"
                    {:target target :class (class target)}))))

(defn- invoke!
  "Reflective `Method.invoke`, unwrapping InvocationTargetException so the SDK's own error
   surfaces instead of a bare wrapper with a null message. `args` fills the varargs slot,
   which Clojure interop never synthesizes."
  [^Method m target args]
  (try
    (.invoke m target (object-array args))
    (catch InvocationTargetException e
      (throw (.getCause e)))))

(defn get-by-method
  [^Registry registry ^Method method]
  (invoke! m-reverse registry [method]))
 
(defn check-arity!
  "Durable functions are dispatched by exact arity: the SDK strips `ctx` by position and
   binds the rest one to one, so there is no padding and no spreading.

   The ceiling is Clojure's, not Resonate's -- a fn takes at most 20 parameters, one of
   which is `ctx`, so a durable function takes at most 19 arguments. (The SDK's own
   `Fn.F0..F5` ladder caps Java callers at 5, but nothing on this path goes through it.)

   A variadic `[ctx & more]` is not variadic here: it compiles to `invokeStatic(Object,
   ISeq)`, so the SDK sees exactly one parameter typed ISeq -- and being non-Object it is
   coerced rather than passed through, which Jackson cannot do. Rejected outright."
  [^Method method args]
  (let [types (.getParameterTypes method)
        expected (dec (alength types))
        got (count args)
        owner (.getName (.getDeclaringClass method))]
    (when (and (pos? expected) (= clojure.lang.ISeq (aget types (dec (alength types)))))
      (throw (ex-info (str owner ": variadic durable functions are not supported"
                           " -- the SDK binds arguments positionally against a fixed signature")
                      {:method (str method)})))
    (when-not (= expected got)
      (throw (ex-info (str owner ": expected " expected " argument(s), got " got)
                      {:expected expected :got got :method (str method)})))))

(defn get-key 
  [target ?ref]
  (let [registry (get-registry target)
        method (method-of ?ref)
        key (get-by-method registry method)]
    (when-not key
      (throw (ex-info "not registered: no reverse entry for this function"
                      {:ref ?ref :method (str method)})))
    [(.name key) (.version key)]))

(defn- kw->str ^String [k] (subs (str k) 1))

(defn- coerce 
  "Coerces kw into str, var into fn. Returns input verbatim when out of options."
  [?ref]
  (cond
    (string? ?ref) (if (str/blank? ?ref) nil ?ref)
    (keyword? ?ref) (kw->str ?ref)
    (var? ?ref) (deref ?ref)
    :else ?ref))

(defn unregister
  "Drop `(name, version)` from `r`'s registry, so it can be registered again.

   Only `byKey` is cleared; `register` overwrites the policy and reverse-lookup
   entries unconditionally. Returns the Resonate instance `r`."
  (^Resonate [^Resonate R name] (unregister R name 1))
  (^Resonate [^Resonate R name version]
   (let [registry (.get f-registry R)
         by-key (.get by-key-field registry)]
     (.remove by-key (->Key name version))
     R)))

(defn register
  "Register a durable function with Resonate instance `r`.
   
   `f` is a var (preferred, it supplies the default name) or a fn value; its first
   argument must be the Resonate `Context`.

   Returns the Resonate instance `r`, enabling threading of multiple registrations.

   Options:
   :name     - registration name, default the var's fully qualified symbol.
   :version  - default 1.
   :retry    - a RetryPolicy, default nil (the SDK's own default).
   :replace? - replace any existing registration instead of throwing, default false.

   Without `:replace?`, throws `AlreadyRegisteredError` when this (name, version) is
   already registered -- re-evaluating a `defn` and registering it again counts."
  (^Resonate [^Resonate R ?ref] (register R ?ref nil))
  (^Resonate [^Resonate R ?ref & {:keys [name version retry replace?] 
                                  :or {version 1} :as _opts}]
   (let [method (method-of ?ref)
         name (coerce name)
         name (if (string? name) name (fq-name ?ref))]
     (when replace? (unregister R name version))
     (try
       (let [registry (.get f-registry R)
             args (object-array [name method (int version) retry])]
         (.invoke m-register registry args))
       (catch InvocationTargetException e 
         (throw (.getCause e)))) 
     R)))

(defn ->duration
  "A `java.time.Duration` from a Duration or a number of milliseconds."
  ^Duration [d]
  (if (number? d) (Duration/ofMillis (long d)) d))

(defn ->Resonate
  "Idiomatic Clojure factory on par with the Resonate builder."
  ^Resonate [{:keys [url network group pid ttl token encryptor heartbeat prefix
                     max-concurrent-tasks retry-policy]}]
  (let [^Resonate$Builder b (Resonate/builder)]
    (cond-> b
      url (.url ^String url)
      network (.network ^Network network)
      group (.group ^String group)
      pid (.pid ^String pid)
      ttl (.ttl (->duration ttl))
      token (.token ^String token)
      encryptor (.encryptor ^Codec$Encryptor encryptor)
      heartbeat (.heartbeat ^Heartbeat$Hb heartbeat)
      prefix (.prefix ^String prefix)
      max-concurrent-tasks (.maxConcurrentTasks (int max-concurrent-tasks))
      retry-policy (.retryPolicy ^Retry$RetryPolicy retry-policy)
      :always (.build))))

(defn start!
  "Start a Resonate instance, pairs with `stop!`."
  [m] (->Resonate m))

(defn stop! 
  "Stop the Resonate instance. Idempotent."
  [^Resonate r] (.stop r))

(defn id
  "Make a run ID from system nano time."
  ([] (id "run-"))
  ([prefix] (str prefix "-" (System/nanoTime))))

;; -- wire marshalling ---------------------------------------------------------
;;
;; Everything crossing the wire is JSON, and `Codec.decode` is `readValue(.., Object.class)`.
;; A Clojure signature carries no declared parameter types, so `Durable` takes its passthrough
;; branch and hands whatever that produced straight to the function -- which is why teaching
;; the SDK's own mapper is what reaches a durable function's arguments. Nothing we could do at
;; our own boundary gets there.
;;
;; `Codec.MAPPER` is private static final with no builder knob, so we reach it reflectively and
;; register jsonista's Clojure codec on it: keywords, symbols and ratios out; persistent maps
;; and vectors in. One hook, both directions, no hand-written serializers.

(def ^:private mapper
  (.get (doto (.getDeclaredField Codec "MAPPER") (.setAccessible true)) nil))

(def ^:private sdk-error-keys
  "The SDK reads its own error envelope back with String keys -- `map.get(\"message\")` in
   `Codec/deserializeError` -- so these three must not become keywords, or a rejected promise
   loses its cause and surfaces as \"unknown error\"."
  #{"__type" "message" "__java_serialized"})

(defn- decode-key [^String k]
  (if (sdk-error-keys k) k (keyword k)))

;; defonce: registering a module mutates shared state; a namespace reload must not repeat it.
#_{:clj-kondo/ignore [:unused-private-var]}
(defonce ^:private clojure-codec
  (doto ^ObjectMapper mapper
    (.registerModule
     (doto (SimpleModule. "resonate-clj")
       (.addSerializer clojure.lang.Keyword (KeywordSerializer. false))
       (.addSerializer clojure.lang.Symbol (SymbolSerializer.))
       (.addSerializer clojure.lang.Ratio (RatioSerializer.))
       ;; Map keys take a separate path in Jackson; a value serializer never sees them.
       (.addKeySerializer clojure.lang.Keyword (FunctionalKeywordSerializer. kw->str))
       (.addKeyDeserializer Object (FunctionalKeyDeserializer. decode-key))
       (.addDeserializer java.util.Map (PersistentHashMapDeserializer.))
       (.addDeserializer java.util.List (PersistentVectorDeserializer.))))))

(defn run-resolved
  [^Resonate R id name version args]
  (invoke! m-run-resolved R [id name (int version) (object-array args)]))

(defn run
  "Start a durable invocation from outside a workflow.

   Args:
   R    - Resonate instance.
   id   - promise ID String. Reusing an ID rejoins that promise instead of
          starting new work; `(id)` when a fresh run is wanted.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Returns a ResonateHandle, settled with `result`."
  ^Handle$ResonateHandle [^Resonate R ^String id ?ref & args]
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.run R id ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref) 
                       [name version] (get-key R ?ref)]
                   (check-arity! method args)
                   (run-resolved R id name version args))
      :else (throw (ex-info "R dispatch failed" {:ref ?ref})))))

(defn run-method
  [^Context ctx ^Method method args]
  (invoke! m-run-method ctx [method (object-array args)]))

(defn run-in
  "Run a durable function as a child of the workflow `ctx` belongs to.

   Args:
   ctx  - Resonate Context, the first argument of the calling durable function.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Takes no ID: child IDs come from call order, which is what makes replay
   deterministic. Returns a ResonateFuture, settled with `await`."
  ^Context$ResonateFuture [^Context ctx ?ref & args] 
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.run ctx ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref)]
                   (check-arity! method args)
                   (run-method ctx method args))
      :else (throw (ex-info "Ctx dispatch failed}" {:ref ?ref})))))

(defn rpc-resolved
  [^Resonate R id name version args]
  (invoke! m-rpc-resolved R [id name (int version) Object (object-array args)]))

(defn rpc
  "Dispatch a durable invocation remotely, from outside a workflow.

   Args:
   R    - Resonate instance.
   id   - promise ID String. Reusing an ID rejoins that promise instead of
          starting new work; `(id)` when a fresh run is wanted.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Unlike `run`, the callee does not execute here -- whichever worker in the target group
   claims the task runs it, so a String names a function this process need not have. A var
   or fn must be registered locally, since its name and version come from the reverse index.

   Returns a ResonateHandle, settled with `result`."
  ^Handle$ResonateHandle [^Resonate R ^String id ?ref & args]
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.rpc R id ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref) 
                       [name version] (get-key R ?ref)]
                   (check-arity! method args)
                   (rpc-resolved R id name version args))
      :else (throw (ex-info "R dispatch failed" {:ref ?ref})))))

(defn rpc-method
  [^Context ctx ^Method method args]
  (invoke! m-rpc-method ctx [method (object-array args)]))

(defn rpc-in
  "Dispatch a durable function remotely from inside a workflow.

   Args:
   ctx  - Resonate Context, the first argument of the calling durable function.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Takes no ID: child IDs come from call order, which is what makes replay deterministic.
   Returns a ResonateFuture, settled with `await`.

   Where `run-in` can reach an unregistered function by Method, `rpc-in` cannot: the name
   and version are what travel, so a var or fn must be registered here even though the
   callee runs elsewhere."
  ^Context$ResonateFuture [^Context ctx ?ref & args]
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.rpc ctx ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref)]
                   (check-arity! method args)
                   (rpc-method ctx method args))
      :else (throw (ex-info "Ctx dispatch failed" {:ref ?ref})))))

(defn await
  "Block for a child's value. Already Clojure data: the SDK decodes through our own reader."
  [^Context$ResonateFuture f] (.await f))

(defn result
  "Block for a run's value. Already Clojure data: the SDK decodes through our own reader."
  [^Handle$ResonateHandle h] (.result h))


;; -- defdurable ---------------------------------------------------------------

;; (defonce durables (atom nil))
;; 
;; (defmacro defdurable
;;   "Like `defn`, but marks the function as durable and registers it with the current
;;    client (see `set-client!`) if there is one. Registering again is what a re-evaluated
;;    form does, so editing and re-evaluating is enough to put new code behind the name.
;; 
;;    The first argument must be the Resonate `Context`. Registration options are read
;;    from the var's metadata, so they go in `defn`'s attr-map:
;; 
;;      (defdurable format-greeting
;;        \"A leaf function.\"
;;        {:durable/name \"greet\" :durable/version 2}
;;        [ctx name]
;;        (str \"hello, \" name))
;; 
;;    :durable/name         - registration name, default the fully qualified symbol.
;;    :durable/version      - default 1.
;;    :durable/retry-policy - a Retry$RetryPolicy, default nil."
;;   {:arglists '([name doc-string? attr-map? [ctx params*] body])}
;;   [name & fdecl]
;;   `(let [v# (defn ~name ~@fdecl)]
;;      (alter-meta! v# assoc ::durable true)
;;      (register-current v#)
;;      v#))
;; 
;; (defn durable-vars
;;   "Every var defined by `defdurable`, across all loaded namespaces."
;;   []
;;   (->> (all-ns)
;;        (mapcat (comp vals ns-interns))
;;        (filter (comp ::durable meta))))
;; 
;; (defn register-all
;;   "Register every `defdurable` var with `r`, replacing prior registrations. Use it to
;;    populate a fresh instance, or to resync after `clojure.tools.namespace` reloads a
;;    namespace out from under the registry. Returns the Resonate instance `r`."
;;   (^Resonate [] (register-all @current-client))
;;   (^Resonate [^Resonate r]
;;    (doseq [v (durable-vars)]
;;      (register r v (durable-opts v)))
;;    r))
