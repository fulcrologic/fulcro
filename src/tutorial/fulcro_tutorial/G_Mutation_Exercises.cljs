(ns fulcro-tutorial.G-Mutation-Exercises
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client :as fc]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :include-macros true :refer-macros [defcard defcard-doc dom-node]]
            [cljs.reader :as r]
            [fulcro.client.impl.parser :as p]))

(defcard-doc "# Mutation exercises ")

; TODO: Exercise 1: Implement this mutation
(defmutation ex1-inc [params]
  (action [{:keys [state]}]
    (swap! state update :n inc)
    nil                                                     ; placeholder body
    ))

(defsc Ex1-Root [this {:keys [ui/react-key n]}]
  {:query [:ui/react-key :n]}
  (dom/div #js {:key react-key}
    (dom/hr nil)
    (dom/p nil "n: " n)
    (dom/button #js {:onClick #(prim/transact! this `[(ex1-inc {})])} "Increment")))

(defcard-fulcro mutation-exercise-1
  "
  ## Exercise 1 - Basic mutation

  Open the source for this file and look at Ex1-Root. Notice the transact that
  is calling `ex1-inc`. Look just above the `defsc` and find the `defmutation` for
  that mutation. Implement it so that the example works.
  "
  Ex1-Root
  {:n 1})

; TODO: Exercise 2: Implement this mutation
(defmutation ex2-inc [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :n] inc)
    nil                                                     ;  placeholder body
    ))


(defsc Ex2-Child [this {:keys [id n]}]
  {:query [:id :n]
   :ident [:child/by-id :id]}
  (dom/li nil
    (dom/span nil "n: " n)
    (dom/button #js {:onClick #(prim/transact! this `[(ex2-inc {:id ~id})])} "Increment")))

(def ui-ex2-child (prim/factory Ex2-Child {:keyfn :id}))

(defsc Ex2-Root [this {:keys [ui/react-key items]}]
  {:query [:ui/react-key {:items (prim/get-query Ex2-Child)}]}
  (dom/div #js {:key react-key}
    (dom/ul nil (map ui-ex2-child items))))

(defcard-fulcro mutation-exercise-2
  "## Exercise 2 - Mutation parameters

  Now move onto the source for Ex2-Root. Notice the transact is now on a child, and uses a
  different mutation that accepts parameters. Find the TODO marker and implement this mutation.

  Be sure to examine the app database content (shown at the bottom of this card and in the source).
  "
  Ex2-Root
  {:items       [[:child/by-id 1] [:child/by-id 2]]
   :child/by-id {1 {:id 1 :n 3}
                 2 {:id 2 :n 9}}}
  {:inspect-data true})

(defmutation ex3-dec [{:keys [id]}]
  (action [{:keys [state]}]
    (swap! state update-in [:child/by-id id :n] dec)) )

(defsc Ex3-Item [this {:keys [id n]}]
  {:query [:id :n]
   :ident [:child/by-id :id]}
  (dom/li nil
    (dom/span nil "n: " n)
    ; TODO: Fix the rendering using transaction follow-on property reads
    (dom/button #js {:onClick #(prim/transact! this `[(ex2-inc {:id ~id})])} "Increment")
    (dom/button #js {:onClick #(prim/transact! this `[(ex3-dec {:id ~id})])} "Decrement")))

(def ui-ex3-item (prim/factory Ex3-Item {:keyfn :id}))

(defsc Ex3-List [this {:keys [title min max items] :or {min 0 max 1000000}}]
  {:query [:id :title :max :min {[:items '_] (prim/get-query Ex3-Item)}]
   :ident [:list/by-id :id]}
  (dom/div #js {:style {:float "left" :width "300px"}}
    (dom/h4 nil (str title (when (= 0 min) (str " (n <= " max ")"))))
    (dom/ul nil (->> items
                  (filter (fn [{:keys [n]}] (<= min n max)))
                  (map ui-ex3-item)))))

(def ui-ex3-list (prim/factory Ex3-List {:keyfn :id}))

(defsc Ex3-Root [this {:keys [ui/react-key lists]}]
  {:query [:ui/react-key {:lists (prim/get-query Ex3-List)}]}
  (dom/div #js {:key react-key}
    (map ui-ex3-list lists)))

(defcard-fulcro mutation-exercise-3
  "## Exercise 3 - Mutations and rendering

  Using the same source and mutation from the prior example, try out this card (it won't work
  until you've completed the exercises above, and you may need to reload the browser
  between your code fixes on this one).

  Notice that we've made two lists that choose to display items based on their numeric value. The mutations
  are already hooked up, so you can start pressing buttons right away. You should notice that
  it doesn't quite work right. When the `n` of an item goes above 10 it should migrate to the Big list.

  The problem is that the element in the UI is changing state that some other part of the UI needs.

  Fulcro is perfectly happy to update elements with the same Ident when you transact on them, but it
  does not keep track of a dependency tree. Instead, it knows which components queried for what
  data.

  The help it needs is for you to describe what real data is changing as a result of the
  abstract mutation. In this case, anything that queries for `:items` should re-render.

  To indicate this, simply add follow-on reads for `:items`. Try it via the `transact!`, and
  (separately) via the `refresh` of the mutation.

  Fix the code and make sure it works by playing with the UI.

  Also, try changing the database min/max data so that the lists will have
  overlap and render some of the same items (and reload the page). Note that all of the
  rendering updates are working perfectly now.
  "
  Ex3-Root
  {:items       [[:child/by-id 1] [:child/by-id 2]]
   :lists       [[:list/by-id 1] [:list/by-id 2]]
   :list/by-id  {1 {:id 1 :title "Small" :max 10}
                 2 {:id 2 :title "Big" :min 11}}
   :child/by-id {1 {:id 1 :n 3}
                 2 {:id 2 :n 9}}}
  {:inspect-data true})

(defsc Ex4-Item [this {:keys [id n]} computed-callbacks] ; TODO: destructure the callbacks out of computed
  {:query [:id :n]
   :ident [:child/by-id :id]}
  (dom/li nil
    (dom/span nil "n: " n)
    ; TODO: MOVE THESE TO THE PARENT, and trigger callbacks (received from computed) from here instead
    (dom/button #js {:onClick #(prim/transact! this `[(ex2-inc {:id ~id})])} "Increment")
    (dom/button #js {:onClick #(prim/transact! this `[(ex3-dec {:id ~id})])} "Decrement")))

(def ui-ex4-item (prim/factory Ex4-Item {:keyfn :id}))

(defsc Ex4-List [this {:keys [title min max items] :or {min 0 max 1000000}} computed-callbacks] ; TODO: pass the callbacks through to the items
  {:query [:id :title :max :min {[:items '_] (prim/get-query Ex4-Item)}]
   :ident [:list/by-id :id]}
  (dom/div #js {:style {:float "left" :width "300px"}}
    (dom/h4 nil (str title (when (= 0 min) (str " (n <= " max ")"))))
    (dom/ul nil (->> items
                  (filter (fn [{:keys [n]}] (<= min n max)))
                  ; TODO: Pass through the callbacks. Remember to use computed!
                  (map ui-ex4-item)))))

(def ui-ex4-list (prim/factory Ex4-List {:keyfn :id}))

(defsc Ex4-Root [this {:keys [ui/react-key lists]}]
  {:query [:ui/react-key {:lists (prim/get-query Ex4-List)}]}
  ; TODO: Create the callbacks, and pass them down the tree. Remember to use computed!
  (dom/div #js {:key react-key}
    (map ui-ex4-list lists)))


(defcard-fulcro mutation-exercise-4
  "## Exercise 4 - Mutations and Rendering with Better Design

  In exercise 3 you could have added the `:items` to the `refresh` list of the mutation. This should have
  felt weird because that solution doesn't make a lot of sense from a design perspective. Why would counter mutations
  know that some other component in the system joined them in under the key `:items`? Totally weird and
  unnatural.

  If you remember from the chapter: the follow-on read to the `transact!` is also not ideal in this scenario.

  The better design approach is to have the child UI representation of the items delegate changes via callbacks to the
  parent. This is better for several reasons:

  1. The parent can augment the request for change with it's own mutations that should coincide with the child update.
  2. Fulcro refreshes the transacting *tree*, so the parent and child will both update properly.

  Rewrite the UI of this example to use callbacks and run the transactions in the parent.
  "
  Ex4-Root
  {:items       [[:child/by-id 1] [:child/by-id 2]]
   :lists       [[:list/by-id 1] [:list/by-id 2]]
   :list/by-id  {1 {:id 1 :title "Small" :max 10}
                 2 {:id 2 :title "Big" :min 11}}
   :child/by-id {1 {:id 1 :n 3}
                 2 {:id 2 :n 9}}}
  {:inspect-data true})
