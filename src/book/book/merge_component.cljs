(ns book.merge-component
  (:require [fulcro.client.primitives :as prim :refer [defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.data-fetch :as df]))

(defsc Counter [this {:keys [counter/id counter/n] :as props} {:keys [onClick] :as computed}]
  {:query [:counter/id :counter/n]
   :ident [:counter/by-id :counter/id]}
  (dom/div #js {:className "counter"}
    (dom/span #js {:className "counter-label"}
      (str "Current count for counter " id ":  "))
    (dom/span #js {:className "counter-value"} n)
    (dom/button #js {:onClick #(onClick id)} "Increment")))

(def ui-counter (prim/factory Counter {:keyfn :counter/id}))

; the * suffix is just a notation to indicate an implementation of something..in this case the guts of a mutation
(defn increment-counter*
  "Increment a counter with ID counter-id in a Fulcro database."
  [database counter-id]
  (update-in database [:counter/by-id counter-id :counter/n] inc))

(defmutation increment-counter [{:keys [id] :as params}]
  ; The local thing to do
  (action [{:keys [state] :as env}]
    (swap! state increment-counter* id))
  ; The remote thing to do. True means "the same (abstract) thing". False (or omitting it) means "nothing"
  (remote [env] true))

(defsc CounterPanel [this {:keys [counters]}]
  {:initial-state (fn [params] {:counters []})
   :query         [{:counters (prim/get-query Counter)}]
   :ident         (fn [] [:panels/by-kw :counter])}
  (let [click-callback (fn [id] (prim/transact! this
                                  `[(increment-counter {:id ~id}) :counter/by-id]))]
    (dom/div nil
      ; embedded style: kind of silly in a real app, but doable
      (dom/style nil ".counter { width: 400px; padding-bottom: 20px; }
                        button { margin-left: 10px; }")
      ; computed lets us pass calculated data to our component's 3rd argument. It has to be
      ; combined into a single argument or the factory would not be React-compatible (not would it be able to handle
      ; children).
      (map #(ui-counter (prim/computed % {:onClick click-callback})) counters))))

(def ui-counter-panel (prim/factory CounterPanel))

(defonce timer-id (atom 0))
(declare sample-of-counter-app-with-merge-component-fulcro-app)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The code of interest...
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-counter
  "NOTE: A function callable from anywhere as long as you have a reconciler..."
  [reconciler counter]
  (prim/merge-component! reconciler Counter counter
    :append [:panels/by-kw :counter :counters]))

(defsc Root [this {:keys [panel]}]
  {:query         [{:panel (prim/get-query CounterPanel)}]
   :initial-state {:panel {}}}
  (let [reconciler (prim/get-reconciler this)]              ; pretend we've got the reconciler saved somewhere...
    (dom/div #js {:style #js {:border "1px solid black"}}
      ; NOTE: A plain function...pretend this is happening outside of the UI...we're doing it here so we can embed it in the book...
      (dom/button #js {:onClick #(add-counter reconciler {:counter/id 4 :counter/n 22})} "Simulate Data Import")
      (dom/hr nil)
      "Counters:"
      (ui-counter-panel panel))))
