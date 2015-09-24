(ns untangled.state
  (:require [untangled.events :as evt]
            cljs.pprint
            [clojure.set :refer [union]]
            [untangled.logging :as logging]
            [untangled.application :as app]
            [untangled.history :as h]))

(defn root-context
  "Create a root context for a given Untangled Application (see untangled.core/new-application)."
  [app]
  {
   ::application     app
   ::scope           []
   ::event-listeners []
   ::to-publish      {}
   }
  )

(defn- find-first [pred coll] (first (filter pred coll)))

(defn checked-index [items index id-keyword value]
  (let [index-valid? (> (count items) index)
        proposed-item (if index-valid? (get items index) nil)
        ]
    (cond (and proposed-item
               (= value (get proposed-item id-keyword))) index
          :otherwise (->> (map-indexed vector items) (find-first #(= value (id-keyword (second %)))) (first))
          )
    )
  )

(defn resolve-data-path [state path-seq]
  (reduce (fn [real-path path-ele]
            (if (sequential? path-ele)
              (do
                (if (not= 4 (count path-ele))
                  (logging/log "ERROR: VECTOR BASED DATA ACCESS MUST HAVE A 4-TUPLE KEY")
                  (let [vector-key (first path-ele)
                        state-vector (get-in state (conj real-path vector-key))
                        lookup-function (second path-ele)
                        target-value (nth path-ele 2)
                        proposed-index (nth path-ele 3)
                        index (checked-index state-vector proposed-index lookup-function target-value)
                        ]
                    (if index
                      (conj real-path vector-key index)
                      (do
                        (logging/log "ERROR: NO ITEM FOUND AT DATA PATH")
                        (cljs.pprint/pprint path-seq)
                        real-path
                        )
                      )
                    )))
              (conj real-path path-ele)
              )
            )
          [] path-seq))

(defn data-path
  "Used by internal context tracking to correct the internal path to account for the inclusion of vectors as data structures
  pointing to items in vectors."
  [context]
  (let [state @(-> context ::application :app-state)
        path-seq (::scope context)
        ]
    (resolve-data-path state path-seq)
    ))

(defn parent-data
  "Find data with the given key recursively in parent(s) of the given context.
  Searches up in the context scopes until it finds data for the given key.
  Returns nil if no such data can be found"
  [context key]
  (let [state-atom (-> context ::application :app-state)]
    (loop [parent-scope (vec (butlast (::scope context)))]
      (let [path (conj (resolve-data-path @state-atom parent-scope) key)
            value (get-in @state-atom path)]
        (cond
          (<= (count path) 1) nil
          value value
          :else (recur (vec (butlast parent-scope)))
          ))))
  )

(defn context-data
  "Extract the data for the component indicated by the given context. If the context indicates there is published
  state from a parent, then that published state will be included in the data."
  [context]
  (let [state-atom (-> context ::application :app-state)
        path (data-path context)
        to-copy (-> context ::to-publish)
        ]
    (cond->> (get-in @state-atom path)
             (not-empty to-copy) (merge to-copy)
             )))

(defn get-application "Retrieve the top-level application for any given context" [context] (::application context))

(defn update-in-context
  "Update the application state by applying the given operation to the state of the component implied by
  the given context. Think of this as a 'targeted' `swap!` where you don't have to know where the data is
  stored. This function also records the change in this state history with an optional associated Reason."
  [context operation undoable compressable reason]
  (let [application (-> context ::application)
        state-atom (:app-state application)
        history-atom (:history application)
        path (data-path context)
        old-state @state-atom
        history-entry (h/set-reason (h/new-point-in-time old-state undoable compressable) reason)]
    (swap! history-atom #(h/record % history-entry))
    (swap! state-atom (fn [old-state]
                        (-> old-state
                            (assoc :time (h/now))
                            (update-in path operation))))
    (app/state-changed application old-state @state-atom)
    ))

(defn dbg [v] (cljs.pprint/pprint v) v)

(defn new-sub-context
  "Create a new context (scope) which represents a child's context. Also installs the given handler map as a list of
  event handlers the child can trigger on the parent.
  
  A new sub-context may also include data from the parent context. In this case, pass a set of attributes to publish as 
  the last argument, and that state will be copied into the publish list of the new context."
  ([context id handler-map]
   (cond-> (assoc context ::scope (conj (::scope context) id))
           handler-map (assoc ::event-listeners (concat (::event-listeners context) handler-map))
           ))
  ([context id handler-map child-publish-set]
   (let [data (get (context-data context) id)
         published-data (reduce (fn [acc i] (assoc acc i (get data i))) {} child-publish-set)]
     (cond-> (new-sub-context context id handler-map)
             child-publish-set (update ::to-publish (partial merge published-data))
             )))
  )

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

(defn context-operator
  "Create a function that, when called, updates the app state localized to the given context. Think of this as a
  constructor for targeted `swap!` functions.

  Parameters:
  `context`: the target rendering context
  `operation`: A 1-arg function that takes the data at the context's path, and returns an updated version

  Optional named parameters:

  - `:undoable boolean`: Indicate that this state change is (or is not) undoable. Defaults to true.
  - `:compress boolean`: Indicate that this state change can be compressed (keeping only the most recent of adjacent
    compressable items in the state history). Defaults to false.
  - `:trigger evt-kw`: Sets user-defined event to be triggered (in the parent) when the
  *generated* operation runs. E.g. `:trigger :deleted`.  May be a list or single keyword.
  - `:reason Reason`: When the *generated* operation runs, causes resulting application state change in history to
   include the stated (default) reason. The reason can be overridden by passing a :reason named parameter to the
   generated function.

  The trigger parameter indicates that the resulting function will trigger those abstract events on the parent.

  Examples:

  let [set-today (context-operator context set-to-today :trigger [:date-picked] :reason (Reason. \"Set date\"))]
  ...
      (d/button { :onClick set-today } \"Today\")
      (d/button { :onClick (fn [] (set-today :reason \"Clicked 'Today'\")) } \"Today\"))
  "
  [context operation & {:keys [trigger reason undoable compress] :or {trigger false undoable true compress false}}]
  (fn [& args]
    (let [evt-reason (event-reason (first args))
          {:keys [reason trigger event] :or {reason reason trigger trigger event nil}} (drop-while #(not (keyword? %)) args)
          reason (if reason reason evt-reason)
          ]
      (if trigger (evt/trigger context trigger))
      (update-in-context context operation undoable compress reason)))
  )

(defn op-builder
  "Exactly equivalent to (partial context-operator context). See context-operator for details."
  [context]
  (partial context-operator context))

(defn list-element-id
  "Construct a proper sub-element ID for a list in a component's state.

  Parameters:
  - `current-component-data` The full data of the component being rendered
  - `subcomponent-id` The keyword used to find the sub-list (which must be a vector) in the current component's state.
  - `subelement-keyword` The keyword used within the list **items** that uniquely identifies that item. MUST exist and not change over time.
  - `desired sub-element` An instance of an item (the entire item, not it's key) from the sublist

  Returns an Untangled ID that uniquely identifies the supplied item for the rendering system.

  For example, in the state:

       (def a { :k 1 :v 1 })
       (def b { :k 1 :v 2 })

       ...
       :state {
            :list [ a b ]
            }

  in the renderer:

       (defscomponent Thing [data context]
         (let [element-id (partial list-element-id data :state :k)]
            (ul {}
               (map
                  (fn [item] (SubComponent (element-id item) context))
                  (:items data))
             )))
  "
  [current-component-data subcomponent-id subelement-keyword index]
  (let [subelement (get-in current-component-data [subcomponent-id index])
        subelement-key (get subelement subelement-keyword)
        ]
    (assert subelement-key (str "No value for key named " subelement-keyword " found in target object. Cannot create a UI list ID for it!"))
    [subcomponent-id subelement-keyword subelement-key index]))

(defn transact!
  [context f & options]
  (let [op (op-builder context)]
    ((apply op f options))
    )
  )
