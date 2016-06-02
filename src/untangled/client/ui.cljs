(ns untangled.client.ui
  (:require [om.next]
            [om.dom :as dom]
            [goog.dom :as gdom]))

(defn wrap-render [{:keys [file]} body]
  (set! (.-onkeypress js/document)
        (let [toggle (atom true)]
          (fn [e]
            (let [e (or e js/window.event)
                  code (.-which e)]
              (when (= 63 code) ;?-mark
                (let [style (if @toggle "" "display:none;")]
                  (swap! toggle not)
                  (doseq [ele (array-seq (gdom/getElementsByClass "source-viewers"))]
                    (gdom/setProperties ele #js {:style style}))))))))

  (dom/div nil
           (dom/a #js {:href (str "view-source:" file)
                       :className "source-viewers"
                       :style #js {:display "none"}}
                  "src")
           body))
