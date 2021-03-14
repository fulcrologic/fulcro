(ns com.fulcrologic.fulcro.cards.composition-cards
  (:require
    [nubank.workspaces.card-types.react :as ct.react]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro-css.localized-dom :as dom]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.alpha.raw-components :as raw]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.mutations :as m]))

;; The raw fulcro-app has NO renderer installed. We're doing this example with nothing but raw react. Of course, this
;; means you could embed it in *any* React-based system, since only hooks are required.
(defonce APP (raw/fulcro-app {}))

(m/defmutation bump [{:counter/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:counter/id id :counter/n] inc)))

(defsc Counter [this props]
  {:query         [:counter/id :counter/n]
   :ident         :counter/id
   :initial-state {:counter/id :param/id
                   :counter/n  :param/n}
   ;; Optional. Std components will work fine.
   :use-hooks?    true}
  (dom/button {:onClick #(comp/transact! this [(bump props)])}
    (str (:counter/n props))))

;; Important to use the right factory. This one establishes the stuff you need for nested Fulcro stuff to work
;; according to the book.
(def raw-counter (raw/factory APP Counter {:keyfn :counter/id}))

;; A raw hooks component that uses a Fulcro sub-tree. See docstring on use-fulcro.
(defn SampleComponent [props]
  (let [props (raw/use-fulcro APP Counter {:initial-state-params {:id 1 :n 100}
                                           :keep-existing?       true})]
    (raw-counter props)))

;; Use some hooks state to make a toggle button so we can play with mount/unmount behavior
(defn Top [props]
  (let [[visible? set-visible?] (hooks/use-state false)]
    (dom/div
      (dom/button {:onClick #(set-visible? (not visible?))} "Toggle")
      (when visible?
        (dom/create-element SampleComponent {})))))

;; Render a truly raw react hooks component in a plain react card
(ws/defcard fulcro-composed-into-vanilla-react
  (ct.react/react-card
    (dom/create-element Top {})))