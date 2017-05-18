(ns untangled-devguide.G-Mutation-Exercises
  (:require-macros [cljs.test :refer [is]]
                   [untangled-devguide.tutmacros :refer [untangled-app]])
  (:require [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [untangled.client.core :as uc]
            [untangled.client.mutations :as m]
            [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [cljs.reader :as r]
            [om.next.impl.parser :as p]))

(defcard-doc "# Mutation exercises ")

; TODO: Exercise 1: Implement this mutation
(defmethod m/mutate 'exercise/g-ex1-inc [{:keys [state]} k p]
  )

(defui ^:once Ex1-Root
  static om/IQuery
  (query [this] [:ui/react-key :n])
  Object
  (render [this]
    (let [{:keys [ui/react-key n]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/hr nil)
        (dom/p nil "n: " n)
        (dom/button #js {:onClick #(om/transact! this '[(exercise/g-ex1-inc)])} "Increment")))))

(defcard mutation-exercise-1
  "
  ## Exercise 1 - Basic mutation, the thunk

  Open the source for this file and look at Ex1-Root. Notice the transact that
   is calling `exercise/ex1-inc`. Look just above the `defui` and find the `defmethod` for
    that mutation. *implement this improperly* with the following:

    ```
    (swap! state update :n inc)
    ```

    and play with the UI. What happens?

    If you did it as instructed, you should see the number jump by two each time. This is
    because we broke the cardinal rule: mutations return a thunk. The internals of Om
    run the mutation methods more than once, which is why the proper implementation should
    return a map that indicates a lambda to run instead.

    Fix the implementation.
    "
  (untangled-app Ex1-Root)
  {:n 1})

(defui ^:once Ex2-Child
  static om/IQuery
  (query [this] [:id :n])
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id n]} (om/props this)]
      (dom/li nil
        (dom/span nil "n: " n)
        (dom/button #js {:onClick #(om/transact! this `[(exercise/g-ex2-inc {:id ~id})])} "Increment")))))

(def ui-ex2-child (om/factory Ex2-Child {:keyfn :id}))

(defui ^:once Ex2-Root
  static om/IQuery
  (query [this] [:ui/react-key {:items (om/get-query Ex2-Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key items]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/ul nil (map ui-ex2-child items))))))

; TODO: Exercise 2: Implement this mutation
(defmethod m/mutate 'exercise/g-ex2-inc [{:keys [state]} k {:keys [id]}]
  )

(defcard mutation-exercise-2
  "## Exercise 2 - Mutation parameters

  Now move onto the source for Ex2-Root. Notice the transact is now on a child, and uses a
  different mutation that accepts parameters. Find the TODO marker and implement this mutation.

  Be sure to examine the app database content (shown at the bottom of this card and in the source).
  "
  (untangled-app Ex2-Root)
  {:items       [[:child/by-id 1] [:child/by-id 2]]
   :child/by-id {1 {:id 1 :n 3}
                 2 {:id 2 :n 9}}}
  {:inspect-data true})

(defmethod m/mutate 'exercise/g-ex3-dec [{:keys [state]} k {:keys [id]}]
  {:action #(swap! state update-in [:child/by-id id :n] dec)})

(defui ^:once Ex3-Item
  static om/IQuery
  (query [this] [:id :n])
  static om/Ident
  (ident [this props] [:child/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [id n]} (om/props this)]
      (dom/li nil
        (dom/span nil "n: " n)
        ; TODO: Fix the rendering using transaction follow-on property reads
        (dom/button #js {:onClick #(om/transact! this `[(exercise/g-ex2-inc {:id ~id})])} "Increment")
        (dom/button #js {:onClick #(om/transact! this `[(exercise/g-ex3-dec {:id ~id})])} "Decrement")))))

(def ui-ex3-item (om/factory Ex3-Item {:keyfn :id}))

(defui ^:once Ex3-List
  static om/IQuery
  (query [this] [:id :title :max :min {[:items '_] (om/get-query Ex3-Item)}])
  static om/Ident
  (ident [this props] [:list/by-id (:id props)])
  Object
  (render [this]
    (let [{:keys [title min max items] :or {min 0 max 1000000}} (om/props this)]
      (dom/div #js {:style {:float "left" :width "300px"}}
        (dom/h4 nil (str title (when (= 0 min) (str " (n <= " max ")"))))
        (dom/ul nil (->> items
                      (filter (fn [{:keys [n]}] (<= min n max)))
                      (map ui-ex3-item)))))))

(def ui-ex3-list (om/factory Ex3-List {:keyfn :id}))

(defui ^:once Ex3-Root
  static om/IQuery
  (query [this] [:ui/react-key {:lists (om/get-query Ex3-List)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key lists]} (om/props this)]
      (dom/div #js {:key react-key}
        (map ui-ex3-list lists)))))

(defcard mutation-exercise-3
  "## Exercise 3 - Mutations and rendering

  Using the same source and mutation from the prior example, try out this card (it won't work
  until you've completed the exercises above, and you may need to reload the browser
  between your code fixes on this one).

  Notice that we've made two lists that choose to display items based on their numeric value. The mutations
  are already hooked up, so you can start pressing buttons right away. You should notice that
  it doesn't quite work right.

  The problem is that the element in the UI is changing state that some other part of the UI needs.

  Om is perfectly happy to update elements with the same Ident when you transact on them, but it
  does not keep track of a dependency tree. Instead, it knows which components queried for what
  data.

  The help it needs is for you to describe what real data is changing as a result of the
  abstract mutation. In this case, anything that queries for `:items` should re-render.

  To indicate this, you simply add `:items` to `transact!` after your mutation.

  Fix the code and make sure it works by playing with the UI.

  Also, try changing the database min/max data so that the lists will have
  overlap and render some of the same items (and reload the page). Note that all of the
  rendering updates are working perfectly now.
  "
  (untangled-app Ex3-Root)
  {:items       [[:child/by-id 1] [:child/by-id 2]]
   :lists       [[:list/by-id 1] [:list/by-id 2]]
   :list/by-id  {1 {:id 1 :title "Small" :max 10}
                 2 {:id 2 :title "Big" :min 11}}
   :child/by-id {1 {:id 1 :n 3}
                 2 {:id 2 :n 9}}}
  {:inspect-data true})
