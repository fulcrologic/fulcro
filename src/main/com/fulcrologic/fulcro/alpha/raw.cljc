(ns com.fulcrologic.fulcro.alpha.raw
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.raw.application :as app]
    [com.fulcrologic.fulcro.raw.components :as comp]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [taoensso.timbre :as log]))

(defn fulcro-app
  "Just like application/fulcro-app, but turns off internal rendering, and starts inspect client (if in dev mode)"
  [options]
  (let [app (app/fulcro-app (merge {:optimized-render! nil} options))]
    (inspect/app-started! app)
    app))

(defn add-uism!
  "Add a UISM to Fulcro. This will set up the given state machine under the given ID, and start it (if not
   already started). Your initial state handler MUST set up actors and otherwise initialize based on initial-event-data.

   If the machine is already started at the given ID then this will send it an `:event/remounted` event.

   You MUST include `:componentName` in each of your actor's normalizing component options (e.g. `(nc query {:componentName ::uniqueName})`)
   because UISM requires component appear in the component registry (components cannot be safely stored in app state, just their
   names).

   Calls `receive-props` with a map that contains the actor props (by actor name) and the current state of the state machine as `:active-state`."
  [app {:keys [state-machine-definition id receive-props initial-event-data]}]
  (app/add-render-listener! app id
    (fn []
      (let [state-map (app/current-state app)
            {::uism/keys [active-state actor->ident actor->component-name]} (get-in state-map [::uism/asm-id id])
            props     (reduce-kv
                        (fn [result actor ident]
                          (let [cname (actor->component-name actor)
                                cls   (comp/registry-key->class cname)
                                query (comp/get-query cls)
                                base  (get-in state-map ident)]
                            (when-not cls
                              (log/error "You forgot to give actor" actor "a :componentName"))
                            (assoc result actor (fdn/db->tree query base state-map))))
                        {:active-state active-state}
                        actor->ident)]
        (receive-props props))))
  (let [s        (app/current-state app)
        started? (get-in s [::uism/asm-id id])]
    (if started?
      (uism/trigger!! app id :event/remounted)
      (uism/begin! app state-machine-definition id initial-event-data))))

(defn remove-uism! [app id]
  (app/remove-render-listener! app id)
  (swap! (::app/state-atom app) update ::uism/asm-id dissoc id))

(defn add-root!
  "Use a root key and component as a subtree managed by Fulcro. The `root-key` must be a unique
   (namespace recommended) key among all keys used within the application, since the root of the database is where it
   will live.

   The `component` should be a real Fulcro component or a generated normalizing component from `nc` (or similar).

   Calls `receive-props` with the props (not including `root-key`) that satisfy the query of `component`.
  "
  [app root-key component {:keys [receive-props initialize? initial-params]}]
  (fn []
    (when (and initialize? (not (contains? (app/current-state app) root-key)))
      (swap! (::app/state-atom app) (fn use-root-merge* [s]
                                      (merge/merge-component s component
                                        (comp/get-initial-state component (or initial-params {}))
                                        :replace [root-key]))))
    (let [get-props (fn use-root-get-props* []
                      (let [query     [{root-key (comp/get-query component)}]
                            state-map (app/current-state app)]
                        (fdn/db->tree query state-map state-map)))]
      (receive-props (get-props))
      (app/add-render-listener! app root-key (fn use-root-render-listener* [app _]
                                               (receive-props (get-props)))))))

(defn remove-root!
  "Remove a root key managed subtree from Fulcro"
  [app root-key]
  (app/remove-render-listener! app root-key)
  (swap! (::app/state-atom app) dissoc root-key))

(defn id-key
  "Returns the keyword of the most likely ID attribute in the given props (the first one with the `name` \"id\").
  Returns nil if there isn't one."
  [props]
  (first (filter #(= "id" (name %)) (keys props))))

(defn set-value!!
  "Run a transaction that will update the given k/v pair in the props of the database. Uses the `current-props` to
   derive the ident of the database entry. The props must contain an ID key that can be used to derive the ident from
   the current-props."
  [app current-props k v]
  (let [ik    (id-key current-props)
        ident [ik (get current-props ik)]]
    (if (some nil? ident)
      (log/error "Cannot set-value!! because current-props could not be used to derive the ident of the component." current-props)
      (do
        (comp/transact!! app `[(m/set-props ~{k v})] {:ref ident})))))

(defn update-value!!
  "Run a transaction that will update the given k/v pair in the props of the database. Uses the `current-props` as the basis
   for the update, and to find the ident of the target. The `current-props` must contain an ID field that can be used to derive
   the ident from the passed props."
  [app current-props k f & args]
  (let [ik        (id-key current-props)
        ident     [ik (get current-props ik)]
        old-value (get current-props k)
        new-value (apply f old-value args)]
    (if (some nil? ident)
      (log/error "Cannot update-value!! because current-props could not be used to derive the ident of the component." current-props)
      (do
        (comp/transact!! app `[(m/set-props ~{k new-value})] {:ref ident})))))


