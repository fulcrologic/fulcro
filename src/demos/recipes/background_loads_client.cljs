(ns recipes.background-loads-client
  (:require
    [untangled.client.core :as uc]
    [untangled.i18n :refer [tr trf]]
    [untangled.client.data-fetch :as df]
    [om.dom :as dom]
    [om.next :as om :refer [defui]]
    yahoo.intl-messageformat-with-locales))

(defn render-result [v] (dom/span nil v))

(defui ^:once Child
  static om/IQuery
  (query [this] [:id :name :background/long-query])
  static om/Ident
  (ident [this props] [:background.child/by-id (:id props)])
  Object
  (render [this] (let [{:keys [name name background/long-query]} (om/props this)]
                   (dom/div #js {:style #js {:display "inline" :float "left" :width "200px"}}
                     (dom/button #js {:onClick #(df/load-field this :background/long-query :parallel true)} "Load stuff parallel")
                     (dom/button #js {:onClick #(df/load-field this :background/long-query)} "Load stuff sequential")
                     (dom/div nil
                       name
                       (df/lazily-loaded render-result long-query))))))

(def ui-child (om/factory Child {:keyfn :id}))

(defui ^:once Root
  static uc/InitialAppState
  (initial-state [c params] {:children [{:id 1 :name "A"} {:id 2 :name "B"} {:id 3 :name "C"}]})
  static om/IQuery
  (query [this] [:ui/react-key {:children (om/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key children] :or {ui/react-key "ROOT"} :as props} (om/props this)]
      (dom/div #js {:key react-key}
        (mapv ui-child children)
        (dom/br #js {:style #js {:clear "both"}}) (dom/br nil)))))


