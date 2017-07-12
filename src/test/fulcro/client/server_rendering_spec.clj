(ns fulcro.client.server-rendering-spec
  (:require [om.next :as om :refer [defui]]
            [om.dom :as dom]
            [fulcro-spec.core :refer [specification behavior assertions]]
            [fulcro.client.core :as uc]))

(defui Item
  static uc/InitialAppState
  (initial-state [cls {:keys [id label]}] {:id id :label label})
  static om/IQuery
  (query [this] [:id :label])
  static om/Ident
  (ident [this props] [:items/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [label]} (om/props this)]
      (dom/div #js {:className "item"}
        (dom/span #js {:className "label"} label)))))

(def ui-item (om/factory Item {:keyfn :id}))

(defui Root
  static uc/InitialAppState
  (initial-state [cls params] {:items [(uc/get-initial-state Item {:id 1 :label "A"})
                                       (uc/get-initial-state Item {:id 2 :label "B"})]})
  static om/IQuery
  (query [this] [{:items (om/get-query Item)}])
  Object
  (render [this]
    (let [{:keys [items]} (om/props this)]
      (dom/div #js {:className "root"}
        (mapv ui-item items)))))

(def ui-root (om/factory Root))

(specification "Server-side rendering"
  (assertions
    "Can generate a string from UI with initial state"
    (dom/render-to-str (ui-root (uc/get-initial-state Root {}))) => "<div class=\"root\" data-reactroot=\"\" data-reactid=\"1\" data-react-checksum=\"830295248\"><div class=\"item\" data-reactid=\"2\"><span class=\"label\" data-reactid=\"3\">A</span></div><div class=\"item\" data-reactid=\"4\"><span class=\"label\" data-reactid=\"5\">B</span></div></div>"))
