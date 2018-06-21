(ns book.ui.focus-example
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.mutations :as m]))

(defsc ClickToEditField [this {:keys [value editing?]}]
  {:initial-state      {:value    "ABC"
                        :db/id    1
                        :editing? false}
   :query              [:db/id :value :editing?]
   :ident              [:field/by-id :db/id]
   :componentDidUpdate (fn [prev-props _]
                         (when (and (not (:editing? prev-props)) (:editing? (prim/props this)))
                           (let [input-field        (dom/node this "edit_field")
                                 input-field-length (.. input-field -value -length)]
                             (.focus input-field)
                             (.setSelectionRange input-field input-field-length input-field-length))))}
  (dom/div
    ; trigger a focus based on a state change (componentDidUpdate)
    (dom/a {:onClick #(m/toggle! this :editing?)}
      "Click to focus (if not already editing): ")
    (dom/input {:value    value
                :onChange #(m/set-string! this :event %)
                :ref      "edit_field"})
    ; do an explicit focus
    (dom/button {:onClick (fn []
                            (let [input-field        (dom/node this "edit_field")
                                  input-field-length (.. input-field -value -length)]
                              (.focus input-field)
                              (.setSelectionRange input-field 0 input-field-length)))}
      "Highlight All")))

(def ui-click-to-edit (prim/factory ClickToEditField))

(defsc Root [this {:keys [field] :as props}]
  {:query         [{:field (prim/get-query ClickToEditField)}]
   :initial-state {:field {}}}
  (ui-click-to-edit field))
