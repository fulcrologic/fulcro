(ns cards.server-return-values-as-data-driven-mutation-joins
  (:require
    #?@(:cljs [[devcards.core :as dc :refer-macros [defcard defcard-doc]]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui]]
    [fulcro.client.dom :as dom]
    [cards.card-utils :refer [sleep]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.core :as fc :refer [defsc]]
    [fulcro.server :as server]
    [fulcro.util :as util]
    [fulcro.client.data-fetch :as df]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-root :mutation-join-list
  (value [env {:keys [kind]}]
    {:db/id      1
     :list/title "Mutation Join List Demo"
     :list/items [{:db/id      1
                   :item/value "A"}
                  {:db/id      2
                   :item/value "B"}]}))

(server/defmutation change-label [{:keys [db/id item/value]}]
  (action [env]
    {:db/id id :item/value (str (util/unique-key))}))

(def ids (atom 999))

(server/defmutation add-item [{:keys [id value]}]
  (action [env]
    (sleep 4000)
    (let [new-id (swap! ids inc)]
      (merge
        {::prim/tempids {id new-id}}
        {:db/id id :item/value value}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(declare Item)

(defmutation change-label [{:keys [db/id]}]
  (remote [{:keys [ast state]}]
    (-> ast
      (m/returning state Item)
      (m/with-params {:db/id id}))))

(defmutation add-item [{:keys [list-id id value]}]
  (action [{:keys [state]}]
    (let [idnt [:item/by-id id]]
      (swap! state
        (fn [s]
          (-> s
            (assoc-in idnt {:db/id id :item/value value})
            (fc/integrate-ident idnt :append [:list/by-id list-id :list/items]))))))
  (remote [{:keys [state ast]}]
    (m/returning ast state Item)))

(defsc Item [this {:keys [db/id item/value]} _ _]
  {:query [:db/id :item/value]
   :ident [:item/by-id :db/id]}
  (dom/li #js {:onClick (fn [evt]
                          (prim/transact! this `[(change-label {:db/id ~id})]))} value))

(def ui-item (prim/factory Item {:keyfn :db/id}))

(defsc ItemList [this {:keys [db/id list/title list/items] :as props} _ _]
  {:query [:db/id :list/title {:list/items (prim/get-query Item)}]
   :ident [:list/by-id :db/id]}
  (dom/div nil
    (dom/h3 nil title)
    (dom/ul nil (map ui-item items))
    (dom/button #js {:onClick #(prim/transact! this `[(add-item {:list-id ~id
                                                                 :id      ~(prim/tempid)
                                                                 :value   "A New Value"})])} "Add item")))

(def ui-list (prim/factory ItemList {:keyfn :db/id}))

(defsc Root [this {:keys [ui/react-key mutation-join-list]} _ _]
  {:query [:ui/react-key {:mutation-join-list (prim/get-query ItemList)}]}
  (dom/div #js {:key react-key}
    "Test"
    (ui-list mutation-join-list)))

#?(:cljs
   (defcard-doc
     "
     # Mutation Joins

     Fulcro 2.0+ supports the ability to return (and auto-normalize) a value from a server-side mutation.

     You can write mutation joins as part of normal transactions, but they are a bit messy to read (see the
     Developer's Guide). The easier way to work with them is to use a regular-looking mutation in the UI,
     and encode the join on the client-side *implementation* of the mutation.

     So, in our demo below our mutation to change a label is called like this:
     "
     (dc/mkdn-pprint-source Item)
     "

     but our mutation can take that incoming AST (for `(change-label)`) and indicate that it will return a value:

     ```
     (defmutation change-label [{:keys [db/id]}]
       (remote [{:keys [ast state]}]
         (-> ast
           (m/returning state Item)
           (m/with-params {:db/id id}))))
     ```

     Note that there is *no* optimistic update here. The change you see on the UI in the demo below is due to the response
     merging into your application state!

     The additional `with-params` just shows there's an additional helper for changing the parameters that would be
     sent to the server (it isn't needed in this example, since that's what the parameters already were).

     ## Normalization

     For normalization to work, the mutation join must use a UI query. This is why the second argument to `returning` is
     a *component*. The query is pulled from that component, which includes the metadata necessary for proper normalization.
     "))

#?(:cljs
   (defcard-fulcro mutation-merge
     "# Demonstration

     The two items in the list are clickable. Clicking on either of them will ask the server to change their value
     to some random UUID and return the updated entity. Using mutation joins this data is auto-merged and the UI updated.

     NOTE: The Add Item button is full-stack, and uses tempids. The server has a 4-second delay on that so you can examine
     the app state as it runs."
     Root
     {}
     {:inspect-data true
      :fulcro       {:started-callback (fn [app] (df/load app :mutation-join-list ItemList))}}))

