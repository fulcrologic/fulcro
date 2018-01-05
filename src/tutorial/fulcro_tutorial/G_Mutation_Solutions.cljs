(ns fulcro-tutorial.G-Mutation-Solutions
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]))

(defcard-doc
  "# Mutation solutions

  The solutions are shown below:

  Exercise 1:

  ```
  (defmutation ex1-inc [params]
    (action [{:keys [state]}]
      (swap! state update :n inc)))
  ```

  Exercise 2:

  ```
  (defmutation ex2-inc [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state update-in [:child/by-id id :n] inc)))
  ```

  Exercise 3:

  In the `defsc` of the item, the calls to transact need to include `:items`, as shown below:

  ```
  (defsc Ex3-Item [this {:keys [id n]}]
    {:query [:id :n]
     :ident [:child/by-id :id]}
    (dom/li nil
      (dom/span nil \"n: \" n)
      ; TODO: Fix the rendering using transaction follow-on property reads
      (dom/button #js {:onClick #(prim/transact! this `[(ex2-inc {:id ~id}) :items])} \"Increment\")
      (dom/button #js {:onClick #(prim/transact! this `[(ex3-dec {:id ~id}) :items])} \"Decrement\")))
  ```

  OR, add it to the mutations:

  ```
  (defmutation ex2-inc [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state update-in [:child/by-id id :n] inc))
    (refresh [env] [:items]))

  (defmutation ex3-dec [{:keys [id]}]
    (action [{:keys [state]}]
      (swap! state update-in [:child/by-id id :n] dec))
    (refresh [env] [:items]))
  ```

  Note that this solution doesn't make a lot of sense from a design perspective. Why would counter mutations
  know that some other component in the system joined them in under the key `:items`? Totally weird and
  unnatural. Don't do this.

  Exercise 4:

  In this solution we move the `transact!` up the tree to the place that ultimately owns the UI responsibility for
  layout (in this case it turns out to be root because of how we structured the example):

  ```
  (defsc Ex4-Item [this {:keys [id n]} {:keys [onIncrement onDecrement]}]
    {:query [:id :n]
     :ident [:child/by-id :id]}
    (dom/li nil
      (dom/span nil \"n: \" n)
      (dom/button #js {:onClick #(onIncrement id)} \"Increment\")
      (dom/button #js {:onClick #(onDecrement id)} \"Decrement\")))

  (defsc Ex4-List [this {:keys [title min max items] :or {min 0 max 1000000}} computed-callbacks]
    {:query [:id :title :max :min {[:items '_] (prim/get-query Ex4-Item)}]
     :ident [:list/by-id :id]}
    (let [onIncrement (fn [id] (prim/transact! this `[(ex2-inc {:id ~id})]))
          onDecrement (fn [id] (prim/transact! this `[(ex3-dec {:id ~id})]))]
      (dom/div #js {:style {:float \"left\" :width \"300px\"}}
        (dom/h4 nil (str title (when (= 0 min) (str \" (n <= \" max \")\"))))
        (dom/ul nil (->> items
                      (filter (fn [{:keys [n]}] (<= min n max)))
                      (map (fn [i] (ui-ex4-item (prim/computed i computed-callbacks)))))))))

  (defsc Ex4-Root [this {:keys [ui/react-key lists]}]
    {:query [:ui/react-key {:lists (prim/get-query Ex4-List)}]}
    (let [onIncrement (fn [id] (prim/transact! this `[(ex2-inc {:id ~id})]))
          onDecrement (fn [id] (prim/transact! this `[(ex3-dec {:id ~id})]))]
      (dom/div #js {:key react-key}
        (map (fn [l] (ui-ex4-list (prim/computed l {:onIncrement onIncrement
                                                    :onDecrement onDecrement})))
          lists))))
  ```
  ")








