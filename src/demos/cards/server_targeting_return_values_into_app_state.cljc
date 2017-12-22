(ns cards.server-targeting-return-values-into-app-state
  (:require
    #?@(:cljs [[devcards.core :as dc :refer-macros [defcard defcard-doc]]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [cards.card-utils :refer [sleep]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.server :as server]
    [fulcro.client.data-fetch :as df]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#?(:clj (def clj->js identity))

(def ids (atom 1))

(server/defmutation ^{:intern server-trigger-error} trigger-error [_]
  (action [env]
    {:error "something bad"}))

(server/defmutation ^{:intern server-create-entity} create-entity [{:keys [db/id]}]
  (action [env]
    (let [real-id (swap! ids inc)]
      {:db/id        real-id
       :entity/label (str "Entity " real-id)
       :tempids      {id real-id}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare Item Entity)

(defmutation ^:intern trigger-error
  "This mutation causes an unstructured error (just a map), but targets that value
   to the field `:error-message` on the component that invokes it."
  [_]
  (remote [{:keys [ast ref]}]
    (m/with-target ast (conj ref :error-message))))

(defmutation ^:intern create-entity
  "This mutation simply creates a new entity, but targets it to a specific location
  (in this case the `:child` field of the invoking component)."
  [{:keys [where?] :as params}]
  (remote [{:keys [ast ref state]}]
    (let [path-to-target (conj ref :children)
          ; replacement cannot succeed if there is nothing present...turn those into appends
          no-items?      (empty? (get-in @state path-to-target))
          where?         (if (and no-items? (= :replace-first where?))
                           :append
                           where?)]
      (cond-> (-> ast
                ; always set what kind of thing is coming back
                (m/returning state Entity)
                ; strip the where?...it is for local use only (not server)
                (m/with-params (dissoc params :where?)))
        ; Add the targeting...based on where?
        (= :append where?) (m/with-target (df/append-to path-to-target)) ; where to put it
        (= :prepend where?) (m/with-target (df/prepend-to path-to-target))
        (= :replace-first where?) (m/with-target (df/replace-at (conj path-to-target 0)))))))

(defsc Entity [this {:keys [entity/label]}]
  {:ident [:entity/by-id :db/id]
   :query [:db/id :entity/label]}
  (dom/div nil label))

(def ui-entity (prim/factory Entity {:keyfn :db/id}))

(defsc Item [this {:keys [db/id error-message children]}]
  {:query         [:db/id :error-message {:children (prim/get-query Entity)}]
   :initial-state {:db/id :param/id :children []}
   :ident         [:item/by-id :db/id]}
  (dom/div (clj->js {:style {:float  :left
                             :width  "200px"
                             :margin "5px"
                             :border "1px solid black"}})
    (dom/h4 nil (str "Item " id))
    (when error-message
      (dom/div nil "The generated error was: " (pr-str error-message)))
    (dom/button #js {:onClick (fn [evt] (prim/transact! this `[(trigger-error {})]))} "Trigger Error")
    (dom/h6 nil "Children")
    (map ui-entity children)
    (dom/button #js {:onClick (fn [evt] (prim/transact! this `[(create-entity {:where? :prepend :db/id ~(prim/tempid)})]))} "Prepend one!")
    (dom/button #js {:onClick (fn [evt] (prim/transact! this `[(create-entity {:where? :append :db/id ~(prim/tempid)})]))} "Append one!")
    (dom/button #js {:onClick (fn [evt] (prim/transact! this `[(create-entity {:where? :replace-first :db/id ~(prim/tempid)})]))} "Replace first one!")))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defsc Root [this {:keys [ui/react-key root/items]}]
  {:query         [:ui/react-key {:root/items (prim/get-query Item)}]
   :initial-state {:root/items [{:id 1} {:id 2} {:id 3}]}}
  (dom/div (clj->js {:key react-key})
    (mapv ui-item items)
    (dom/br #js {:style #js {:clear "both"}})))

#?(:cljs
   (defcard-doc
     "# Targeting Return values

     This demo shows you how to use the `with-target` mutation modifier to cause the return value of the server to
     be placed in a location of your choosing.

     ## Targeting Raw Values

     If you don't specify a component with `returning`, then your returned data can be targeted, but of course it
     won't normalize. The error triggering mutation does this:

     "
     (dc/mkdn-pprint-source trigger-error)
     "and the server simply returns a raw value (map is recommended)"
     (dc/mkdn-pprint-source server-trigger-error)
     "

     ## Targeting Graph Results

     The more interesting case is when you want normalization, but you also need to pepper the return
     value's ident around the state of your application. The default `returning` just refreshes (or loads)
     the entity into its table, which means anything that already way linked to it will update, but if it is
     new there can be no UI refresh because nothing in the UI will be using it.

     This is where targeting shines. In the demo The bulk of the work is done in the `create-entity` mutation.
     which is targeting to-many so we can demo more features (you can
     also target to-one with a simple path). Multiple targets are also supported (see load documentation).
     "
     (dc/mkdn-pprint-source create-entity)
     "The server mutation just returns the entity (mixed with tempid remappings, if you need them)."
     (dc/mkdn-pprint-source server-create-entity)))

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

