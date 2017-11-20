(ns recipes.cascading-dropdown-client
  (:require
    [fulcro.client.core :as fc]
    [fulcro.client.data-fetch :as df]
    [om.dom :as dom]
    [fulcro.ui.bootstrap3 :as bs]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [om.next :as om :refer [defui]]
    [fulcro.ui.elements :as ele]))


(defn render-example [width height & children]
  (ele/ui-iframe {:frameBorder 0 :height height :width width}
    (apply dom/div #js {:key "example-frame-key"}
      (dom/style nil ".boxed {border: 1px solid black}")
      (dom/link #js {:rel "stylesheet" :href "bootstrap-3.3.7/css/bootstrap.min.css"})
      children)))

(defmutation show-list-loading
  "Change the items of the dropdown with the given ID to a single item that indicates Loading..."
  [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state assoc-in
      [:bootstrap.dropdown/by-id id :fulcro.ui.bootstrap3/items]
      [(assoc (bs/dropdown-item :loading "Loading...") :fulcro.ui.bootstrap3/disabled? true)])))

(defui ^:once Root
  static fc/InitialAppState
  (initial-state [this props]
    {:make-dropdown  (bs/dropdown :make "Make" [(bs/dropdown-item :ford "Ford")
                                                (bs/dropdown-item :honda "Honda")])
     ; leave the model items empty
     :model-dropdown (bs/dropdown :model "Model" [])})
  static om/IQuery
  (query [this] [:ui/react-key
                 ; initial state for two Bootstrap dropdowns
                 {:make-dropdown (om/get-query bs/Dropdown)}
                 {:model-dropdown (om/get-query bs/Dropdown)}])
  Object
  (render [this]
    (let [{:keys [:ui/react-key make-dropdown model-dropdown]} (om/props this)
          {:keys [:fulcro.ui.bootstrap3/items]} model-dropdown]
      (render-example "200px" "200px"
        (dom/div #js {:key react-key}
          (bs/ui-dropdown make-dropdown
            :onSelect (fn [item]
                        ; Update the state of the model dropdown to show a loading indicator
                        (om/transact! this `[(show-list-loading {:id :model})])
                        ; Issue the remote load. Note the use of DropdownItem as the query, so we get proper normalization
                        ; The targeting is used to make sure we hit the correct dropdown's items
                        (df/load this :models bs/DropdownItem {:target [:bootstrap.dropdown/by-id :model :fulcro.ui.bootstrap3/items]
                                                               ; don't overwrite state with loading markers...we're doing that manually to structure it specially
                                                               :marker false
                                                               ; A server parameter on the query
                                                               :params {:make item}}))
            :stateful? true)
          (bs/ui-dropdown model-dropdown
            :onSelect (fn [item] (js/console.log item))
            :stateful? true))))))

