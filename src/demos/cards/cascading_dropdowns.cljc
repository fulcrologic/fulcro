(ns cards.cascading-dropdowns
  (:require
    #?@(:cljs [[fulcro.client.cards :refer [defcard-fulcro]]
               [devcards.core :as dc :refer-macros [defcard-doc]]])
    [fulcro.ui.bootstrap3 :as bs]
    [fulcro.client :as fc]
    [fulcro.client.logging :as log]
    [fulcro.client.data-fetch :as df]
    [fulcro.ui.bootstrap3 :as bs]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.server :as server]
    [fulcro.ui.elements :as ele]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-root :models
  (value [env {:keys [make]}]
    #?(:clj (Thread/sleep 2000))
    (case make
      :ford [(bs/dropdown-item :escort "Escort")
             (bs/dropdown-item :F-150 "F-150")]
      :honda [(bs/dropdown-item :civic "Civic")
              (bs/dropdown-item :accort "Accord")])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-example [width height & children]
  #?(:clj  nil
     :cljs (ele/ui-iframe {:frameBorder 0 :height height :width width}
             (apply dom/div #js {:key "example-frame-key"}
               (dom/style nil ".boxed {border: 1px solid black}")
               (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
               children))))

(defmutation show-list-loading
  "Change the items of the dropdown with the given ID to a single item that indicates Loading..."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state assoc-in
      [:bootstrap.dropdown/by-id id :fulcro.ui.bootstrap3/items]
      [(assoc (bs/dropdown-item :loading "Loading...") :fulcro.ui.bootstrap3/disabled? true)])))

(defsc Root [this {:keys [:ui/react-key make-dropdown model-dropdown]}]
  {:initial-state (fn [params]
                    {:make-dropdown  (bs/dropdown :make "Make" [(bs/dropdown-item :ford "Ford")
                                                                (bs/dropdown-item :honda "Honda")])
                     ; leave the model items empty
                     :model-dropdown (bs/dropdown :model "Model" [])})
   :query         [:ui/react-key
                   ; initial state for two Bootstrap dropdowns
                   {:make-dropdown (prim/get-query bs/Dropdown)}
                   {:model-dropdown (prim/get-query bs/Dropdown)}]}
  (let [{:keys [:fulcro.ui.bootstrap3/items]} model-dropdown]
    (render-example "200px" "200px"
      (dom/div #js {:key react-key}
        (bs/ui-dropdown make-dropdown
          :onSelect (fn [item]
                      ; Update the state of the model dropdown to show a loading indicator
                      (prim/transact! this `[(show-list-loading {:id :model})])
                      ; Issue the remote load. Note the use of DropdownItem as the query, so we get proper normalization
                      ; The targeting is used to make sure we hit the correct dropdown's items
                      (df/load this :models bs/DropdownItem {:target [:bootstrap.dropdown/by-id :model :fulcro.ui.bootstrap3/items]
                                                             ; don't overwrite state with loading markers...we're doing that manually to structure it specially
                                                             :marker false
                                                             ; A server parameter on the query
                                                             :params {:make item}}))
          :stateful? true)
        (bs/ui-dropdown model-dropdown
          :onSelect (fn [item] (log/info item))
          :stateful? true)))))

#?(:cljs
   (defcard-doc
     "# Cascading Dropdowns

     A common UI desire is to have dropdowns that cascade. I.e. a dropdown populates in response to a selection in
     an earlier dropdown, like Make/Model for cars.

     This can be done quite easily. This is a full-stack example that requires you run the demo server. See the Intro
     card for instructions.

     The basic implementation is as follows:

     1. Define dropdowns that can display the items
     2. Don't initialize the extra ones with items
     3. When the first one is given a selection, load the next one

     A couple of simple implementation details are needed:

     1. We're using bootstrap dropdowns, and we need to know where they normalize their data. Looking at the data inspector
     for the card makes this easy to see. For example, we can see that items are stored in the
     `:bootstrap.dropdown/by-id` table, in the `:fulcro.ui.bootstrap3/items` column.
     2. The IDs of the dropdowns (which we generate)

     On the server, we define the query handler as follows (with a 2-second delay so we can play with load visualization):

     ```
     (defquery-root :models
       (value [env {:keys [make]}]
         (Thread/sleep 2000)
         (case make
           :ford [(bs/dropdown-item :escort \"Escort\")
                  (bs/dropdown-item :F-150 \"F-150\")]
           :honda [(bs/dropdown-item :civic \"Civic\")
                   (bs/dropdown-item :accort \"Accord\")])))
     ```

     The complete UI is then just:
     "
     (dc/mkdn-pprint-source Root)
     "

     and we define a mutation for showing a \"Loading...\" item in the dropdown that is loading as:

     ```
     (defmutation show-list-loading
       \"Change the items of the dropdown with the given ID to a single item that indicates Loading...\"
       [{:keys [id]}]
       (action [{:keys [state]}]
         (swap! state assoc-in
           [:bootstrap.dropdown/by-id id :fulcro.ui.bootstrap3/items]
           [(assoc (bs/dropdown-item :loading \"Loading...\") :fulcro.ui.bootstrap3/disabled? true)])))
     ```

     The main action is in the `onSelect` of the first dropdown, which just issues the transact to set the loading
     visualization, followed by the remote load.
     "))

#?(:cljs
   (defcard-fulcro cascading-dropdown-card
     Root
     {}
     {:inspect-data true}))
