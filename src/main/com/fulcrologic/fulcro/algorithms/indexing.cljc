(ns com.fulcrologic.fulcro.algorithms.indexing
  (:require
    [com.fulcrologic.fulcro.components :as comp]
    [taoensso.timbre :as log]))

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
