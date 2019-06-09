(ns com.fulcrologic.fulcro.algorithms.indexing
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn index-query*
  [prop->classes {parent-component :component
                  parent-children  :children
                  :as              ast}]
  (log/info (select-keys ast [:key :dispatch-key]))
  (let [parent-children (seq parent-children)
        update-index    (fn [idx k c]
                          (log/info "Adding" k "to index")
                          (update idx k (fnil conj #{}) c))]
    (if parent-children
      (reduce
        (fn [idx {:keys [key dispatch-key children] :as child-ast}]
          (cond-> idx
            (and (vector? key) (= '_ (second key))) (update-index dispatch-key parent-component)
            (and (vector? key) (not= '_ (second key))) (update-index key parent-component)
            (keyword? key) (update-index key parent-component)
            (seq children) (index-query* child-ast)))
        prop->classes
        parent-children)
      prop->classes)))

(defn index-query
  "Create an index of the given component-annotated query.  Returns a map from query keyword to the component
  class(es) that query for that keyword."
  [query]
  (let [ast (eql/query->ast query)]
    (index-query* {} ast)))

(comment

  ;; old index component:
  (let [indexes (update-in indexes
                  [:class->components (react-type c)]
                  (fnil conj #{}) c)
        ident   (when #?(:clj  (satisfies? Ident c)
                         :cljs (implements? Ident c))
                  (let [ident (ident c (props c))]
                    (when-not (util/ident? ident)
                      (log/info
                        "malformed Ident. An ident must be a vector of "
                        "two elements (a keyword and an EDN value). Check "
                        "the Ident implementation of component `"
                        (.. c -constructor -displayName) "`."))
                    (when-not (some? (second ident))
                      (log/info
                        (str "component " (.. c -constructor -displayName)
                          "'s ident (" ident ") has a `nil` second element."
                          " This warning can be safely ignored if that is intended.")))
                    ident))]
    (if-not (nil? ident)
      (cond-> indexes
        ident (update-in [:ref->components ident] (fnil conj #{}) c))
      indexes)))
(defn index-component!
  "Add a component to the app index."
  [this]
  (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom]} (comp/any->app this)
        ident (comp/component-options this :ident)]
    (when (and ident runtime-atom)
      (log/info "Adding component with ident " (comp/ident this (comp/props this)) "to index")
      (swap! runtime-atom update-in
        [:com.fulcrologic.fulcro.application/indexes :ident->components (comp/ident this (comp/props this))]
        (fnil conj #{})
        this))))

(comment

  (key->components [_ k]
    (let [indexes @indexes]
      (if (component? k)
        #{k}
        (transduce (map #(get-in indexes [:class->components %]))
          (completing into)
          (get-in indexes [:ref->components k] #{})
          (get-in indexes [:prop->classes k])))))

  (drop-component! [_ c]
    (swap! indexes
      (fn drop-component-helper [indexes]
        (let [indexes (update-in indexes [:class->components (react-type c)] disj c)
              ident   (when #?(:clj  (satisfies? Ident c)
                               :cljs (implements? Ident c))
                        (ident c (props c)))]
          (if-not (nil? ident)
            (cond-> indexes
              ident (update-in [:ref->components ident] disj c))
            indexes))))))

(defn drop-component!
  "Remove the component from the index.  If ident is supplied it uses that, otherwise it gets the
  ident from the component itself."
  ([this ident]
   (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom]} (comp/any->app this)]
     (when (and ident runtime-atom)
       (log/info "Dropping component with ident " ident "from index")
       (swap! runtime-atom update-in
         [:com.fulcrologic.fulcro.application/indexes :ident->components ident]
         disj
         this))))
  ([this]
   (let [{:keys [:com.fulcrologic.fulcro.application/runtime-atom]} (comp/any->app this)
         old-ident (comp/get-ident this)]
     (when (and old-ident runtime-atom)
       (log/info "Dropping component with ident " old-ident "from index")
       (swap! runtime-atom update-in
         [:com.fulcrologic.fulcro.application/indexes :ident->components old-ident]
         disj
         this)))))
