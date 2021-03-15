(ns com.fulcrologic.fulcro.cards.composition-cards
  (:require
    [nubank.workspaces.card-types.react :as ct.react]
    [nubank.workspaces.core :as ws]
    [com.fulcrologic.fulcro-css.localized-dom :as dom]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.alpha.raw-components2 :as raw]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.mutations :as m]
    [taoensso.timbre :as log]))

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
(def raw-counter (raw/factory Counter {:keyfn :counter/id}))

(m/defmutation toggle [{:item/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:item/id id :item/complete?] not)))

;; A raw hooks component that uses a Fulcro sub-tree. See docstring on use-fulcro.
(defn SampleComponent [props]
  (raw/with-fulcro APP
    (let [counter-A (raw/use-component APP Counter {:initial-state-params {:id 1 :n 100}
                                                    :keep-existing?       true})
          ;; OR, don't even use a component!!!
          list      (raw/use-tree APP (raw/nc [:list/id {:list/items [:item/id :item/label :item/complete?]}])
                      {:keep-existing? true
                       :initial-tree   {:list/id    1
                                        :list/items [{:item/id 1 :item/label "A"}
                                                     {:item/id 2 :item/label "B"}
                                                     {:item/id 2 :item/label "B"}
                                                     {:item/id 2 :item/label "B"}]}})
          counter-B (raw/use-tree APP (raw/nc [:counter/id :counter/n]) {:initial-tree   {:counter/id 2 :counter/n 45}
                                                                         :keep-existing? true})]
      (dom/div
        (raw-counter counter-A)
        ;; just render the data...you don't have to use Fulcro components at all
        (dom/button {:onClick #(comp/transact! APP [(bump counter-B)])}
          (str (:counter/n counter-B)))

        (dom/ul
          (map-indexed
            (fn [idx {:item/keys [label complete?] :as item}]
              (dom/li {:key (str idx)}
                (dom/input {:type     "checkbox"
                            :checked  (boolean complete?)
                            :onChange (fn [] (comp/transact! APP [(toggle item)]))})
                label))
            (:list/items list)))))))

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

(comment
  (-> APP ::app/runtime-atom deref ::app/render-listeners))