(ns fulcro-devguide.queries.query-editing
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [cljs.reader :as r]
            [devcards.util.edn-renderer :refer [html-edn]]
            [goog.dom :as gdom]
            [goog.object :as gobj]
            [cljs.pprint :as pp :refer [pprint]]
            [cljsjs.codemirror]
            [cljsjs.codemirror.mode.clojure]
            [cljsjs.codemirror.addons.matchbrackets]
            [cljsjs.codemirror.addons.closebrackets]
            [fulcro.client.dom :as dom]))

(defn run-query [db q]
  (try
    (prim/db->tree (r/read-string q) db db)
    (catch js/Error e "Invalid Query")))

(def cm-opts
  #js {:fontSize          8
       :lineNumbers       true
       :matchBrackets     true
       :autoCloseBrackets true
       :indentWithTabs    false
       :mode              #js {:name "clojure"}})

(defn pprint-src
  "Pretty print src for CodeMirro editor.
  Could be included in textarea->cm"
  [s]
  (-> s
    r/read-string
    pprint
    with-out-str))


(defn textarea->cm
  "Decorate a textarea with a CodeMirror editor given an id and code as string."
  [id code]
  (let [ta (gdom/getElement id)]
    (js/CodeMirror
      #(.replaceChild (.-parentNode ta) % ta)
      (doto cm-opts
        (gobj/set "value" code)))))

(defui ^:once QueryEditor
  Object
  (componentDidMount [this]
    (let [{:keys [query id]} (prim/props this)
          src (pprint-src query)
          cm (textarea->cm id src)]
      (prim/update-state! this assoc :cm cm)))
  (render [this]
    (let [{:keys [id db query-result]} (prim/props this)
          local (prim/get-state this)
          state (prim/get-computed this :atom)]
      (dom/div nil
        (dom/h4 nil "Database")
        (html-edn db)
        (dom/hr nil)
        (dom/div #js {:key (str "editor-" id)}
          (dom/h4 nil "Query Editor")
          (dom/textarea #js {:id id})
          (dom/button #js {:onClick #(let [query (.getValue (:cm local))]
                                      (swap! state assoc :query-result (run-query db query)
                                        :query query))} "Run Query"))
        (dom/hr nil)
        (dom/div #js {:key (str "result-" id)}
          (dom/h4 nil "Query Result")
          (html-edn query-result))))))

(def ui-query-editor (prim/factory QueryEditor))

(def query-editor (fn [state _] (ui-query-editor (prim/computed @state {:atom state}))))
