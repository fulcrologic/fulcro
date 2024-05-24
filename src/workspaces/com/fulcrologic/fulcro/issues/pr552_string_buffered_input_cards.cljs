(ns com.fulcrologic.fulcro.issues.pr552-string-buffered-input-cards
  (:require
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :refer [b button div p h3 li p ul]]
    [com.fulcrologic.fulcro.dom.inputs :refer [StringBufferedInput]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.rendering.ident-optimized-render :as ior]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]))

(def MyInput (StringBufferedInput ::MyInput {:model->string identity
                                             :string->model identity}))

(def ui-input (comp/factory MyInput))

(defsc Item
  [this {:item/keys [name]}]
  {:ident         :item/id
   :query         [:item/id :item/name :ui/clicked?]
   :initial-state {:item/id 1 :item/name "Item 1" :ui/clicked? false}}
  (let [v (last (for [n (range 1000000)]
                  (+ n (* 2 n))))]
    (div
      (div (str v))
      (ui-input {:value    name
                 :onChange (fn [v] (m/set-string! this :item/name :value v))})
      (button {:onClick (fn [] (m/set-string! this :item/name :value "Bar"))}
        "Force to Bar")
      (button {:onClick (fn [] (m/set-string! this :item/name :value "Foo"))}
        "Force to Foo"))))

(def ui-item (comp/factory Item {:keyfn :item/id}))

(defsc Root [_this {:root/keys [item]}]
  {:query         [{:root/item (comp/get-query Item)}]
   :initial-state {:root/item {}}}

  (div
    (ui-item item)))

(ws/defcard string-buffered-input-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       Root}))
