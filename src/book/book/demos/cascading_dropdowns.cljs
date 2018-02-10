(ns book.demos.cascading-dropdowns
  (:require
    [fulcro.ui.bootstrap3 :as bs]
    [fulcro.logging :as log]
    [fulcro.client.data-fetch :as df]
    [fulcro.ui.bootstrap3 :as bs]
    [fulcro.client.mutations :refer [defmutation]]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.server :as server]
    [fulcro.ui.elements :as ele]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(server/defquery-root :models
  (value [env {:keys [make]}]
    (case make
      :ford [(bs/dropdown-item :escort "Escort")
             (bs/dropdown-item :F-150 "F-150")]
      :honda [(bs/dropdown-item :civic "Civic")
              (bs/dropdown-item :accort "Accord")])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-example
  "Wrap an example in an iframe so we can load external CSS without affecting the containing page."
  [width height & children]
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
