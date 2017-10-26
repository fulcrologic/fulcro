(ns recipes.mutation-return-value-client
  (:require
    [fulcro.client.data-fetch :as df]
    [fulcro.client.mutations :as m]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as om :refer [defui]]
    [fulcro.client.core :as fc :refer [InitialAppState initial-state]]))

(defmulti merge-return-value (fn [state sym return-value] sym))

; Do all of the work on the server.
(defmethod m/mutate 'rv/crank-it-up [env k params] {:remote true})

(defmethod merge-return-value 'rv/crank-it-up
  [state _ {:keys [value]}]
  (assoc-in state [:child/by-id 0 :volume] value))

(defui ^:once Child
  static InitialAppState
  (initial-state [cls params] {:id 0 :volume 5})
  static om/IQuery
  (query [this] [:id :volume])
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id volume]} (om/props this)]
      (dom/div nil
        (dom/p nil "Current volume: " volume)
        (dom/button #js {:onClick #(om/transact! this `[(rv/crank-it-up ~{:value volume})])} "+")))))

(def ui-child (om/factory Child))

(defui ^:once Root
  static InitialAppState
  (initial-state [cls params]
    {:child (initial-state Child {})})
  static om/IQuery
  (query [this] [:ui/react-key {:child (om/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key child]} (om/props this)]
      (dom/div #js {:key react-key} (ui-child child)))))
