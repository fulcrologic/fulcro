(ns other-demos.multi-root-sample
  (:require
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mroot]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.react.hooks :as hooks]))

(declare AltRootPlainClass app)

(defsc OtherChild [this {:keys [:other/id :other/n] :as props}]
  {:query         [:other/id :other/n]
   :ident         :other/id
   :initial-state {:other/id :param/id :other/n :param/n}}
  (dom/div
    (dom/button
      {:onClick #(m/set-integer! this :other/n :value (inc n))}
      (str n))))

(def ui-other-child (comp/factory OtherChild {:keyfn :other/id}))

(defsc AltRoot [this props]
  {:use-hooks? true}
  (let [id      (hooks/use-generated-id) ; Generate an ID to use with floating root
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
  (dom/div
    (dom/h2 "Regular Tree")
    (dom/label "Child: ")
    (dom/input {:value    (or name "")
                :onChange (fn [evt]
                            (let [v (.. evt -target -value)]
                              (comp/transact! this
                                [(m/set-props {:child/name v})]
                                {:only-refresh [(comp/get-ident this)]})))})
    (dom/div
      (if (= 1 id)
        ;; EACH child renders a floating root component
        (ui-alt-root)
        (dom/create-element AltRootPlainClass)))))

(def ui-child (comp/factory Child {:keyfn :child/id}))

(defsc Root [this {:keys [children] :as props}]
  {:query         [{:children (comp/get-query Child)}]
   :initial-state {:children [{:id 1 :name "Joe"}
                              {:id 2 :name "Sally"}]}}
  (let [show? (comp/get-state this :show?)]
    (dom/div
      (dom/button {:onClick (fn [] (comp/set-state! this {:show? (not show?)}))} "Toggle")
      (when show?
        ;; Two children
        (mapv ui-child children)))))

(defonce app (app/fulcro-app {:optimized-render! mroot/render!}))
(comment
  (-> app ::app/runtime-atom deref ::app/indexes)
  )
(def AltRootPlainClass (mroot/floating-root-react-class AltRoot app))

(defn start []
  (app/mount! app Root "app"))
