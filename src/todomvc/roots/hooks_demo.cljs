(ns roots.hooks-demo
  (:require
    [com.fulcrologic.fulcro.rendering.multiple-roots-renderer :as mroot]
    [com.fulcrologic.fulcro.algorithms.indexing :as index]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :as m]
    [goog.object :as gobj]
    [taoensso.timbre :as log]))

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

;; TODO: Add `:use-hooks?` support to component options. When there, a `defsc` like this:
;; (defsc Hook [this {:hook/keys [x]}]
;;   {:use-hooks? true
;;    :ident :hook/id
;;    :query [:hook/id :hook/x]
;;    :initial-state (fn [{:keys [id]}] {:hook/id id :hook/x 1})}
;;   (dom/div "This ..." ...))
;;
;; should output:
#_(def Hook
    (comp/configure-hooks-component!
      {:componentName ::Hook
       :ident         (fn [this props] [:hook/id (:hook/id props)])
       :query         (fn [_] [:hook/id :hook/x])
       :initial-state (fn [{:keys [id] :as params}] {:hook/id id :hook/x 1})
       :render        (fn [this {:hook/keys [x] :as props}]
                        (dom/div "This is a hooks-based component: "
                          (dom/button {:onClick #(m/set-integer! this :hook/x :value (inc x))}
                            (str x))))}
      (fn [] Hook)))

(defsc SomeHookChild [this {:child/keys [id label] :as props}]
  {:query         [:child/id :child/label]
   :initial-state {:child/id :param/id :child/label (str "some child")}
   :ident         :child/id}
  (dom/div "Child " label
    (dom/input {:value   label
                :onInput (fn [evt] (m/set-string! this :child/label :event evt))})))

(def ui-some-hook-child (comp/factory SomeHookChild {:keyfn :child/id}))

(defsc Hook [this {:hook/keys [id x child] :as props}]
  {:query         [:hook/id :hook/x {:hook/child (comp/get-query SomeHookChild)}]
   :ident         :hook/id
   :initial-state {:hook/x     1 :hook/id :param/id
                   :hook/child {:id :param/id}}
   :use-hooks?    true}
  (let [[v set-v!] (comp/use-state 0)]
    (dom/div "This is a hooks-based component: "
      (ui-some-hook-child child)
      (dom/button {:onClick #(set-v! (inc v))} (str v))
      (dom/button {:onClick #(m/set-integer! this :hook/x :value (inc x))}
        (str x)))))

(def ui-hook (comp/factory Hook {:keyfn :hook/id}))

(defsc Root [this {:keys [hook hooks] :as props}]
  {:query         [{:hook (comp/get-query Hook)}
                   {:hooks (comp/get-query Hook)}]
   :initial-state {:hook  {:id 1}
                   :hooks [{:id 2} {:id 3}]}}
  (dom/div
    (ui-hook hook)
    (dom/h2 "Children")
    (map ui-hook hooks)))

(comment
  (comp/get-query Hook))

(defonce app (app/fulcro-app {}))

(defn start []
  (app/mount! app Root "app"))
