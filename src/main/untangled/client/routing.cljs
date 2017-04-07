(ns untangled.client.routing
  (:require-macros untangled.client.routing)
  (:require [untangled.client.mutations :as m]
            untangled.client.core
            om.next
            om.dom
            [untangled.client.logging :as log]))

(def routing-tree-key ::routing-tree)
(def routers-table :untangled.client.routing.routers/by-id) ; NOTE: needed in macro, but hand-coded

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

(defn route-to
  "Om Mutation (use in transact! only):
  Change the application's overall UI route to the given route by handler. Handler must be a single keyword that indicates an entry in
  your routing tree (which must be in the initial app state of your UI root). route-params is a map of key-value pairs
  that will be substituted in the target screen idents of the routing tree."
  [{:keys [handler route-params]}] (comment "placeholder for IDE assistance"))

(defmethod m/mutate `route-to [{:keys [state]} k p]
  {:action (fn [] (swap! state update-routing-links p))})
