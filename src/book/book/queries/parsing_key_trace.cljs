(ns book.queries.parsing-key-trace
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [devcards.util.edn-renderer :refer [html-edn]]
            [cljs.reader :as r]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.dom :as dom]))

(defn tracer-path
  "The assoc-in path of a field on the ParsingTracer in app state."
  [field] [:widget/by-id :tracer field])

(defn tracing-reader
  "Creates a parser read handler that can record just the dispatch keys of a parse."
  [state-atom]
  (fn [env k params]
    (swap! state-atom update-in (tracer-path :trace) conj {:read-called-with-key k})))

(defmutation record-parsing-trace
  "Mutation: Run and record the trace of a query."
  [{:keys [query]}]
  (action [{:keys [state]}]
    (let [parser (prim/parser {:read (tracing-reader state)})] ; make a parser that records calls to read
      (try
        (swap! state assoc-in (tracer-path :trace) []) ; clear the last trace
        (swap! state assoc-in (tracer-path :error) nil) ; clear the last error
        (parser {} (r/read-string query)) ; Record the trace
        (catch js/Error e (swap! state assoc-in (tracer-path :error) e)))))) ; Record and exceptions

(defsc ParsingTracer [this {:keys [trace error query result]}]
  {:query         [:trace :error :query :result]
   :ident         (fn [] [:widget/by-id :tracer])
   :initial-state {:trace [] :error nil :query ""}}
  (dom/div nil
    (when error
      (dom/div nil (str error)))
    (dom/input #js {:type     "text"
                    :value    query
                    :onChange #(m/set-string! this :query :event %)})
    (dom/button #js {:onClick #(prim/transact! this `[(record-parsing-trace ~{:query query})])} "Run Parser")
    (dom/h4 nil "Parsing Trace")
    (html-edn trace)))

(def ui-tracer (prim/factory ParsingTracer))

(defsc Root [this {:keys [ui/tracer]}]
  {:query         [{:ui/tracer (prim/get-query ParsingTracer)}]
   :initial-state {:ui/tracer {}}}
  (ui-tracer tracer))
