(ns untangled.client.ui
  (:require
    [cljs.js :as cljs]
    [om.next :as om]
    [om.dom :as dom]
    [goog.object :as gobj]
    [goog.dom :as gdom]
    [goog.events :as gevt]
    [clojure.string :as str]
    hiccups.runtime)
  (:require-macros
    [hiccups.core :refer [html]])
  (:import
    goog.events.EventType))

(defn highlight-element [{:keys [hl-class node-clone prev-on-click last-node]}]
  (fn [e]
    (let [possible-nodes (array-seq (js/document.elementsFromPoint (.-clientX e) (.-clientY e)))
          current-node (first (filter #(.getAttribute % "data-untangled-ui") possible-nodes))]
      (when (and (not= current-node @last-node)
              (.-classList @last-node)
              (.contains (.-classList @last-node) hl-class))
        (.. @last-node -classList (remove hl-class))
        (reset! last-node #js {}))
      (if-not current-node (reset! node-clone #js {})
        (when-not (or (#{js/document.documentElement js/document.body} current-node)
                      (= current-node @last-node))
          (reset! node-clone (.cloneNode current-node))
          (.. current-node -classList (add hl-class))
          (reset! last-node current-node))))))

(defn inspect-element [{:keys [node-clone select-file!]}]
  (fn [evt]
    (when-let [meta-info (and (.-getAttribute @node-clone)
                           (.getAttribute @node-clone "data-untangled-ui"))]
      (js/console.log @node-clone)
      (select-file! (cljs.reader/read-string meta-info)))))

(defn node [html-str]
  (let [div (js/document.createElement "div")]
    (set! (.-innerHTML div) html-str)
    (.-firstChild div)))

(defn ensure-panel []
  (let [container-id "untangled-client-ui-dev-panel"
        content-id "untangled-client-ui-dev-panel-content-area"]
    (if-not (js/document.querySelector (str "#" container-id))
      (let [panel
            (html
              [:div {:id container-id :class "dev-panel-bottom"}
               [:div {:id content-id :class "tabbed"}
                [:input {:name "tabbed" :id "repl-tab" :type "radio" :checked true}]
                [:section {}
                 [:h1 [:label {:for "repl-tab"} "REPL"]]
                 [:div {} "user=>"
                  [:input {:id "repl"}]
                  [:button {:id "repl-submit"} "SUBMIT"]]]
                [:input {:name "tabbed" :id "appstate-tab" :type "radio"}]
                [:section {}
                 [:h1 [:label {:for "appstate-tab"} "appstate"]]
                 [:div {} "appstate=>"]]]]) ]
        (-> (.-body js/document)
          (.appendChild (node panel)))
        (goog.events.listen (js/document.querySelector "#repl-submit")
          goog.events.EventType.CLICK
          (fn [_]
            (cljs/eval-str (cljs/empty-state)
              (.-value (js/document.querySelector "input#repl"))
              "" {:eval cljs/js-eval} identity))))
      {:container-el    (js/document.querySelector (str "#" container-id))
       :content-area-el (js/document.querySelector (str "#" content-id))})))

(defn show-dev-panel! []
  (ensure-panel))
(defn hide-dev-panel! []
  (.remove (:container-el (ensure-panel))))

(defn toggle-devtools [{:keys [hl-class last-node node-clone prev-on-click
                               removed-attached-listeners! reset-listeners!]}]
  (fn [e]
    (let [code (.-charCode e), ?-mark 63]
      (when (= ?-mark code)
        (if-not (gobj/get js/window "untangled-ui-enabled")
          (do (gobj/set js/window "untangled-ui-enabled" true)
            (show-dev-panel!)
            (reset-listeners!))
          (do
            (gobj/set js/window "untangled-ui-enabled" false)
            (hide-dev-panel!)
            (when (and (.-classList @last-node)
                    (.contains (.-classList @last-node) hl-class))
              (.. @last-node -classList (remove hl-class)))
            (removed-attached-listeners!)))))))

(def attached-listeners
  (do (or (gobj/get js/window "attached-listeners")
          (gobj/set js/window "attached-listeners" (atom #{})))
    (gobj/get js/window "attached-listeners")))

(defn install-listeners! [& {:keys [select-file hl-class]}]
  (let [add-listener! (fn [et f] (swap! attached-listeners conj (gevt/listen js/document et f)))
        swap-listener!
        (fn [et f]
          (gevt/unlistenByKey (gobj/get js/window "keypress-listener"))
          (gobj/set js/window "keypress-listener"
                    (gevt/listen js/document et f)))
        removed-attached-listeners!
        (fn [] (doseq [k @attached-listeners] (gevt/unlistenByKey k))
          (reset! attached-listeners #{}))
        ctx {:hl-class (or hl-class "highlight-element")
             :last-node (atom #js {})
             :node-clone (atom #js {})
             :prev-on-click (atom #js {})
             :removed-attached-listeners! removed-attached-listeners!
             :reset-listeners! install-listeners!
             :select-file! (or select-file
                               (fn [{:keys [file line column]}]
                                 (figwheel.client.heads-up/heads-up-event-dispatch
                                   #js {:figwheelEvent "file-selected"
                                        :fileName file
                                        :fileLine (str line)
                                        :fileColumn (str column)})))}]
    (when-not (gobj/get js/window "untangled-ui-enabled")
      (removed-attached-listeners!))
    (when (gobj/get js/window "untangled-ui-enabled")
      (add-listener! EventType.MOUSEMOVE (highlight-element ctx))
      (add-listener! EventType.CLICK (inspect-element ctx)))
    (swap-listener! EventType.KEYPRESS (toggle-devtools ctx))))

(defn wrap-render [meta-info {:keys [this class]} body]
  (dom/div #js {:data-untangled-ui meta-info} body))
