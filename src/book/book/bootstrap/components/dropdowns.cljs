(ns book.bootstrap.components.dropdowns
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [book.bootstrap.helpers :refer [render-example sample]]
            [fulcro.ui.bootstrap3 :as b]))

(defsc DropdownRoot [this {:keys [dropdown dropdown-2]}]
  {:initial-state (fn [params] {:dropdown   (b/dropdown :file "File" [(b/dropdown-item :open "Open")
                                                                      (b/dropdown-item :close "Close")
                                                                      (b/dropdown-divider :divider-1)
                                                                      (b/dropdown-item :quit "Quit")])
                                :dropdown-2 (b/dropdown :select "Select One" [(b/dropdown-item :a "A")
                                                                              (b/dropdown-item :b "B")])})
   :query         [{:dropdown (prim/get-query b/Dropdown)} {:dropdown-2 (prim/get-query b/Dropdown)}]}
  (render-example "100%" "150px"
    (let [select (fn [id] (js/alert (str "Selected: " id)))]
      (dom/div #js {:height "100%" :onClick #(prim/transact! this `[(b/close-all-dropdowns {})])}
        (b/ui-dropdown dropdown :onSelect select :kind :success)
        (b/ui-dropdown dropdown-2 :onSelect select :kind :success :stateful? true)))))

