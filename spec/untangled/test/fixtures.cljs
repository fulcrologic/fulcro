(ns untangled.test.fixtures
  (:require
    [untangled.test.dom :refer [render-as-dom]]
    [om.next :as om :refer-macros [defui]]
    [om.dom :as dom]))


(defn evt-tracker-input [& {:keys [type prop]
                            :or   {type "text"
                                   prop (fn [evt] (.-keyCode evt))}}]
  (let [seqnc (atom [])
        handler (fn [evt] (swap! seqnc #(conj % (prop evt))))
        input (render-as-dom
                (dom/input #js {:type          type
                            :onClick       handler
                            :onDoubleClick handler
                            :onKeyDown     handler
                            :onKeyPress    handler
                            :onKeyUp       handler}))]
    [seqnc input]))




