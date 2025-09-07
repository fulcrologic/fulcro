(ns com.fulcrologic.fulcro.routing.system
  "A generalization of routing, useful when different libraries/applications want to be isloated from the real underlying
   routing implementation.

   ALPHA: This namespace and any that use it should be considered UNSTABLE and EXPERIMENTAL. These are being
   published in order to get feedback and ensure they meet the feature needs of users before freezing the API.
   Every attempt will be made to prevent breaking changes, but while in this stage there are no guarantees.
   "
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.routing.system-protocol :as sp]
    [com.fulcrologic.guardrails.core :refer [=> >defn ?]]
    [taoensso.timbre :as log]))

(s/def ::RoutingSystem #(satisfies? sp/RoutingSystem %))

(>defn install-routing-system!
  "Install the given routing `system` on the given Fulcro `app`."
  [app system]
  [:com.fulcrologic.fulcro.application/app ::RoutingSystem => :com.fulcrologic.fulcro.application/app]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app]
    (swap! runtime-atom assoc ::routing-system system))
  app)

(>defn current-routing-system
  "Returns the currently-installed routing system, or nil if none is installed."
  [app-ish]
  [any? => (? ::RoutingSystem)]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} (rc/any->app app-ish)]
    (some-> runtime-atom deref ::routing-system)))

(s/def ::target (s/or
                  :symbol qualified-symbol?
                  :string string?
                  :keyword qualified-keyword?
                  :class rc/component-class?))
(s/def ::route (s/every (s/or :s string? :k keyword) :kind vector?))
(s/def ::params map?)
(s/def ::target-or-route-with-params (s/or
                 :class-based (s/keys :req-un [::target] :opt-un [::params])
                 :vector-based (s/keys :req-un [::route] :opt-un [::params])))

(defn- rrun!
  "Run the given routing system f on the app. Returns what f returns, or nil if no routing system."
  [app-ish f & args]
  (if-let [sys (current-routing-system app-ish)]
    (apply f sys args)
    (do
      (log/debug "Routing ignored. No routing system installed.")
      nil)))

(>defn route-to!
  "Use the routing system to route to a particular route. `options` may contain:

   * :target A component class that you wish to be on-screen
   * :route A vector of strings/keywords that abstractly define a path to a target. E.g. [\"account\" :account_id]
   * :params An arbitrary map of data to be sent along with the routing command which can affect how the route is interpreted.
  "
  [app-ish options]
  [any? ::target-or-route-with-params => any?]
  (rrun! app-ish sp/-route-to! options))

(>defn current-route
  [app-ish]
  [any? => (? ::target-or-route-with-params)]
  (rrun! app-ish sp/-current-route))

(>defn current-route-busy?
  [app-ish]
  [any? => boolean?]
  (boolean (rrun! app-ish sp/-current-route-busy?)))

(defn back!
  "Attempt to go to the last place the routing system was before the current route. Optional, and may have limits to
   the number of calls that can be successful (e.g. history support may be limited or non-existent)."
  [app-ish]
  (rrun! app-ish sp/-back!))

(defn set-route-params!
  "Attempt to OVERWRITE the parameters of the current route (in history). The may affect the URL. `params` should be a map
   where the types are all transit-serializable by the currently installed types.
   "
  [app-ish params]
  (rrun! app-ish sp/-set-route-params! params)
  nil)

(defn current-route-params
  "Returns the current route parameters, if available, as a map. May return nil."
  [app-ish]
  (rrun! app-ish sp/-current-route-params))

(defn merge-route-params!
  "Attempt to merge the given params-to-merge onto the current route's params."
  [app-ish params-to-merge]
  (let [old-params (current-route-params app-ish)
        new-params (merge old-params params-to-merge)]
    (set-route-params! app-ish (merge old-params params-to-merge))))
