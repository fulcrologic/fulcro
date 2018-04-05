(ns book.demos.parallel-vs-sequential-loading
  (:require
    [fulcro.server :refer [defquery-root defquery-entity defmutation]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defquery-entity :background.child/by-id
  (value [{:keys [parser query] :as env} id params]
    (when (= query [:background/long-query])
      (parser env query))))

(defquery-root :background/long-query
  (value [{:keys [ast query] :as env} params] 42))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn render-result [v] (dom/span nil v))

(defsc Child [this {:keys [name name background/long-query]}]
  {:query [:id :name :background/long-query]
   :ident [:background.child/by-id :id]}
  (dom/div #js {:style #js {:display "inline" :float "left" :width "200px"}}
    (dom/button #js {:onClick #(df/load-field this :background/long-query :parallel true)} "Load stuff parallel")
    (dom/button #js {:onClick #(df/load-field this :background/long-query)} "Load stuff sequential")
    (dom/div nil
      name
      (df/lazily-loaded render-result long-query))))

(def ui-child (prim/factory Child {:keyfn :id}))

(defsc Root [this {:keys [ui/react-key children] :or {ui/react-key "ROOT"} :as props}]
  ; cheating a little...raw props used for child, instead of embedding them there.
  {:initial-state (fn [params] {:children [{:id 1 :name "A"} {:id 2 :name "B"} {:id 3 :name "C"}]})
   :query         [:ui/react-key {:children (prim/get-query Child)}]}
  (dom/div #js {:key react-key}
    (mapv ui-child children)
    (dom/br #js {:style #js {:clear "both"}}) (dom/br nil)))

