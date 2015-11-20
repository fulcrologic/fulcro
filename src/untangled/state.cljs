(ns untangled.state)

(defn event-reason [evt]
  (let [e (some-> evt (.-nativeEvent))]
    (if (instance? js/Event e)
      (let [typ (.-type e)]
        (cond-> {:kind :browser-event :type typ}
                (= "input" typ) (merge {
                                        :react-id    (some-> (.-target e) (.-attributes) (.getNamedItem "data-reactid") (.-value))
                                        :input-value (some-> (.-target e) (.-value))
                                        })
                (= "click" typ) (merge {
                                        :x            (.-x e)
                                        :y            (.-y e)
                                        :client-x     (.-clientX e)
                                        :client-y     (.-clientY e)
                                        :screen-x     (.-screenX e)
                                        :screen-y     (.-screenY e)
                                        :alt          (.-altKey e)
                                        :ctrl         (.-ctrlKey e)
                                        :meta         (.-metaKey e)
                                        :shift        (.-shiftKey e)
                                        :mouse-button (.-button e)
                                        })))
      nil)))
