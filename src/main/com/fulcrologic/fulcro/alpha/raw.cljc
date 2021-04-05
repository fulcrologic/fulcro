(ns com.fulcrologic.fulcro.alpha.raw
  #?(:cljs (:require-macros [com.fulcrologic.fulcro.alpha.raw :refer [with-fulcro]]))
  (:require
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.inspect.inspect-client :as inspect]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [edn-query-language.core :as eql]
    [taoensso.timbre :as log]))

(defn fulcro-app
  "Just like application/fulcro-app, but turns off internal rendering, and starts inspect client (if in dev mode)"
  [options]
  (let [app (app/fulcro-app (merge {:optimized-render! nil} options))]
    (inspect/app-started! app)
    app))

(defn- ast-id-key [children]
  (:key
    (first
      (filter (fn [{:keys [type key]}]
                (and
                  (keyword? key)
                  (= :prop type)
                  (= "id" (name key))))
        children))))

(defn- normalize* [{:keys [children] :as original-node} {:keys [componentName] :as top-component-options}]
  (let [detected-id-key (ast-id-key children)
        real-id-key     (or detected-id-key)
        component       (fn [& args])
        new-children    (mapv
                          (fn [{:keys [type] :as node}]
                            (if (and (= type :join) (not (:component node)))
                              (normalize* node {})
                              node))
                          children)
        updated-node    (assoc original-node :children new-children :component component)
        query           (if (= type :join)
                          (eql/ast->query (assoc updated-node :type :root))
                          (eql/ast->query updated-node))
        _               (comp/add-hook-options! component (cond-> (with-meta
                                                                    (merge
                                                                      {:initial-state (fn [& args] {})}
                                                                      top-component-options
                                                                      {:query  (fn [& args] query)
                                                                       "props" {"fulcro$queryid" :anonymous}})
                                                                    {:query-id :anonymous})
                                                            componentName (assoc :componentName componentName)
                                                            real-id-key (assoc :ident (fn [_ props] [real-id-key (get props real-id-key)]))))]
    updated-node))

(defn nc
  "Create an anonymous normalizing query component. By default the normalization will be auto-detected based on there being a prop at each
   entity level that has (any) namespace, but with the name `id`. For example:

   ```
   [:list/id :list/name {:list/items [:item/id :item/complete? :item/label]}]
   ```

   will create a normalizing query that expects the top-level values to be normalized by `:list/id` and the nested
   items to be normalized by `:item/id`. If there is more than one ID in your props, make sure the *first* one is
   the one to use for normalization.

   The `top-component-options` becomes the options map of the component.

   You can include :componentName to push the resulting anonymous component definition into the component registry, which
   is needed by some parts of Fulcro, like UISM.

   NOTE: `nc` is recursive, and *does* compose if you want to name the components at various levels. It can be used with queries from
   other defsc components:

   ```
   (def query (nc [:user/id
                   :user/name
                   ;; Generate an anonymous component that is available in the registry under ::Session
                   {:user/session-details (nc [:session/id :session/last-login] {:componentName ::Session})}
                   ;; Use a defsc query as the source
                   {:user/settings (comp/get-query Settings)}
                   ;; Autogenerates an anonymous address query component that has no name
                   {:user/address [:address/id :address/street]}]))
   ```
   "
  ([query] (nc query {}))
  ([query {:keys [componentName] :as top-component-options}]
   (let [ast (eql/query->ast query)]
     (:component (normalize* ast top-component-options)))))

(defn- normalize-form* [{:keys [children type] :as original-node} top-component-options]
  (let [detected-id-key (or (ast-id-key children) (throw (ex-info "Query must have an ID field for normalization detection" {:query (eql/ast->query original-node)})))
        _               detected-id-key
        form-fields     (into #{}
                          (comp
                            (map :key)
                            (filter #(and
                                       (not (vector? %))
                                       (not= "ui" (namespace %))
                                       (not= % detected-id-key))))
                          children)
        children        (conj children (eql/expr->ast fs/form-config-join))
        component       (fn [& args])
        new-children    (mapv
                          (fn [{:keys [type] :as node}]
                            (if (and (= type :join) (not (:component node)))
                              (normalize-form* node {})
                              node))
                          children)
        updated-node    (assoc original-node :children new-children :component component)
        query           (if (= type :join)
                          (eql/ast->query (assoc updated-node :type :root))
                          (eql/ast->query updated-node))
        _               (comp/add-hook-options! component (cond-> (with-meta
                                                                    (merge
                                                                      {:initial-state (fn [& args] {})}
                                                                      top-component-options
                                                                      {:query       (fn [& args] query)
                                                                       :ident       (fn [_ props] [detected-id-key (get props detected-id-key)])
                                                                       :form-fields form-fields
                                                                       "props"      {"fulcro$queryid" :anonymous}})
                                                                    {:query-id :anonymous})))]
    updated-node))

(defn formc
  "Create an anonymous normalizing form component from EQL. Every level of the query MUST
   have an `:<???>/id` field which is used to build the ident, and every non-id attribute will be considered part
   of the form except:

   * Props in the namespace `ui` like `:ui/checked?`
   * Idents list `[:component/id :thing]`
   * Root links like `[:root/key '_]`

   This function also auto-adds the necessary form-state form join, and populates the anonymous component with the
   `:form-fields` option. You can add additional component options to the top-level anonymous component with
   `top-component-options`.

   See also `nc`, which is similar but does not autogenerate form-related add-ins."
  ([EQL] (formc EQL {}))
  ([EQL top-component-options]
   (let [ast (eql/query->ast EQL)]
     (:component (normalize-form* ast top-component-options)))))

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

#?(:clj
   (defmacro with-fulcro
     "Wraps the given body with the correct internal bindings so that Fulcro internals
     will work when that body is not rendered by Fulcro (e.g. async render from controlling component, rendered by
     non-Fulcro parent.
     "
     [app & body]
     `(binding [comp/*app*    ~app
                comp/*depth*  0
                comp/*shared* (comp/shared ~app)
                comp/*parent* nil]
        ~@body)))

(defn ^:export using-fulcro
  "Runs `callback` immediately, but within the context of a Fulcro app. Use this from js to get the same effect as
   `with-fulcro`:

   ```
   using_fulcro(fulcroapp, () => <div>...</div>);
   ```
   "
  [app callback]
  (with-fulcro app
    (callback)))
