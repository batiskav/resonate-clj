(ns resonate.interop
  (:import
   [io.resonatehq.resonate Context Registry Registry$NameVersion Resonate Retry$RetryPolicy]
   [java.lang.reflect Constructor Field InvocationTargetException Method Modifier Type]))

;; Reflection

(def ^:private ObjectArray (class (object-array 0)))
(def ^:private ^Class RegistryKey (Class/forName "io.resonatehq.resonate.Registry$Key"))
(def ^:private ^Class ContextState (Class/forName "io.resonatehq.resonate.Context$State"))

(def ^:private ^Field f-registry
  (doto (.getDeclaredField Resonate "registry")
    (.setAccessible true)))

(def ^:private ^Method m-run-resolved
  (let [param-types (into-array Class [String String Integer/TYPE ObjectArray])]
    (doto (.getDeclaredMethod Resonate "runResolved" param-types)
      (.setAccessible true))))

(def ^:private ^Method m-register
  (let [param-types (into-array Class [String Method Integer/TYPE Retry$RetryPolicy])]
    (doto (.getDeclaredMethod Registry "register" param-types)
      (.setAccessible true))))

(def ^:private ^Method m-reverse
  (let [param-types (into-array Class [Method])]
    (doto (.getDeclaredMethod Registry "reverse" param-types)
      (.setAccessible true))))

(def ^:private ^Field by-key-field
  (doto (.getDeclaredField Registry "byKey")
    (.setAccessible true)))

(def ^:private ^Field f-state
  (doto (.getDeclaredField Context "state")
    (.setAccessible true)))

(def ^:private ^Method m-run-method
  (let [param-types (into-array Class [Method ObjectArray])]
    (doto (.getDeclaredMethod Context "run" param-types)
      (.setAccessible true))))

(def ^:private ^Method m-rpc-resolved
  (let [param-types (into-array Class [String String Integer/TYPE Type ObjectArray])]
    (doto (.getDeclaredMethod Resonate "rpcResolved" param-types)
      (.setAccessible true))))

(def ^:private ^Method m-rpc-method
  (let [param-types (into-array Class [Method ObjectArray])]
    (doto (.getDeclaredMethod Context "rpc" param-types)
      (.setAccessible true))))

(def ^:private ^Field f-state-registry
  (doto (.getDeclaredField ContextState "registry")
    (.setAccessible true)))

(def ^:private ^Constructor c-registry-key
  (let [param-types (into-array Class [String Integer/TYPE])]
    (doto (.getDeclaredConstructor RegistryKey param-types)
      (.setAccessible true))))

;; Util

(defn- invoke!
  "Reflective `Method.invoke`, unwrapping InvocationTargetException so the SDK's own error
   surfaces instead of a bare wrapper with a null message. `args` fills the varargs slot,
   which Clojure interop never synthesizes."
  [^Method m target args]
  (try
    (.invoke m target (object-array args))
    (catch InvocationTargetException e
      (throw (.getCause e)))))

;; API

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

(defn- ->Key [name version]
  (.newInstance c-registry-key (object-array [name (int version)])))

(defn get-registry
  "The Registry behind a Resonate instance or a Context."
  ^Registry [target]
  (condp instance? target
    Resonate (.get f-registry target)
    Context (.get f-state-registry (.get f-state target))
    (throw (ex-info "no registry: expected a Resonate instance or a Context"
                    {:target target :class (class target)}))))

(defn- get-by-method
  [^Registry registry ^Method method]
  (invoke! m-reverse registry [method]))

(defn get-registered-key
  "The `[name version]` a Method is registered under, from the SDK's own reverse index.

   Takes the Method the caller already resolved: `method-of` rescans the class every call,
   so resolving it here as well would double that work on every dispatch."
  [target ^Method method]
  (let [^Registry$NameVersion nv (get-by-method (get-registry target) method)]
    (when-not nv
      (throw (ex-info "not registered: no reverse entry for this function"
                      {:method (str method)})))
    ;; NameVersion is a public record, unlike the private Registry$Key -- a hint is enough.
    [(.name nv) (.version nv)]))

(defn register
  "Register `method` under `(name, version)` with an optional retry policy."
  [target name ^Method method version retry]
  (invoke! m-register (get-registry target) [name method (int version) retry]))

(defn unregister
  "Drop `(name, version)` from the registry, so it can be registered again. Only `byKey` is
   cleared; `register` overwrites the policy and reverse-lookup entries unconditionally."
  [target name version]
  (.remove ^java.util.Map (.get by-key-field (get-registry target)) (->Key name version)))

(defn run-resolved
  [^Resonate R id name version args]
  (invoke! m-run-resolved R [id name (int version) (object-array args)]))

(defn run-method
  [^Context ctx ^Method method args]
  (invoke! m-run-method ctx [method (object-array args)]))

(defn rpc-resolved
  [^Resonate R id name version args]
  (invoke! m-rpc-resolved R [id name (int version) Object (object-array args)]))

(defn rpc-method
  [^Context ctx ^Method method args]
  (invoke! m-rpc-method ctx [method (object-array args)]))
