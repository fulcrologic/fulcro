(ns untangled.client.ui
  (:require
    [om.next :as om]
    [om.dom :as dom]
    [goog.object :as gobj]
    [goog.dom :as gdom]
    [goog.events :as gevt])
  (:import
    goog.events.EventType))

(def attached-listeners
  (do (or (gobj/get js/window "attached-listeners")
          (gobj/set js/window "attached-listeners" (atom #{})))
    (gobj/get js/window "attached-listeners")))

(defn highlight-element [{:keys [node-clone prev-on-click last-node]}]
  (fn [e]
    (let [possible-nodes (array-seq (js/document.elementsFromPoint (.-clientX e) (.-clientY e)))
          current-node (first (filter #(.getAttribute % "data-untangled-ui") possible-nodes))]
      (when-not (or (not current-node)
                    (#{js/document.documentElement js/document.body} current-node)
                    (= current-node @last-node))
        (when (.-classList @last-node)
          (when (.getAttribute @last-node "onclick")
            (reset! prev-on-click (.getAttribute @node-clone "onclick"))
            (.setAttribute @last-node "onclick" @prev-on-click))
          (.. @last-node -classList (remove "highlight-element")))
        (reset! node-clone (.cloneNode current-node))
        (when (.getAttribute current-node "onclick")
          (.setAttribute current-node "onclick" (constantly false)))
        (.. current-node -classList (add "highlight-element"))
        (reset! last-node current-node)))))

(defn inspect-element [{:keys [node-clone]}]
  (fn [evt]
    ;(let [e (.getBrowserEvent evt)]
    ;  (.preventDefault e)
    ;  (.stopPropagation e)
    ;  (.stopImmediatePropagation e))
    (js/console.log @node-clone)))

(defn toggle-devtools [{:keys [last-node node-clone prev-on-click remove-all-listeners install-listeners]}]
  (fn [e]
    (let [code (.-keyCode e), ?-mark 63]
      (when (= ?-mark code)
        (if-not (gobj/get js/window "untangled-ui-enabled")
          (do (gobj/set js/window "untangled-ui-enabled" true)
            (install-listeners))
          (do
            (gobj/set js/window "untangled-ui-enabled" false)
            (when (.-classList @last-node)
              (when (.getAttribute @last-node "onclick")
                (reset! prev-on-click (.getAttribute @node-clone "onclick"))
                (.setAttribute @last-node "onclick" @prev-on-click))
              (.. @last-node -classList (remove "highlight-element")))
            (remove-all-listeners)))))))

(defn install-listeners []
  (let [add-listener (fn [et f] (swap! attached-listeners conj (gevt/listen js/document et f)))
        add-and-remove-listener
        (fn [et f]
          (gevt/unlistenByKey (gobj/get js/window "keypress-listener"))
          (gobj/set js/window "keypress-listener"
                    (gevt/listen js/document et f)))
        remove-all-listeners
        (fn [] (doseq [k @attached-listeners] (gevt/unlistenByKey k))
          (reset! attached-listeners #{}))
        ctx {:last-node (atom #js {})
             :node-clone (atom #js {})
             :prev-on-click (atom #js {})
             :remove-all-listeners remove-all-listeners
             :install-listeners install-listeners}]
    (remove-all-listeners)
    (add-listener EventType.MOUSEMOVE (highlight-element ctx))
    (add-listener EventType.CLICK (inspect-element ctx))
    (add-and-remove-listener EventType.KEYPRESS (toggle-devtools ctx))))

(defn wrap-render [meta-info {:keys [this class]} body]
  (dom/div #js {:data-untangled-ui meta-info}
    body))
