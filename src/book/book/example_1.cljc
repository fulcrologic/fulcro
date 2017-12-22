(ns book.example-1
  (:require [fulcro.client.primitives :as fp :refer [defsc]]
            [fulcro.client.mutations :refer [defmutation]]
            [fulcro.client.dom :as dom]))

(defmutation bump-number [ignored]
  (action [{:keys [state]}]
    (swap! state update :ui/number inc)))

(defsc Root [this {:keys [ui/number]}]
  {:query         [:ui/number]
   :initial-state {:ui/number 0}}
  (dom/div nil
    (dom/h4 nil "This is an example.")
    (dom/button #js {:onClick #(fp/transact! this `[(bump-number {})])}
      "You've clicked this button " number " times.")))
