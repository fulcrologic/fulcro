(ns untangled.client.ui
  (:require
    [om.next :as om]
    [om.dom :as dom]
    [goog.object :as gobj]
    [goog.dom :as gdom]
    [goog.events :as gevt])
  (:import
    goog.events.EventType))

(defn highlight-element [{:keys [node-clone prev-on-click last-node]}]
  (fn [e]
    (let [possible-nodes (array-seq (js/document.elementsFromPoint (.-clientX e) (.-clientY e)))
          current-node (first (filter #(.getAttribute % "data-untangled-ui") possible-nodes))]
      (if current-node
        (when-not (or (#{js/document.documentElement js/document.body} current-node)
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
          (reset! last-node current-node))
        (do
          (when (.-classList @last-node)
            (when (.getAttribute @last-node "onclick")
              (reset! prev-on-click (.getAttribute @node-clone "onclick"))
              (.setAttribute @last-node "onclick" @prev-on-click))
            (.. @last-node -classList (remove "highlight-element")))
          (reset! node-clone #js {}))))))

(defn inspect-element [{:keys [node-clone select-file show-panel]}]
  (fn [evt]
    (when-let [meta-info (and (.-getAttribute @node-clone)
                           (.getAttribute @node-clone "data-untangled-ui"))]
      (js/console.log @node-clone)
      (select-file (cljs.reader/read-string meta-info))
      (show-panel "test data please ignore"))))

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

(def attached-listeners
  (do (or (gobj/get js/window "attached-listeners")
          (gobj/set js/window "attached-listeners" (atom #{})))
    (gobj/get js/window "attached-listeners")))

(defn install-listeners [& {:keys [select-file show-panel]}]
  (let [add-listener (fn [et f] (swap! attached-listeners conj (gevt/listen js/document et f)))
        remove-and-add-listener
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
             :install-listeners install-listeners
             :select-file (or select-file
                              (fn [{:keys [file line column]}]
                                (figwheel.client.heads-up/heads-up-event-dispatch
                                  #js {:figwheelEvent "file-selected"
                                       :fileName file
                                       :fileLine (str line)
                                       :fileColumn (str column)})))
             :show-panel (or show-panel
                             (fn [msg]
                               (figwheel.client.heads-up/display-heads-up
                                 {} (str (figwheel.client.heads-up/close-link) msg))))}]
    (remove-all-listeners)
    (add-listener EventType.MOUSEMOVE (highlight-element ctx))
    (add-listener EventType.CLICK (inspect-element ctx))
    (remove-and-add-listener EventType.KEYPRESS (toggle-devtools ctx))))

(defn wrap-render [meta-info {:keys [this class]} body]
  (dom/div #js {:data-untangled-ui meta-info} body))
