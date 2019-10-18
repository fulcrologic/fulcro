(ns com.fulcrologic.fulcro.algorithms.normalized-state-helpers
  "Functions that can be used against a normalized Fulcro state database."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.algorithms.normalized-state-helpers))
  (:refer-clojure :exclude [get-in])
  (:require
    [clojure.set :as set]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [edn-query-language.core :as eql]
    [clojure.spec.alpha :as s]
    [ghostwheel.core :refer [>defn =>]]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as targeting]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.components :as comp]))

(def integrate-ident*
  "[state ident & named-parameters]

  Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not place
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector.

  NOTE: `ident` does not have to be an ident if you want to place denormalized data.  It can really be anything.

  Returns the updated state map."
  targeting/integrate-ident*)

(def remove-ident*
  " [state-map ident path-to-idents]

  Removes an ident, if it exists, from a list of idents in app state. This
  function is safe to use within mutations."
  merge/remove-ident*)


(>defn tree-path->db-path
  "Convert a 'denormalized' path into a normalized one by walking the path in state and honoring ident-based edges.

  For example, one might find this to be true for a normalized db:

  ```
  state => {:person/id {1 {:person/id 1 :person/spouse [:person/id 3]}
                        3 {:person/id 3 :person/first-name ...}}}

  (tree-path->db-path state [:person/id 1 :person/spouse :person/first-name])
  => [:person/id 3 :person/first-name]
  ```
  "
  ([state path]
   [map? vector? => vector?]
   (loop [[h & t] path
          new-path []]
     (if h
       (let [np (conj new-path h)
             c (clojure.core/get-in state np)]
         (if (eql/ident? c)
           (recur t c)
           (recur t (conj new-path h))))
       (if (not= path new-path)
         new-path
         path)))))


(>defn get-in
  "Just like clojure.core/get-in, but if an element of the path is an ident it will follow the ident instead."
  ([state-map path]
   [map? vector? => any?]
   (get-in state-map path nil))

  ([state-map path not-found]
   [map? vector? any? => any?]
   (clojure.core/get-in state-map (tree-path->db-path state-map path) not-found)))


(defn ui->props
  "Obtain a tree of props for a UI instance from the current application state. Useful in mutations where you want
  to denormalize an entity from the state database. `this` can often be obtained from the mutation `env` at the
  `:component` key."
  ([this]
   (ui->props (comp/component->state-map this) (comp/react-type this) (comp/get-ident this)))
  ([state-map component-class ident]
   (fdn/db->tree (comp/get-query component-class state-map) (get-in state-map ident) state-map)))


(defn- dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
   nested structure. keys is a sequence of keys. Any empty maps that result
   will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))


(>defn remove-entity*
  "Remove the given entity at the given ident. Also scans all tables and removes any to-one or to-many idents that are
  found that match `ident` (removes dangling pointers to the removed entity).

  The optional `cascade` parameter is a set of keywords that represent edges that should cause recursive deletes
  (i.e. it indicates edge names that *own* something, indicating it is safe to remove those entities as well).

  Returns the new state map with the entity(ies) removed."

  ([state-map ident]
   [map? eql/ident? => map?]
   (remove-entity* state-map ident #{}))

  ([state-map ident cascade]
   [map? eql/ident? (s/coll-of keyword? :kind set?) => map?]

   (let [;; "Walks the tree in a depth first manner and returns the normalized possible paths"
         normalized-paths (letfn [(paths* [ps ks m]
                                    (reduce-kv
                                      (fn [ps k v]
                                        (if (map? v)
                                          (paths* ps (conj ks k) v)
                                          (conj ps (conj ks k))))
                                      ps
                                      m))]
                            (filter #(< (count %) 4)
                                    (paths* () [] state-map)))

         ident-specific-paths (fn [state ident]
                                (filter (fn [a-path]
                                          (let [vl (clojure.core/get-in state a-path)]
                                            (if (coll? vl)
                                              (or
                                                (some #{ident} vl)
                                                (= ident vl)))))
                                        normalized-paths))

         remove-ident-at-path (fn [state a-normalized-path ident]
                                (let [v (clojure.core/get-in state a-normalized-path)]
                                  (if (coll? v)
                                    (cond
                                      (= v ident) (dissoc-in state a-normalized-path)
                                      (every? eql/ident? v) (merge/remove-ident* state ident a-normalized-path)
                                      :else state)
                                    state)))


         remove-ident-from-tables (fn [state ident]
                                    (reduce #(remove-ident-at-path %1 %2 ident)
                                            state
                                            (ident-specific-paths state ident)))

         state-without-entity (->
                                ;; remove pointers to the entity
                                (remove-ident-from-tables state-map ident)
                                ;; remove the top-level entity
                                (dissoc-in ident))

         target-entity (get-in state-map ident)

         ;; Computed set of all affected entities when cascade option is provided
         cascaded-idents (let [table-key (first ident)]
                           (map
                             (fn [entity-field]
                               (clojure.core/get-in state-map
                                                    (conj [table-key (table-key target-entity)] entity-field)))
                             (set/intersection
                               cascade
                               (set (keys target-entity)))))

         final-state (reduce
                       (fn [state edge]
                         (if (every? eql/ident? edge)
                           (reduce (fn [new-state ident] (remove-entity* new-state ident cascade)) state edge)
                           (remove-entity* state edge cascade)))
                       state-without-entity
                       cascaded-idents)]

     final-state)))


(>defn remove-edge*
  "Remove the given edge at the given path. Also scans all tables and removes any to-one or to-many idents that are
  found that match `edge` (removes dangling pointers to the removed entity(ies).

  The optional `cascade` parameter is a set of keywords that represent edges that should cause recursive deletes
  (i.e. it indicates edge names that *own* something, indicating it is safe to remove those entities as well).

  Returns the new state map with the entity(ies) removed."

  ([state-map path-to-edge]
   [map? vector? => any?]
   (remove-edge* state-map path-to-edge #{}))


  ([state-map path-to-edge cascade]
   [map? vector? (s/coll-of keyword? :kind set?) => map?]
   (let [;; "Walks the tree in a depth first manner and returns the normalized possible paths"
         normalized-paths (letfn [(paths* [ps ks m]
                                    (reduce-kv
                                      (fn [ps k v]
                                        (if (map? v)
                                          (paths* ps (conj ks k) v)
                                          (conj ps (conj ks k))))
                                      ps
                                      m))]
                            (filter #(< (count %) 4)
                                    (paths* () [] state-map)))

         candidate (let [vl (clojure.core/get-in state-map path-to-edge)]
                     (if-not (vector? vl)
                       nil
                       (cond
                         (eql/ident? vl) [vl]
                         (every? eql/ident? vl) vl)))

         final-state (if (some #{path-to-edge} normalized-paths)
                       (reduce
                         #(remove-entity* %1 %2 cascade)
                         state-map
                         candidate)
                       state-map)]
     final-state)))


(>defn sort-idents-by
  "Returns the sorted version of the provided vector of idents.

  Intended to be used as
  ```
  (sort-idents-by :entity/field vector-of-idents)
  ```

  Can facilitate:
  ```
  (swap! state-map update-in [:entity 1 :list] #(sort-idents-by :list/field %))
  ```
  "
  [entity-field vector-of-idents]
  [keyword? vector? => any?]
  (mapv first
        (sort-by entity-field
                 (map (fn [[k v]] {k v}) vector-of-idents))))


(defn update-caller!
  "Runs clojure.core/update on the table entry in the state database that corresponds
   to the mutation caller (which can be explicitly set via `:ref` when calling `transact!`).

   Equivalent to
   ```
   (swap! (:state env) update-in (:ref env) ...)
   ```
   "

  [{:keys [state ref] :as mutation-env} & args]
  (apply swap! state update-in ref args))


(defn update-caller-in!
  "Like swap! but starts at the ref from `env`, adds in supplied `path` elements
  (resolving across idents if necessary). Finally runs an update-in on that resultant
  path with the given `args`.

   Equivalent to:
   ```
   (swap! (:state env) update-in (tree-path->db-path @state (into (:ref env) path)) args)
   ```
   with a small bit of additional sanity checking."

  [{:keys [state ref] :as mutation-env} path & args]
  (let [path (tree-path->db-path @state (into ref path))]
    (if (and path (get-in @state path))
      (apply swap! state update-in path args)
      @state)))



#?(:clj
   (defmacro swap!->
     "A useful macro for threading multiple operations together on the state map.

     Equivalent to:
     ```
     (swap! (:state env) (fn [s] (-> s ...forms...)))
     ```

     For example
     ```
     (swap!-> env
       (merge/merge-component ...)
       (integrate-ident* ...))
     ```
     "
     [mutation-env & forms]
     `(swap! (:state ~mutation-env) (fn [s#]
                                      (-> s#
                                          ~@forms)))))


