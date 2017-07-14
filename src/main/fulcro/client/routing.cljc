(ns fulcro.client.routing
  #?(:cljs (:require-macros fulcro.client.routing))
  (:require [fulcro.client.mutations :as m]
            fulcro.client.core
            om.next
            om.dom
            [fulcro.client.logging :as log]
            [fulcro.client.util :refer [conform!]]
            [clojure.spec.alpha :as s]
            [fulcro.client.logging :as log]))

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
    static uc/InitialAppState
    (initial-state [cls params]  (merge {:child-key (uc/get-initial-state Child)}
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
            (keyword (get route-params (keyword (name element)) element))
            element))
    ident))

(defn set-route
  "Set the given screen-ident as the current route on the router with the given ID. Returns a new application
  state map."
  [state-map router-id screen-ident]
  (assoc-in state-map [routers-table router-id :current-route] screen-ident))

(defn update-routing-links
  "Given the app state map, returns a new map that has the routing graph links updated for the given route/params
  as a bidi match."
  [state-map {:keys [handler route-params]}]
  (let [routing-instructions (get-in state-map [routing-tree-key handler])]
    (if-not (or (nil? routing-instructions) (vector? routing-instructions))
      (log/error "Routing tree does not contain a vector of routing-instructions for handler " handler)
      (reduce (fn [m {:keys [target-router target-screen]}]
                (let [parameterized-screen-ident (set-ident-route-params target-screen route-params)]
                  (set-route m target-router parameterized-screen-ident))) state-map routing-instructions))))

(m/defmutation route-to
  "Om Mutation (use in transact! only):

  Change the application's overall UI route to the given route by handler. Handler must be a single keyword that
  indicates an entry in your routing tree (which must be in the initial app state of your UI root). route-params
  is a map of key-value pairs that will be substituted in the target screen idents of the routing tree."
  [{:keys [handler route-params] :as params}]
  (action [{:keys [state]}] (swap! state update-routing-links params)))
