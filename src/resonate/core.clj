(ns resonate.core
  (:refer-clojure :exclude [await promise run!]) 
  (:require
   [clojure.string :as str]
   [resonate.codec]
   [resonate.interop :as i :refer [method-of]])
  (:import 
   [io.resonatehq.resonate Codec$Encryptor Context Context$ResonateFuture 
    Handle$ResonateHandle Heartbeat$Hb Network 
    Resonate Resonate$Builder Retry$RetryPolicy Types$Info] 
   [java.lang.reflect Method] 
   [java.time Duration]))

(defn- fq-name
  "The default registration name for a var: its fully qualified symbol."
  ^String [f]
  (when (var? f)
    (let [m (meta f)]
      (str (ns-name (:ns m)) "/" (:name m)))))
 
(defn- check-arity!
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
   (i/unregister R name version)
   R))

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
         name (cond (keyword? name) (kw->str name)
                    (string? name) name
                    :else (fq-name ?ref))]
     (when (and (fn? ?ref) (nil? name))
       (throw (ex-info "Pass var instead of fn or provide explicit :name."
                       {:ref ?ref :name name})))
     (when replace? (unregister R name version))
     (i/register R name method version retry)
     R)))

(defn ->Duration
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
      ttl (.ttl (->Duration ttl))
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
  ([prefix] (str prefix (System/nanoTime))))

(defn run!
  "Start a durable invocation from outside a workflow.

   Args:
   R    - Resonate instance.
   id   - promise ID String. Reusing an ID rejoins that promise instead of
          starting new work; `(id)` when a fresh run is wanted.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Returns a ResonateHandle, settled with `result`."
  ^Handle$ResonateHandle [^Resonate R ^String id ?ref & args]
  (assert (instance? Resonate R) "Expected Resonate as first arg!")
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.run R id ^String ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref)
                       _ (check-arity! method args)
                       [name version] (i/get-registered-key R method)]
                   (i/run-resolved R id name version args))
      :else (throw (ex-info "R dispatch failed" {:ref ?ref})))))

(defn run
  "Run a durable function as a child of the workflow `ctx` belongs to.

   Args:
   ctx  - Resonate Context, the first argument of the calling durable function.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Takes no ID: child IDs come from call order, which is what makes replay
   deterministic. Returns a ResonateFuture, settled with `await`."
  ^Context$ResonateFuture [^Context ctx ?ref & args]
  (assert (instance? Context ctx) "Expected Context as first arg!")
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.run ctx ^String ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref)]
                   (check-arity! method args)
                   (i/run-method ctx method args))
      :else (throw (ex-info "Ctx dispatch failed" {:ref ?ref})))))

(defn rpc!
  "Dispatch a durable invocation remotely, from outside a workflow.

   Args:
   R    - Resonate instance.
   id   - promise ID String. Reusing an ID rejoins that promise instead of
          starting new work; `(id)` when a fresh run is wanted.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Unlike `run!`, the callee does not execute here -- whichever worker in the target group
   claims the task runs it, so a String names a function this process need not have. A var
   or fn must be registered locally, since its name and version come from the reverse index.

   Returns a ResonateHandle, settled with `result`."
  ^Handle$ResonateHandle [^Resonate R ^String id ?ref & args]
  (assert (instance? Resonate R) "Expected Resonate as first arg!")
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.rpc R id ^String ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref)
                       _ (check-arity! method args)
                       [name version] (i/get-registered-key R method)]
                   (i/rpc-resolved R id name version args))
      :else (throw (ex-info "R dispatch failed" {:ref ?ref})))))

(defn rpc
  "Dispatch a durable function remotely from inside a workflow.

   Args:
   ctx  - Resonate Context, the first argument of the calling durable function.
   ?ref - Something coerceable into a durable ref.
   & args - Arguments to pass to the durable ref.

   Takes no ID: child IDs come from call order, which is what makes replay deterministic.
   Returns a ResonateFuture, settled with `await`.

   Where `run` can reach an unregistered function by Method, `rpc` cannot: the name and
   version are what travel, so a var or fn must be registered here even though the callee
   runs elsewhere."
  ^Context$ResonateFuture [^Context ctx ?ref & args]
  (assert (instance? Context ctx) "Expected Context as first arg!")
  (let [?ref (coerce ?ref)]
    (cond
      (string? ?ref) (.rpc ctx ^String ?ref (object-array args))
      (fn? ?ref) (let [method (method-of ?ref)]
                   (check-arity! method args)
                   (i/rpc-method ctx method args))
      :else (throw (ex-info "Ctx dispatch failed" {:ref ?ref})))))

;; -- the rest of the Context surface ------------------------------------------

(defn sleep
  "Sleep durably: a promise that settles when the timer fires, so the wait survives a
   restart where `Thread/sleep` would not.

   `d` is a Duration or a number of milliseconds. Returns a ResonateFuture whose value is
   nil, settled with `await` -- nothing happens until you await it."
  ^Context$ResonateFuture [^Context ctx d]
  (.sleep ctx (->Duration d)))

(defn promise
  "A durable promise this workflow awaits and something *outside* resolves -- a signal, a
   human approving, another system reporting back. Await it to suspend until then.

   `timeout` is a Duration or milliseconds, defaulting to the context's. Returns a
   ResonateFuture; resolve it from elsewhere against the id `future-id` reports."
  (^Context$ResonateFuture [^Context ctx] (.promise ctx))
  (^Context$ResonateFuture [^Context ctx timeout] (.promise ctx (->Duration timeout))))

(defn detached
  "Dispatch a durable function that outlives this workflow. Fire-and-forget: the child gets
   a fresh lineage (its origin resets to its own id) and the parent never waits on it, so it
   keeps running after the parent settles.

   Awaiting the returned future does NOT give you the child's result -- it yields the child's
   promise **id**, as soon as the promise is created, without ever suspending. Hold onto that
   id and `get` it later if you want the outcome.

   `?ref` is a String, or a var/fn that must be registered here: the SDK offers no Method
   form of `detached`, so only a name can travel. Note it dispatches at the *context's*
   version (`opts.version()`), not the version the ref was registered at."
  ^Context$ResonateFuture [^Context ctx ?ref & args]
  (assert (instance? Context ctx) "Expected Context as first arg!")
  (let [?ref (coerce ?ref)
        fn-name (cond
                  (string? ?ref) ?ref
                  (fn? ?ref) (let [method (method-of ?ref)]
                               (check-arity! method args)
                               (first (i/get-registered-key ctx method)))
                  :else (throw (ex-info "Ctx dispatch failed" {:ref ?ref})))]
    (.detached ctx ^String fn-name (object-array args))))

(defn info
  "What the runtime knows about the running durable function, as a Clojure map.

   `:func-name` is the registered name in a root or an `rpc` child, but a `run` child reads
   `invokeStatic`: that one is labelled from the Method, and every Clojure function compiles
   to a method of that name. Don't key anything on it."
  [^Context ctx]
  (let [^Types$Info nfo (.info ctx)]
    {:id (.id nfo)
     :parent-id (.parentId nfo)
     :origin-id (.originId nfo)
     :branch-id (.branchId nfo)
     :timeout-at (.timeoutAt nfo)
     :func-name (.funcName nfo)
     ;; The SDK's tags are a Map<String,String>; keywordize so the whole map is Clojure data.
     :tags (into {} (map (fn [[k v]] [(keyword k) v])) (.tags nfo))}))

(defn dependency
  "A dependency registered on the instance with `.withDependency`, looked up by its class."
  [^Context ctx ^Class c]
  (.getDependency ctx c))

(defn future-id
  "The promise id behind a child future -- the handle on a `detached` child, and what you
   resolve a `promise` against."
  ^String [^Context$ResonateFuture f]
  (.id f))

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
