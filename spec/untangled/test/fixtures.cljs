(ns untangled.test.fixtures
  (:require-macros [untangled.component :as cm])
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
                (dom/input {:type          type
                            :onClick       handler
                            :onDoubleClick handler
                            :onKeyDown     handler
                            :onKeyPress    handler
                            :onKeyUp       handler}))]
    [seqnc input]))


(def sample-doc
  (render-as-dom
    (dom/div {}
             (dom/div {:key "myid"} "by-key")
             (dom/div {} "by-text-value"
                      (dom/span {} "other-text")
                      (dom/button {} (dom/em {} "Click Me")))
             (dom/div {:className "test-button"} "by-classname")
             (dom/span {:className "some-span"}
                       (dom/h3 {} "h3")
                       (dom/section {} "by-selector")
                       (dom/h1 {} "h1"))
             (dom/div {:data-foo "test-foo-data"} "by-attribute")
             (dom/div {:className "bartok"} "wrong-multiple-classes")
             (dom/div {:className "foo bar bah"} "with-multiple-classes"))))


(defui Button
       Object
       (render [data]
               (let [store-last-event (fn [evt input] (assoc input :last-event evt))]
                 (dom/button {:onClick    (fn [evt] "FIXME")
                              :className  "test-button"
                              :last-event (:last-event data)}))))

(def button (om/factory Button))
(def root-obj (atom {}))
(def custom-button (render-as-dom (button :my-button root-obj)))
