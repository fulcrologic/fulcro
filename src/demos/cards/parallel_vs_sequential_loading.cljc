(ns cards.parallel-vs-sequential-loading
  (:require
    [cards.card-utils :refer [sleep now]]
    #?@(:cljs [yahoo.intl-messageformat-with-locales
               [devcards.core :as dc :include-macros true]
               [fulcro.client.cards :refer [defcard-fulcro]]])
    [fulcro.server :refer [defquery-root defquery-entity defmutation]]
    [fulcro.client.logging :as log]
    [fulcro.client.core :as fc]
    [fulcro.i18n :refer [tr trf]]
    [fulcro.client.data-fetch :as df]
    [fulcro.client.dom :as dom]
    [fulcro.client.primitives :as prim :refer [defui defsc]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERVER:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defquery-entity :background.child/by-id
  (value [{:keys [parser query] :as env} id params]
    (when (= query [:background/long-query])
      (parser env query))))

(defquery-root :background/long-query
  (value [{:keys [ast query] :as env} params]
    (log/info "Long query started")
    (sleep 5000)
    (log/info "Long query finished")
    42))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CLIENT:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn render-result [v] (dom/span nil v))

(defui ^:once Child
  static prim/IQuery
  (query [this] [:id :name :background/long-query])
  static prim/Ident
  (ident [this props] [:background.child/by-id (:id props)])
  Object
  (render [this] (let [{:keys [name name background/long-query]} (prim/props this)]
                   (dom/div #js {:style #js {:display "inline" :float "left" :width "200px"}}
                     (dom/button #js {:onClick #(df/load-field this :background/long-query :parallel true)} "Load stuff parallel")
                     (dom/button #js {:onClick #(df/load-field this :background/long-query)} "Load stuff sequential")
                     (dom/div nil
                       name
                       (df/lazily-loaded render-result long-query))))))

(def ui-child (prim/factory Child {:keyfn :id}))

(defui ^:once Root
  static prim/InitialAppState
  (initial-state [c params] {:children [{:id 1 :name "A"} {:id 2 :name "B"} {:id 3 :name "C"}]})
  static prim/IQuery
  (query [this] [:ui/react-key {:children (prim/get-query Child)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key children] :or {ui/react-key "ROOT"} :as props} (prim/props this)]
      (dom/div #js {:key react-key}
        (mapv ui-child children)
        (dom/br #js {:style #js {:clear "both"}}) (dom/br nil)))))

#?(:cljs
   (dc/defcard-doc
     "# Background Loads

     This is a full-stack example. See the Introduction for how to run the demo server.

     Note that all of the examples share the same server, but the server code is isolated for each using
     namespacing of the queries and mutations.

     This is a simple application that shows off the difference between regular loads and those marked parallel.

     Normally, Fulcro runs separate event-based loads in sequence, ensuring that your reasoning can be synchronous;
     however, for loads that might take some time to complete, and for which you can guarantee order of
     completion doesn't matter, you can specify an option on load (`:parallel true`) that allows them to proceed in parallel.

     The buttons in the card below come from this UI component:
     "
     (dc/mkdn-pprint-source Child)
     "
     and you can see how they trigger the same load."))

#?(:cljs
   (defcard-fulcro background-loads
     "# Background Loads

      The server has a built-in delay of 5 seconds. Pressing the sequential buttons on the three (in any order) will take
      at least 15 seconds to complete from the time you click the first one (since each will run after the other is complete).
      If you rapidly click the parallel buttons, then the loads will not be sequenced, and you will see them complete in roughly
      5 seconds overall (from the time you click the last one).
     "
     Root))