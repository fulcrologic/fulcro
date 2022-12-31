(ns com.fulcrologic.fulcro.cards.multi-root-cards
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mroot]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [taoensso.timbre :as log]))

(defsc OtherChild [this {:keys [:other/id :other/n] :as props}]
  {:query         [:other/id :other/n]
   :ident         :other/id
   :initial-state {:other/id :param/id :other/n :param/n}}
  (log/info "OtherChild" (some-> comp/*parent* (comp/component-name)) (comp/depth this))
  (dom/div
    (dom/button
      {:onClick #(m/set-integer! this :other/n :value (inc n))}
      (str n))))

(def ui-other-child (comp/factory OtherChild {:keyfn :other/id}))

(defsc AltRoot [this props]
  {:use-hooks? true}
  (log/info "AltRoot" (some-> comp/*parent* (comp/component-name)) (comp/depth this))
  (let [id      (hooks/use-generated-id)                    ; Generate an ID to use with floating root
        ;; mount a floating root that renders OtherChild
        factory (hooks/use-fulcro-mount this {:initial-state-params {:id id :n 1}
                                              :child-class          OtherChild})]
    ;; Install a GC handler that will clean up the generated data of OtherChild when this component unmounts
    (hooks/use-gc this [:other/id id] #{})
    (dom/div
      (dom/h4 "ALTERNATE ROOT")
      (when factory
        (factory props)))))

(def ui-alt-root (mroot/floating-root-factory AltRoot))

(defsc Child [this {:child/keys [id name] :as props}]
  {:query         [:child/id :child/name]
   :ident         :child/id
   :initial-state {:child/id :param/id :child/name :param/name}}
  (log/info "Child" (some-> comp/*parent* (comp/component-name)) (comp/depth this))
  (dom/div
    (dom/h2 "Regular Tree")
    (dom/label "Child: ")
    (dom/input {:value    (or name "")
                :onChange (fn [evt]
                            (let [v (.. evt -target -value)]
                              (comp/transact! this
                                [(m/set-props {:child/name v})]
                                {:only-refresh [(comp/get-ident this)]})))})
    (ui-alt-root)))

(def ui-child (comp/factory Child {:keyfn :child/id}))

(defsc Root [this {:keys [children] :as props}]
  {:query         [{:children (comp/get-query Child)}]
   :initial-state {:children [{:id 1 :name "Joe"}
                              {:id 2 :name "Sally"}]}}
  (log/info "Root" (some-> comp/*parent* (comp/component-name)) (comp/depth this))
  (let [show? (comp/get-state this :show?)]
    (dom/div
      (dom/button {:onClick (fn [] (comp/set-state! this {:show? (not show?)}))} "Toggle")
      (when show?
        ;; Two children
        (mapv ui-child children)))))

(ws/defcard floating-root-demo-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       Root
     ::ct.fulcro/app        {}}))
