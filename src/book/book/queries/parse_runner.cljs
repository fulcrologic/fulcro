(ns book.queries.parse-runner
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [devcards.util.edn-renderer :refer [html-edn]]
            [cljs.reader :as r]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.dom :as dom]))

(defn parse-runner-path
  ([] [:widget/by-id :parse-runner])
  ([field] [:widget/by-id :parse-runner field]))

(defmutation run-query [{:keys [query database parser]}]
  (action [{:keys [state]}]
    (try
      (let [query  (r/read-string query)
            result (parser {:state (atom database)} query)]
        (swap! state update-in (parse-runner-path) assoc
          :error ""
          :result result))
      (catch js/Error e (swap! state assoc-in (parse-runner-path :error) e)))))

(defsc ParseRunner [this {:keys [ui/query error result]} {:keys [parser database]}]
  {:query         [:ui/query :error :result]
   :initial-state (fn [{:keys [query] :or {query ""}}] {:ui/query query :error "" :result {}})
   :ident         (fn [] [:widget/by-id :parse-runner])}
  (dom/div
    (dom/input {:type     "text"
                :value    query
                :onChange (fn [evt] (m/set-string! this :ui/query :event evt))})
    (dom/button {:onClick #(prim/transact! this `[(run-query ~{:query query :database database :parser parser})])} "Run Parser")
    (when error
      (dom/div (str error)))
    (dom/div
      (dom/h4 "Query Result")
      (html-edn result))
    (dom/div
      (dom/h4 "Database")
      (html-edn database))))

(def ui-parse-runner (prim/factory ParseRunner))


