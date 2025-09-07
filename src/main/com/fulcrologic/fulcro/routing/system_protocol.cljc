(ns com.fulcrologic.fulcro.routing.system-protocol)

(defprotocol RoutingSystem
  (-route-to! [this {:keys [target route params force?]
                     :as   options}]
    "Change the route such that the target (or route) is on-screen. One of target (a component class) or route (a vector of strings)
     must be supplied. Optional params will be sent to the route.

     Use the `route-to!` wrapper instead of this.

     Will be a no-op if the current route is busy unless the `force?` option is true.")
  (-current-route [this] "Returns the current route as a map with keys :target :route and :params.")
  (-current-route-busy? [this] "Returns true if the active leaf of routing is busy and should prevent routing.")
  (-back! [this] "Attempt to go to the prior route. Useful for screens that have things like a Cancel button that are used from multiple places.")
  (-current-route-params [this] "Returns the current route params as a map, or nil if there are none.")
  (-set-route-params! [this params]
    "Change the params that are recorded with the current route (if history is in play). This doesn't change the
     path of the current-route, but updates parameters associated with it. Params can be a clojure map containing any
     data type that can be serialized by the current installed transit handlers.")

  (-add-route-listener! [this k listener]
    "Add a `listener` (fn [app  routing-event]) that will be notified any time the route changes in any way (including params).
     `k` should be a unique name for the listener, and is how you remove the listener later.")
  (-remove-route-listener! [this k]
    "Remove the route listener known by key `k`."))

