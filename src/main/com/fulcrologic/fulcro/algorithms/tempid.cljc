(ns com.fulcrologic.fulcro.algorithms.tempid
  "Functions for making and consuming Fulcro temporary IDs. Tempids are used when the client is optimistically
  creating a new entity and you want to be able to detect that on the server when the data is sent. Additionally,
  Fulcro mutations can return a remapping instruction from the server to rewrite all tempids in the client
  (state, network queues, etc.) atomically.

  This allows the client to safely generate new entities with a temporary ID and let the server remap them to the real
  IDs at some future time.  Transit read/write is included, so that (de)serialization of them can be consistent whenever
  needed (see the `transit` ns in this package)."
  (:refer-clojure :exclude [uuid])
  (:require
    [clojure.walk :refer [prewalk-replace]])
  #?(:clj (:import [java.io Writer]
                   (java.util UUID))))

(def tag "fulcro/tempid")

;; =============================================================================
;; ClojureScript

#?(:cljs
   (deftype TempId [^:mutable id ^:mutable __hash]
     Object
     (toString [this]
       (pr-str this))
     IEquiv
     (-equiv [this other]
       (and (instance? TempId other)
         (= (. this -id) (. other -id))))
     IHash
     (-hash [this]
       (when (nil? __hash)
         (set! __hash (hash id)))
       __hash)
     IPrintWithWriter
     (-pr-writer [_ writer _]
       (write-all writer "#" tag "[\"" id "\"]"))))

#?(:cljs
   (defn tempid
     "Create a new tempid."
     ([]
      (tempid (random-uuid)))
     ([id]
      (TempId. id nil))))

;; =============================================================================
;; Clojure

#?(:clj
   (defrecord TempId [id]
     Object
     (toString [this]
       (pr-str this))))

#?(:clj
   (defmethod print-method TempId [^TempId x ^Writer writer]
     (.write writer (str "#" tag "[\"" (.id x) "\"]"))))

#?(:clj
   (defn tempid
     "Create a new tempid."
     ([]
      (tempid (UUID/randomUUID)))
     ([uuid]
      (TempId. uuid))))

(defn tempid?
  "Returns true if the given `x` is a tempid."
  #?(:cljs {:tag boolean})
  [x]
  (instance? TempId x))

(defn result->tempid->realid
  "Find and combine all of the tempid remappings from a standard fulcro transaction response."
  [tx-result]
  (let [get-tempids (fn [m] (or (get m :tempids)))]
    (->> (filter (comp symbol? first) tx-result)
      (map (comp get-tempids second))
      (reduce merge {}))))

(defn resolve-tempids
  "Replaces all tempids in `data-structure` using the `tid->rid` map.  This is just a deep
   walk that replaces every possible match of `tid` with `rid`.

   `tid->rid` must be a map, as this function optimizes away resolution by checking if
   the map is empty.

   Returns the data structure with everything replaced."
  [data-structure tid->rid]
  (if (empty? tid->rid)
    data-structure
    (prewalk-replace tid->rid data-structure)))

(defn resolve-tempids!
  "Resolve all of the mutation tempid remappings in the `tx-result` against the given `app`.

  app - The fulcro app
  tx-result - The transaction result (the body map, not the internal tx node).

  This function rewrites all tempids in the app state and runtime transaction queues.

  NOTE: This function assumes that tempids are distinctly recognizable (e.g. are TempIds or
  guids).  It is unsafe to use this function if you're using something else for temporary IDs
  as this function might rewrite things that are not IDs."
  [{:com.fulcrologic.fulcro.application/keys [state-atom runtime-atom]} tx-result]
  (let [tid->rid (result->tempid->realid tx-result)]
    (swap! state-atom resolve-tempids tid->rid)
    (swap! runtime-atom
      (fn [r]
        (-> r
          (update :com.fulcrologic.fulcro.algorithms.tx-processing/submission-queue resolve-tempids tid->rid)
          (update :com.fulcrologic.fulcro.algorithms.tx-processing/active-queue resolve-tempids tid->rid)
          (update :com.fulcrologic.fulcro.algorithms.tx-processing/send-queues resolve-tempids tid->rid))))))

(defn uuid
  "Generate a UUID. With no args returns a random UUID. with an arg (numeric)
  it generates a stable one based on that number (useful for testing). Works in cljc."
  #?(:clj ([] (UUID/randomUUID)))
  #?(:clj ([n]
           (UUID/fromString
             (format "ffffffff-ffff-ffff-ffff-%012d" n))))
  #?(:cljs ([] (random-uuid)))
  #?(:cljs ([& args] (cljs.core/uuid (apply str args)))))

