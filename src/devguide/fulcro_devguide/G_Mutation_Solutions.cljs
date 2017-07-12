(ns fulcro-devguide.G-Mutation-Solutions
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]))

; TODO: verify these are good? integrate-ident!?
(defcard-doc
  "# Mutation solutions

  The solutions are shown below:

  Exercise 1:

  ```
  (defmethod m/mutate 'exercise/g-ex1-inc [{:keys [state]} k p]
    {:action #(swap! state update :n inc)})
  ```

  Exercise 2:

  ```
  (defmethod m/mutate 'exercise/g-ex2-inc [{:keys [state]} k {:keys [id]}]
    {:action #(swap! state update-in [:child/by-id id :n] inc)})
  ```

  Exercise 3:

  In the `defui` of the item, the calls to transact need to include `:items`, as shown below:

  ```
  (render [this]
    (let [{:keys [id n]} (om/props this)]
      (dom/li nil
        (dom/span nil \" n: \" n)
        (dom/button #js {:onClick #(om/transact! this `[(exercise/g-ex2-inc {:id ~id}) :items])} \"Increment\")
        (dom/button #js {:onClick #(om/transact! this `[(exercise/g-ex3-dec {:id ~id}) :items])} \"Decrement\")))))
  ```
  ")








