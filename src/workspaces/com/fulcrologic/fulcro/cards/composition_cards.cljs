(ns com.fulcrologic.fulcro.cards.composition-cards
  (:require
    [com.fulcrologic.fulcro.dom :as dom]
    [nubank.workspaces.model :as wsm]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect-client]
    [nubank.workspaces.card-types.react :as ct.react]
    [nubank.workspaces.core :as ws]
    [taoensso.timbre :as log]))

;; The raw fulcro-app has NO renderer installed. We're doing this example with nothing but raw react. Of course, this
;; means you could embed it in *any* React-based system, since only hooks are required.
(defonce APP (let [app (rapp/fulcro-app {})]
               (inspect-client/app-started! app)
               app))

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
  (dom/button :.ui.red.button {:onClick #(comp/transact! this [(bump props)])}
    (str (:counter/n props))))

;; Important to use the right factory. This one establishes the stuff you need for nested Fulcro stuff to work
;; according to the book.
(def raw-counter (comp/factory Counter {:keyfn :counter/id}))

(m/defmutation toggle [{:item/keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:item/id id :item/complete?] not)))

;; A raw hooks component that uses a Fulcro sub-tree. See docstring on use-fulcro.
(defn SampleComponent [props]
  (comp/with-parent-context APP
    (let [counter-A (hooks/use-component APP Counter {:initial-params {:id 1 :n 100}
                                                      :initialize?    true
                                                      :keep-existing? true})
          ;; OR, don't even define a concrete component!!!
          anon-list (rc/entity->component {:list/id    1
                                           ;; A little tricky. :item/complete? HAS to be in at least the first one,
                                           ;; or the generated query for the component will not include it.
                                           :list/items [{:item/complete? false :item/id 1 :item/label "A"}
                                                        {:item/id 2 :item/label "B"}
                                                        {:item/id 2 :item/label "B"}
                                                        {:item/id 2 :item/label "B"}]})
          list      (hooks/use-component APP anon-list {:keep-existing? true
                                                        :initialize?    true})
          counter-B (hooks/use-component APP (rc/entity->component {:counter/id 2 :counter/n 45}) {:keep-existing? true
                                                                                                   :initialize?    true})]
      (dom/div
        (raw-counter counter-A)
        ;; just render the data...you don't have to use Fulcro components at all
        (dom/button :.ui.primary.button {:onClick #(comp/transact! APP [(bump counter-B)])}
          (str (:counter/n counter-B)))

        (dom/h3 "List")
        (dom/ul :.ui.list
          (map-indexed
            (fn [idx {:item/keys [label complete?] :as item}]
              (dom/li :.item {:key (str idx)}
                (dom/div :.ui.checkbox
                  (dom/input {:type     "checkbox"
                              :checked  (boolean complete?)
                              :onChange (fn [] (comp/transact! APP [(toggle item)]))})
                  (dom/label label))))
            (:list/items list)))))))

;; Use some hooks state to make a toggle button so we can play with mount/unmount behavior
(defn Top [props]
  (let [[visible? set-visible?] (hooks/use-state false)]
    (dom/div
      (dom/button :.ui.button {:onClick #(set-visible? (not visible?))} "Toggle")
      (when visible?
        (dom/create-element SampleComponent {})))))

;; Render a truly raw react hooks component in a plain react card
(ws/defcard fulcro-composed-into-vanilla-react
  {::wsm/align {:flex 1}}
  (ct.react/react-card
    (dom/create-element Top {})))

(comment
  (-> APP ::app/runtime-atom deref ::app/render-listeners))