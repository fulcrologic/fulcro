(ns cards.server-return-values-targeting
  (:require
    #?@(:cljs [[devcards.core :as dc :refer-macros [defcard defcard-doc]]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [cards.card-utils :refer [sleep]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.server :as server]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj (def clj->js identity))

(server/defmutation trigger-error [_]
  (action [env]
    {:error "something bad"}))

(server/defmutation create-sibling [data]
  (action [env]
    (assoc data :db/id "new-item")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare Item Sibling)

(defmutation trigger-error [_]
  (remote [{:keys [ast ref]}]
    (m/with-target ast (conj ref :op-call))))

(defmutation create-sibling [_]
  (remote [{:keys [ast ref state]}]
    (-> ast
        (m/returning state Sibling)
        (m/with-target (conj ref :sibling)))))

(defsc Sibling
  [this {:keys [item/name]}]
  {:ident [:sibling/by-id :db/id]
   :query [:db/id :item/name]}
  (dom/div nil
    "I'm a sibling " (str name)))

(def ui-sibling (prim/factory Sibling))

(defsc Item [this {:keys [db/id op-call sibling]}]
  {:query [:db/id :op-call {:sibling (prim/get-query Sibling)}]
   :ident [:item/by-id :db/id]}
  (dom/div #js {}
    (if op-call
      (dom/div nil
        "Result: " (pr-str op-call))
      (dom/div nil "Waiting for call"))



    (dom/button #js {:onClick (fn [evt]
                                (prim/transact! this `[(trigger-error {})]))}
      "Trigger Error")
    (if sibling
      (ui-sibling sibling)
      (dom/button #js {:onClick (fn [evt]
                                  (prim/transact! this `[(create-sibling {:item/name "Foo"})]))}
        "Create Sibling"))))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defsc Root [this {:keys [ui/react-key ui/root]}]
  {:query         [:ui/react-key {:ui/root (prim/get-query Item)}]
   :initial-state {:ui/root {}}}
  (dom/div (clj->js {:key react-key})
    "Item below"
    (ui-item root)))

#?(:cljs
   (defcard-fulcro mutation-target
     "# Demonstration

     This shows how you can make the mutation result target a specific place, if you use just the with-params the raw
     response will be placed on the given path as is. If you use in conjunction with returning, the ident for the
     created entity will be place into the target path. This target accepts the same things a load target does."
     Root
     {}
     {:inspect-data true
      :fulcro       {}}))

