(ns fulcro.democards.react16-cards
  (:require [devcards.core :as dc :refer [defcard]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro make-root]]
            [fulcro.client.primitives :as prim :refer [defsc defui]]
            [goog.object :as gobj]
            [fulcro.client.mutations :as m :refer [defmutation]]))

(defmutation bump-with-root-refresh [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:counter/by-id id :n] inc))
  (refresh [env]
    [:counters]))

(defsc CounterButton [this {:keys [id n]} {:keys [onClick]}]
  {:query                   [:id :n]
   :initial-state           {:id :param/id :n 1}
   :ident                   [:counter/by-id :id]
   :initLocalState          (fn [] {:m 20})
   :getSnapshotBeforeUpdate (fn [prev-props prev-state]
                              #_(js/console.log :GET_SNAP :pp prev-props :cp (prim/props this) :ps prev-state :cs (prim/get-state this))
                              :SNAP!)
   :componentDidUpdate      (fn [prev-props prev-state snapshot] #_(js/console.log :snap snapshot))
   :componentDidMount       (fn [] #_(js/console.log :did-mount (prim/get-ident this) :cp (prim/props this) :cs (prim/get-state this)))

   ; :componentWillUnmount      (fn [] (js/console.log :will-mount (prim/get-ident this) :cp (prim/props this) :cs (prim/get-state this)))
   ;:componentWillReceiveProps (fn [next-props] (js/console.log :will-rp :curr-props (prim/props this) :next-props next-props))
   ;:componentWillUpdate       (fn [next-props next-state] (js/console.log :will-update :cp (prim/props this) :np next-props :cs (prim/get-state this) :crs (prim/get-rendered-state this) :ns next-state))
   ;:componentWillMount        (fn [] (js/console.log :will-mount :cp (prim/props this) :cs (prim/get-state this)))
   ;:UNSAFE_componentWillReceiveProps (fn [next-props] (js/console.log :will-rp :curr-props (prim/props this) :next-props next-props))
   ;:UNSAFE_componentWillUpdate       (fn [next-props next-state] (js/console.log :will-update :cp (prim/props this) :np next-props :cs (prim/get-state this) :crs (prim/get-rendered-state this) :ns next-state))
   ;:UNSAFE_componentWillMount (fn [] #_(js/console.log :will-mount :cp (prim/props this) :cs (prim/get-state this)))
   }
  (dom/div
    #_(js/console.log :pmeta (meta (prim/props this)))
    (dom/button {:onClick (fn [] (prim/update-state! this update :m inc))} (str "M: " (prim/get-state this :m)))

    (dom/button {:onClick (fn []
                            (onClick id)
                            #_(m/set-value! this :n (inc n))
                            #_(prim/transact! this `[(bump-with-root-refresh {:id ~id})]))} (str "N: " n))))

(def ui-counter (prim/factory CounterButton {:keyfn :id}))

(defsc StateUpdateThingy [this {:keys [counters]}]
  {:query          [{:counters (prim/get-query CounterButton)}]
   :initLocalState {:n 22}
   :ident          (fn [] [:A 1])
   :initial-state  {:counters [{:id 1} {:id 2} {:id 3}]}}
  (let [n        (prim/get-state this :n)
        onClick  #(prim/transact! this `[(bump-with-root-refresh {:id ~%})])
        counters (map #(prim/computed % {:onClick onClick}) counters)]
    (dom/div
      (dom/h3 "Counters")
      (dom/p (str "State of root " n))
      (dom/button {:onClick #(prim/update-state! this update :n inc)} "Trigger State Update")
      (dom/ul
        (map ui-counter counters)))))

(defcard-fulcro state-thingy-card
  (make-root StateUpdateThingy {})
  {}
  {:inspect-data true})

(defsc ComputedChild
  [this {:keys [my-value] :as p} {:keys [on-select]}]
  {:initial-state (fn [_]
                    {::id      (random-uuid)
                     :my-value "initial"})
   :ident         [::id ::id]
   :query         [::id :my-value]
   :css           []
   :css-include   []}
  (dom/div
    (dom/div "VALUE - " my-value)
    (dom/button {:onClick #(m/set-value! this :my-value "A")} "Set local A")
    (dom/button {:onClick #(m/set-value! this :my-value "B")} "Set local B")
    (dom/button {:onClick #(on-select "From inside")} "Call computed callback")))

(def input-component (prim/factory ComputedChild))

(defsc DemoContainer
  [this {:keys [input ui/local-value] :as p}]
  {:initial-state (fn [_]
                    {:input          (prim/get-initial-state ComputedChild {})
                     :ui/local-value "initial"})
   :ident         (fn [] [:id "singleton"])
   :query         [:id {:input (prim/get-query ComputedChild)}
                   :ui/local-value]}
  (dom/div
    (dom/div "Outer - " local-value)
    (input-component (prim/computed input {:on-select #(m/set-value! this :ui/local-value %)}))))

(defcard-fulcro broke
  (make-root DemoContainer {})
  {}
  {:inspect-data true})

(defsc FragDemo [this props]
  (prim/fragment
    (dom/p "Hi someone")
    (dom/p "There")))

(def ui-frag-demo (prim/factory FragDemo))

(defcard fragments
  (dom/div
    (dom/p "sibling")
    (ui-frag-demo {:n (rand-int 10000)})))

(defmutation insert-in-front [params]
  (action [{:keys [state]}]
    (let [id   (random-uuid)
          item {:id id :value (str (rand-int 120000))}]
      (swap! state (fn [s]
                     (-> s
                       (assoc-in [:items/by-id id] item)
                       (update-in [:lists/by-id 1 :items] (fn [v]
                                                            (into [[:items/by-id id]] v)))))))))

(defsc MyItem [this {:keys [value] :as props}]
  {:query         [:id :value]
   :ident         [:items/by-id :id]
   :initial-state {:id :param/id :value :param/v}}
  (js/console.log (-> props meta))
  (dom/li
    (dom/a {:onClick #(prim/transact! this `[(insert-in-front {}) :items])} value)))

(def ui-item (prim/factory MyItem {:keyfn :id}))

(defsc MyList [this {:keys [items]}]
  {:query         [:id {:items (prim/get-query MyItem)}]
   :ident         [:lists/by-id :id]
   :initial-state {:id 1 :items [{:id 2 :v "A"} {:id 3 :v "B"}]}}
  (dom/ul
    (map ui-item items)))

(def ui-list (prim/factory MyList {:keyfn :id}))

(defsc Root [this {:keys [ROOT] :as props}]
  {:query                    [{:ROOT (prim/get-query MyList)}]
   :getDerivedStateFromProps (fn [props state]
                               (js/console.log :gdsfp props state)
                               {:x 42})
   :initial-state            {:ROOT {}}}
  (js/console.log :root-props props :st (prim/get-state this))
  (dom/div
    (dom/button {:onClick #(prim/set-state! this {:n 1})})
    (ui-list ROOT)))

(defcard-fulcro reordering-to-many-and-get-derived-state-from-props
  Root
  {}
  {:inspect-data true})
