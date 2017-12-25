(ns book.demos.server-return-values-manually-merging
  (:require
    [fulcro.client.dom :as dom]
    [fulcro.server :as server]
    [fulcro.client.mutations :as m]
    [fulcro.client.primitives :as prim :refer [defsc]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defmutation ^{:intern "-server"} crank-it-up [{:keys [value]}]
  (action [env]
    {:new-volume (inc value)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti merge-return-value (fn [state sym return-value] sym))

; Do all of the work on the server.
(m/defmutation crank-it-up [params]
  (remote [env] true))

(defmethod merge-return-value `crank-it-up
  [state _ {:keys [new-volume]}]
  (assoc-in state [:child/by-id 0 :volume] new-volume))

(defsc Child [this {:keys [id volume]}]
  {:initial-state (fn [params] {:id 0 :volume 5})
   :query         [:id :volume]
   :ident         [:child/by-id :id]}
  (dom/div nil
    (dom/p nil "Current volume: " volume)
    (dom/button #js {:onClick #(prim/transact! this `[(crank-it-up ~{:value volume})])} "+")))

(def ui-child (prim/factory Child))

(defsc Root [this {:keys [ui/react-key child]}]
  {:initial-state (fn [params] {:child (prim/get-initial-state Child {})})
   :query         [:ui/react-key {:child (prim/get-query Child)}]}
  (dom/div #js {:key react-key} (ui-child child)))
