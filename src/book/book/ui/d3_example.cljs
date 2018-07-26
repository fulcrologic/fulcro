(ns book.ui.d3-example
  (:require [fulcro.client.dom :as dom]
            cljsjs.d3
            [goog.object :as gobj]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.primitives :as prim :refer [defsc]]))

(defn render-squares [dom-node props]
  (let [svg       (-> js/d3 (.select dom-node))
        data      (clj->js (:squares props))
        selection (-> svg
                    (.selectAll "rect")
                    (.data data (fn [d] (.-id d))))]
    (-> selection
      .enter
      (.append "rect")
      (.style "fill" (fn [d] (.-color d)))
      (.attr "x" "0")
      (.attr "y" "0")
      .transition
      (.attr "x" (fn [d] (.-x d)))
      (.attr "y" (fn [d] (.-y d)))
      (.attr "width" (fn [d] (.-size d)))
      (.attr "height" (fn [d] (.-size d))))
    (-> selection
      .exit
      .transition
      (.style "opacity" "0")
      .remove)
    false))

(defsc D3Thing [this props]
  {:componentDidMount     (fn []
                            (when-let [dom-node (gobj/get this "svg")]
                              (render-squares dom-node (prim/props this))))
   :shouldComponentUpdate (fn [next-props next-state]
                            (when-let [dom-node (gobj/get this "svg")]
                              (render-squares dom-node next-props))
                            false)}
  (dom/svg {:style   {:backgroundColor "rgb(240,240,240)"}
            :width   200 :height 200
            :ref     (fn [r] (gobj/set this "svg" r))
            :viewBox "0 0 1000 1000"}))

(def d3-thing (prim/factory D3Thing))

(defn random-square []
  {
   :id    (rand-int 10000000)
   :x     (rand-int 900)
   :y     (rand-int 900)
   :size  (+ 50 (rand-int 300))
   :color (case (rand-int 5)
            0 "yellow"
            1 "green"
            2 "orange"
            3 "blue"
            4 "black")})

(defmutation add-square [params]
  (action [{:keys [state]}]
    (swap! state update :squares conj (random-square))))

(defmutation clear-squares [params]
  (action [{:keys [state]}]
    (swap! state assoc :squares [])))

(defsc Root [this props]
  {:query         [:squares]
   :initial-state {:squares []}}
  (dom/div
    (dom/button {:onClick #(prim/transact! this
                             `[(add-square {})])} "Add Random Square")
    (dom/button {:onClick #(prim/transact! this
                             `[(clear-squares {})])} "Clear")
    (dom/br)
    (dom/br)
    (d3-thing props)))

