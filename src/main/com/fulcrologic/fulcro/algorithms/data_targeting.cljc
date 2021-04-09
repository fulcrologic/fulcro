(ns com.fulcrologic.fulcro.algorithms.data-targeting
  "The implementation of processing load/mutation result graph targeting."
  (:require
    [clojure.spec.alpha :as s]
    [clojure.set :as set]
    [com.fulcrologic.guardrails.core :as gw :refer [>defn => >def]]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]))

(>def ::target vector?)

(>defn multiple-targets
  "Specifies a target that should place edges in the graph at multiple locations.

  `targets` - Any number of targets.  A target can be a simple path (as a vector), or other
  special targets like `append-to` and `prepend-to`."
  [& targets]
  [(s/* ::target) => ::target]
  (with-meta (vec targets) {::multiple-targets true}))

(>defn prepend-to
  "Specifies a to-many target that will preprend an edge to some to-many edge. NOTE: this kind of target will not
  create duplicates in the target list.

  `target` - A vector (path) in the normalized database of the to-many list of idents.
  "
  [target]
  [::target => ::target]
  (with-meta target {::prepend-target true}))

(>defn append-to
  "Specifies a to-many target that will append an edge to some to-many edge. NOTE: this kind of target will not
  create duplicates in the target list.

  `target` - A vector (path) in the normalized database of the to-many list of idents."
  [target]
  [::target => ::target]
  (with-meta target {::append-target true}))

(>defn replace-at
  "Specifies a target that will replace an edge at some normalized location.

  `target` - A vector (path) in the normalized database. This path can include numbers to target some element
  of an existing to-many list of idents."
  [target]
  [::target => ::target]
  (with-meta target {::replace-target true}))

(>defn replacement-target? [t] [any? => boolean?] (-> t meta ::replace-target boolean))
(>defn prepend-target? [t] [any? => boolean?] (-> t meta ::prepend-target boolean))
(>defn append-target? [t] [any? => boolean?] (-> t meta ::append-target boolean))
(>defn multiple-targets? [t] [any? => boolean?] (-> t meta ::multiple-targets boolean))

(>defn special-target?
  "Is the given target special? This means it is not just a plain vector path, but is instead something like
  an append."
  [target]
  [any? => boolean?]
  (boolean (seq (set/intersection (-> target meta keys set) #{::replace-target ::append-target ::prepend-target ::multiple-targets}))))

(>defn integrate-ident*
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not place
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element index then that element must already exist in the vector.

  NOTE: `ident` does not have to be an ident if you want to place denormalized data.  It can really be anything.

  Returns the updated state map."
  [state ident & named-parameters]
  [map? any? (s/* (s/or :path ::target :command #{:append :prepend :replace})) => map?]
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (update-in state data-path #(into [ident] %)))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (update-in state data-path (fnil conj []) ident))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (when-not (vector? data-path) (log/error "Replacement path must be a vector. You passed: " data-path "See https://book.fulcrologic.com/#err-targ-repl-path-not-vec"))
                             (when to-many?
                               (cond
                                 (not (vector? vector)) (log/error "Path for replacement must be a vector. See https://book.fulcrologic.com/#err-targ-multi-repl-must-be-vec")
                                 (not (number? index)) (log/error "Path for replacement must end in a vector index. See https://book.fulcrologic.com/#err-targ-multi-repl-must-end-with-idx")
                                 (not (contains? vector index)) (log/error "Target vector for replacement does not have an item at index " index ". See https://book.fulcrologic.com/#err-targ-multi-repl-no-such-idx")))
                             (assoc-in state data-path ident))
                  state)))
      state actions)))

(>defn process-target
  "Process a load target (which can be a multiple-target).

  `state-map` - the state-map
  `source-path` - A keyword, ident, or app-state path.  If the source path is an ident, then that is what is placed
     in app state.  If it is a keyword or longer path then the thing at that location in app state is pulled from app state
     and copied to the target location(s).
  `target` - The target(s)
  `remove-source?` - When true the source will be removed from app state once it has been written to the new location.

  Returns an updated state-map with the given changes."
  ([state-map source-path target]
   [map? (s/or :key keyword? :ident eql/ident? :path vector?) ::target => map?]
   (process-target state-map source-path target true))
  ([state-map source-path target remove-source?]
   [map? (s/or :key keyword? :ident eql/ident? :path vector?) ::target boolean? => map?]
   (letfn [(process-target-impl [state-map source-path target]
             (let [item-to-place (cond (eql/ident? source-path) source-path
                                       (keyword? source-path) (get state-map source-path)
                                       :else (get-in state-map source-path))
                   many-idents?  (and (vector? item-to-place)
                                   (every? eql/ident? item-to-place))]
               (cond
                 (and (eql/ident? source-path) (not (special-target? target)))
                 (assoc-in state-map target item-to-place)

                 (not (special-target? target))
                 (assoc-in state-map target item-to-place)

                 (multiple-targets? target)
                 (reduce (fn [s t] (process-target-impl s source-path t)) state-map target)

                 (and many-idents? (special-target? target))
                 (let [state            state-map
                       target-has-many? (vector? (get-in state target))]
                   (if target-has-many?
                     (cond
                       (prepend-target? target) (update-in state target (fn [v] (vec (concat item-to-place v))))
                       (append-target? target) (update-in state target (fn [v] (vec (concat v item-to-place))))
                       :else state)
                     (assoc-in state target item-to-place)))

                 (special-target? target)
                 (cond-> state-map
                   (prepend-target? target) (integrate-ident* item-to-place :prepend target)
                   (append-target? target) (integrate-ident* item-to-place :append target)
                   (replacement-target? target) (integrate-ident* item-to-place :replace target))

                 :else (do
                         (log/warn "Target processing found an unsupported case. See https://book.fulcrologic.com/#warn-target-unsuported-case")
                         state-map))))]
     (cond-> (process-target-impl state-map source-path target)
       (and remove-source? (not (eql/ident? source-path))) (dissoc source-path)))))
