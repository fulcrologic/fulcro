(ns fulcro.client.routing
  #?(:cljs (:require-macros fulcro.client.routing))
  (:require [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.core :as fc]
            [om.next :as om :refer [defui]]
            om.dom
    #?(:cljs [cljs.loader :as loader])
            [fulcro.client.logging :as log]
            [fulcro.client.util :refer [conform!]]
            [clojure.spec.alpha :as s]
            [fulcro.client.util :as util]
            [fulcro.client.logging :as log]
            [om.next :as om]))

#?(:clj
   (s/def ::mutation-args
     (s/cat
       :sym symbol?
       :doc (s/? string?)
       :arglist vector?
       :body (s/+ (constantly true)))))

#?(:clj
   (defn- emit-union-element [sym ident-fn kws-and-screens]
     (try
       (let [query         (reduce (fn [q {:keys [kw sym]}] (assoc q kw `(om.next/get-query ~sym))) {} kws-and-screens)
             first-screen  (-> kws-and-screens first :sym)
             screen-render (fn [cls] `((om.next/factory ~cls {:keyfn (fn [props#] ~(name cls))}) (om.next/props ~'this)))
             render-stmt   (reduce (fn [cases {:keys [kw sym]}]
                                     (-> cases
                                       (conj kw (screen-render sym)))) [] kws-and-screens)]
         `(om.next/defui ~(vary-meta sym assoc :once true)
            ~'static fulcro.client.core/InitialAppState
            (~'initial-state [~'clz ~'params] (fulcro.client.core/get-initial-state ~first-screen ~'params))
            ~'static om.next/Ident
            ~ident-fn
            ~'static om.next/IQuery
            (~'query [~'this] ~query)
            ~'Object
            (~'render [~'this]
              (let [page# (first (om.next/get-ident ~'this))]
                (case page#
                  ~@render-stmt
                  (om.dom/div nil (str "Cannot route: Unknown Screen " page#)))))))
       (catch Exception e `(def ~sym (log/error "BROKEN ROUTER!"))))))

#?(:clj
   (defn- emit-router [router-id sym union-sym]
     `(om.next/defui ~(vary-meta sym assoc :once true)
        ~'static fulcro.client.core/InitialAppState
        (~'initial-state [~'clz ~'params] {:id ~router-id :current-route (fulcro.client.core/get-initial-state ~union-sym {})})
        ~'static om.next/Ident
        (~'ident [~'this ~'props] [:fulcro.client.routing.routers/by-id ~router-id])
        ~'static om.next/IQuery
        (~'query [~'this] [:id {:current-route (om.next/get-query ~union-sym)}])
        ~'Object
        (~'render [~'this]
          ((om.next/factory ~union-sym) (:current-route (om.next/props ~'this)))))))

#?(:clj
   (s/def ::router-args (s/cat
                          :sym symbol?
                          :router-id keyword?
                          :ident-fn (constantly true)
                          :kws-and-screens (s/+ (s/cat :kw keyword? :sym symbol?)))))

#?(:clj
   (defmacro ^{:doc      "Generates a component with a union query that can route among the given screen, which MUST be
in cljc files. The first screen listed will be the 'default' screen that the router will be initialized to show.

- All screens *must* implement InitialAppState
- All screens *must* have a UI query
- Add screens *must* have state that the ident-fn can use to determine which query to run. E.g. the left member
of running (ident-fn Screen initial-screen-state) => [:kw-for-screen some-id]
"
               :arglists '([sym router-id ident-fn & kws-and-screens])} defrouter
     [& args]
     (let [{:keys [sym router-id ident-fn kws-and-screens]} (conform! ::router-args args)
           union-sym (symbol (str (name sym) "-Union"))]
       `(do
          ~(emit-union-element union-sym ident-fn kws-and-screens)
          ~(emit-router router-id sym union-sym)))))

(def routing-tree-key ::routing-tree)
(def routers-table :fulcro.client.routing.routers/by-id)    ; NOTE: needed in macro, but hand-coded

(defn make-route
  "Make a route name that executes the provided routing instructions to change which screen in on the UI. routing-instructions
  must be a vector. Returns an item that can be passed to `routing-tree` to generate your overall application's routing
  plan.

  `(make-route :route/a [(router-instruction ...) ...])`

  "
  [name routing-instructions]
  {:pre [(vector? routing-instructions)]}
  {:name name :instructions routing-instructions})

(defn routing-tree
  "Generate initial state for your application's routing tree. The return value of this should be merged into your overall
  app state in your Root UI component

  ```
  (defui Root
    static fc/InitialAppState
    (initial-state [cls params]  (merge {:child-key (fc/get-initial-state Child)}
                                        (routing-tree
                                          (make-route :route/a [(router-instruction ...)])
                                          ...)))
    ...
  ```
  "
  [& routes]
  {routing-tree-key (reduce (fn [tree {:keys [name instructions]}] (assoc tree name instructions)) {} routes)})

(defn router-instruction
  "Return the definition of a change-route instruction."
  [router-id target-screen-ident]
  {:target-router router-id
   :target-screen target-screen-ident})

(defn current-route
  "Get the current route from the router with the given id"
  [state-map router-id] (get-in state-map [routers-table router-id :current-route]))

(defn- set-ident-route-params
  "Replace any keywords of the form :params/X with the value of (get route-params X)"
  [ident route-params]
  (mapv (fn [element]
          (if (and (keyword? element) (= "param" (namespace element)))
            (let [v (get route-params (keyword (name element)) element)]
              (cond
                (and (string? v) (seq (re-seq #"^[0-9][0-9]*$" v))) #?(:clj  (Integer/parseInt v)
                                                                       :cljs (js/parseInt v))
                (and (string? v) (seq (re-seq #"^[a-zA-Z]" v))) (keyword v)
                :else v))
            element))
    ident))

(defn set-route
  "Set the given screen-ident as the current route on the router with the given ID. Returns a new application
  state map."
  [state-map router-id screen-ident]
  (log/debug (str "Setting route for router " router-id " to " screen-ident))
  (assoc-in state-map [routers-table router-id :current-route] screen-ident))

(declare DynamicRouter get-dynamic-router-target)

(defn- set-routing-query! [reconciler router-id [target-kw _]]
  (let [router   (om/ref->any reconciler [routers-table router-id])
        dynamic? (instance? DynamicRouter router)
        query    (when dynamic?
                   (some-> target-kw
                     get-dynamic-router-target
                     om/get-query))]
    (when query
      (log/debug (str "Setting routing query for " router-id " to " query))
      (om/set-query! router {:query [:id {:current-route query}]}))))

(defn update-routing-links
  "Given the app state map, returns a new map that has the routing graph links updated for the given route/params
  as a bidi match. This function should only be used if you use static UI routing. If you use DynamicRouter,
  then you should use `route-to-impl!` to ensure your routes are loaded."
  [state-map {:keys [handler route-params]} & {:keys [reconciler]}]
  (let [routing-instructions (get-in state-map [routing-tree-key handler])]
    (if-not (or (nil? routing-instructions) (vector? routing-instructions))
      (log/error "Routing tree does not contain a vector of routing-instructions for handler " handler)
      (reduce (fn [m {:keys [target-router target-screen]}]
                (let [parameterized-screen-ident (set-ident-route-params target-screen route-params)]
                  (when reconciler
                    (set-routing-query! reconciler target-router target-screen))
                  (set-route m target-router parameterized-screen-ident))) state-map routing-instructions))))

(defmulti get-dynamic-router-target
  "Get the component that renders the given screen type. The parameter is simply the keyword of the module/component.
  Note that all have to match: the module name in the compiler that contains the code for the component,
  the first element of the ident returned by the component, and the keyword passed to this multimethod to retrieve
  the component."
  (fn [k] k))

(defmethod get-dynamic-router-target :default [k] nil)

(defn add-route-state [state-map target-kw component]
  (let [tree-state       {:tmp/new-route (fc/get-initial-state component nil)}
        query            [{:tmp/new-route (om/get-query component)}]
        normalized-state (-> (om/tree->db query tree-state true)
                           (dissoc :tmp/new-route))]
    (util/deep-merge state-map normalized-state)))

(defn- install-route-impl [state target-kw component]
  (log/debug (str "Installing route for component " component))
  (defmethod get-dynamic-router-target target-kw [k] component)
  (swap! state add-route-state target-kw component))

(defmutation install-route
  "Fulcro mutation: Install support for a dynamic route. `target-kw` is the keyword that represents the table name of
  the target screen (first elemenet of the ident of the component), which must also match internal data in the
  state of that component at fulcro.client.routing/dynamic-route-key. `component` is the *class* of the UI component that will be
  shown by the router. It *must* implement InitialAppState to provide at least the value of `target-kw` at the
  predefined fulcro.client.routing/dynamic-route-key key.

  An example would be that you've defined a component like this:

  (ns app.component
    (:require fulcro.client.routing))

  (def target-kw :my-component)

  (defui Component
    static fc/InitialAppState
    (initial-state [c p] {fulcro.client.routing/dynamic-route-key target-kw})
    static om/Ident
    (ident [this props] [target-kw :singleton])
    ...)
  "
  [{:keys [target-kw component]}]
  (action [{:keys [state]}]
    (install-route-impl state target-kw component)))

(def dynamic-route-key ::dynamic-route)

(defui ^:once DynamicRouter
  static fc/InitialAppState
  (initial-state [clz {:keys [id]}] {:id id :current-route {}})
  static om.next/Ident
  (ident [this props] [routers-table (:id props)])
  static om.next/IQuery
  (query [this] [:id :current-route])
  Object
  (render [this]
    (let [{:keys [current-route]} (om/props this)
          target-key (get current-route dynamic-route-key)
          c          (get-dynamic-router-target target-key)
          factory    (when c (om/factory c {:keyfn dynamic-route-key}))]
      (log/debug (str "Rendering dynamic route: " current-route))
      (when factory
        (factory current-route)))))

(def ui-dynamic-router (om/factory DynamicRouter {:keyfn :id}))

(defn- process-pending-route!
  "Finish doing the routing after a module completes loading"
  [{:keys [state reconciler] :as env}]
  (let [target (::pending-route @state)
        ]
    (log/debug (str "Attempting to route to " target))
    (swap! state
      (fn [s]
        (cond-> (dissoc s ::pending-route)
          (contains? target :handler) (update-routing-links target))))))

(defn- dynamic-route-load-failed!
  "TODO: Figure out how to figure this out and call it! I don't see a way to detect cljs module load failures."
  [state-atom]
  (swap! state-atom ::pending-route :LOAD-FAILED))

(defn- route-target-missing?
  "Returns true iff the given ident has no component loaded into the dynamic routing multimethod."
  [ident]
  (let [screen (first ident)
        c      (get-dynamic-router-target screen)]
    (nil? c)))

(defn- is-dynamic-router?
  "Returns true if the given component (instance) is a DynamicRouter."
  [component]
  (instance? DynamicRouter component))

(defn- get-missing-routes
  "Returns a sequence of routes that need to be loaded in order for routing to succeed."
  [reconciler state-map {:keys [handler route-params] :as params}]
  (let [routing-instructions (get-in state-map [routing-tree-key handler])]
    (if-not (or (nil? routing-instructions) (vector? routing-instructions))
      (do
        (log/error "Routing tree does not contain a vector of routing-instructions for handler " handler)
        [])
      (reduce (fn [routes {:keys [target-router target-screen]}]
                (let [router (om/ref->any reconciler [routers-table target-router])]
                  (if (and (is-dynamic-router? router) (route-target-missing? target-screen))
                    (conj routes (first target-screen))
                    routes))) [] routing-instructions))))

(defn- load-routes [{:keys [state reconciler] :as env} routes]
  #?(:clj (log/info "Dynamic loading of routes is not done on the server itself.")
     :cljs
          (let [loaded  (atom 0)
                to-load (count routes)
                finish  (fn [k]
                          (fn []
                            (swap! loaded inc)
                            (when (= @loaded to-load)
                              (log/debug "Loading succeeded for missing router with name " k)
                              (swap! state add-route-state k (get-dynamic-router-target k))
                              (process-pending-route! env))))]
            (doseq [r routes]
              (log/debug (str "No route was loaded for " r ". Attempting to load."))
              (loader/load r (finish r))))))

(defn route-to-impl!
  "Mutation implementation, for use as a composition into other mutations. This function can be used
  from within mutations. If a DynamicRouter is used in your routes, then this function may trigger
  code loading. Once the loading is complete (if any is needed), it will trigger the actual UI routing.

  If routes are being loaded, then the root property in your app state :fulcro.client.routing/pending-route
  will be your `bidi-match`. You can use a link query to pull this into your UI to show some kind of indicator."
  [{:keys [state reconciler] :as env} bidi-match]
  (if-let [missing-routes (seq (get-missing-routes reconciler @state bidi-match))]
    (if (= bidi-match (get @state ::pending-route))
      ; TODO: This could be the user clicking again, or a legitimate failure...Not much more I can do yet.
      (log/error "Attempt to trigger a route that was pending, but that wasn't done loading (or failed to load).")
      (do
        (log/debug (str "Missing routes: " missing-routes))
        (swap! state assoc ::pending-route bidi-match)
        (load-routes env missing-routes)))
    (do
      (log/debug (str "Updating routing links " bidi-match))
      (swap! state #(-> %
                      (dissoc ::pending-route)
                      (update-routing-links bidi-match :reconciler reconciler))))))

(m/defmutation route-to
  "Om Mutation (use in transact! only):

  Change the application's overall UI route to the given route by handler. Handler must be a single keyword that
  indicates an entry in your routing tree (which must be in the initial app state of your UI root). route-params
  is a map of key-value pairs that will be substituted in the target screen idents of the routing tree.

  If any of the routers are dynamic, then this mutation will check to see if the target routes are loaded. If any
  are not present, then module load(s) will be triggered for them, and the route will be pending until the code arrives.

  If a new route-to is run before pending routes are installed, then the pending route will be cancelled, but the code
  loading will continue.

  You may use a link query to get [:fulcro.client.routing/pending-route '_] in your application. If it is not nil
  then a route is pending, and you can show UI indicators of this."
  [{:keys [handler route-params] :as params}]
  (action [env] (route-to-impl! env params)))
