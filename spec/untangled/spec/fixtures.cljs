(ns untangled.spec.fixtures
  (:require-macros [untangled.component :as cm])
  (:require
    [quiescent.core :include-macros true]
    [untangled.state :as state]
    [untangled.test.dom :refer [render-as-dom]]
    [untangled.core :as core]
    [untangled.component :as c]))


(defn evt-tracker-input [& {:keys [type prop]
                            :or   {type "text"
                                   prop (fn [evt] (.-keyCode evt))}}]
  (let [seqnc (atom [])
        handler (fn [evt] (swap! seqnc #(conj % (prop evt))))
        input (render-as-dom
                (c/input {:type          type
                          :onClick       handler
                          :onDoubleClick handler
                          :onKeyDown     handler
                          :onKeyPress    handler
                          :onKeyUp       handler}))]
    [seqnc input]))


(def sample-doc
  (render-as-dom
    (c/div {}
           (c/div {:key "myid"} "by-key")
           (c/div {} "by-text-value"
                  (c/span {} "other-text")
                  (c/button {} (c/em {} "Click Me")))
           (c/div {:className "test-button"} "by-classname")
           (c/span {:className "some-span"}
                   (c/h3 {} "h3")
                   (c/section {} "by-selector")
                   (c/h1 {} "h1"))
           (c/div {:data-foo "test-foo-data"} "by-attribute")
           (c/div {:className "bartok"} "wrong-multiple-classes")
           (c/div {:className "foo bar bah"} "with-multiple-classes"))))


(cm/defscomponent Button
                  "A button"
                  [data context]

                  (let [op (state/op-builder context)
                        store-last-event (fn [evt input]
                                           (assoc input :last-event evt))]

                    (c/button {:onClick    (fn [evt] ((op (partial store-last-event (clj->js evt)))))
                               :className  "test-button"
                               :last-event (:last-event data)})))

(def root-obj (state/root-context (core/new-application nil {:my-button {}})))
(def custom-button (render-as-dom (Button :my-button root-obj)))