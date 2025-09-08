(ns com.fulcrologic.fulcro.routing.dynamic-routing-browser-system
  "An implementation of the RoutingSystem abstraction for when you are using dynamic-routing as your UI routing,
   and you would like to integrate it with an HTML Browser which has history and forward/back buttons.

   In order for this to work properly you will use the dynamic routing defrouter as describe in the documentation
   for the *construction* of the UI, but you should use the function in the
   com.fulcrologic.fulcro.routing.system namespace for doing any route-related logic (e.g. changing routes,
   looking at the current route, listening for external route changes, manipulating the URL parameters, etc.).

   The URL itself will be built from the route segments, and any routing params will be transit-encoded into the
   query string of the URL. This ensures that browser nav (back/forward) can properly restore your route by passing you
   the same routing parameters you received previously.

   Remember that dynamic-routing will encode the keyword parts of route-segments as strings (for URL compatibility)
   and therefore your route target hooks (like will-enter) must be prepared to accept them as strings.

   That said, the routing system method `current-route` will return the parameters as their original types, as
   derived from the URL query string (a transit encoded version of the parameters passed to the route).

   A target can manipulate and use the (type safe) URL parameters using sys/current-route,
   sys/merge-route-params! and sys/set-route-params!, but
   be careful not to overwrite parameters that are also used in your route segments! Getting those out of sync can cause
   strange behavior. In general, use non-namespaced keywords for your route segments, and namespaced ones for your
   custom route-specific data.

   = System Startup

   When you enter your application the user MAY have pasted a particular bookmark. Since authentication is your responsibility,
   this system makes no attempt to *restore* the URL as app state. Instead, you must figure out what to do.

   You can decode the URL as a route using the `current-url->route` function from this namespace.

   You *SHOULD* explicitly issue a route-to! call, even if it is just to put that route you decoded into effect."
  (:require
    [com.fulcrologic.fulcro.routing.browser-history-utils :as bhu]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.routing.system :as sys :refer [notify!]]
    [com.fulcrologic.fulcro.routing.system-protocol :as sp]
    [taoensso.timbre :as log]))

(deftype DynamicRoutingBrowserSystem [app vnumber vlisteners route->url url->route]
  sp/RoutingSystem
  (-route-to! [this {::keys [external?]
                     :keys  [target route params force?]
                     :as    options}]
    (let [Target (or target (dr/resolve-target app route))]
      (dr/route-to! app (-> params
                          (assoc :target Target
                                 :route-params params
                                 :before-change (fn [_ {:keys [target path route-params]}]
                                                  (when-not external?
                                                    (let [rte {:route  path
                                                               :params route-params}]
                                                      (vswap! vnumber inc)
                                                      (bhu/push-state! @vnumber (route->url rte))
                                                      (notify! (vals @vlisteners) rte))))
                                 ::dr/force? (boolean force?))
                          (dissoc :params)))))
  (-replace-route! [this {:keys [route target params] :as new-route}]
    (let [path (or route (dr/absolute-path app target params))]
      (bhu/replace-state! @vnumber (route->url (assoc new-route :route path)))))
  (-current-route [this]
    (let [routes  (dr/active-routes app)
          nroutes (count routes)
          {:keys [path target-component] :as preferred-route} (first routes)]
      (if (> nroutes 1)
        (do
          (log/debug "Current route was ambiguous in code (sibling routers). Returning URL route instead")
          (bhu/current-url->route))
        (when path
          {:route  path
           :target target-component}))))
  (-current-route-busy? [this] (not (dr/can-change-route? app)))
  (-back! [this force?]
    (when force?
      (some-> (dr/target-denying-route-changes app) (dr/set-force-route-flag!)))
    (bhu/browser-back!))
  (-current-route-params [this] (:params (bhu/current-url->route)))
  (-set-route-params! [this params]
    (bhu/replace-state! @vnumber (route->url (assoc (bhu/current-url->route)
                                               :params params))))
  (-add-route-listener! [this k listener] (vswap! vlisteners assoc k listener) nil)
  (-remove-route-listener! [this k] (vswap! vlisteners dissoc k) nil))

(defn install-dynamic-routing-browser-system!
  "Install the dynamic router system with support for browser history/URL"
  ([app]
   (install-dynamic-routing-browser-system! app nil))
  ([app {:keys [prefix hash?]}]
   (let [vnumber            (volatile! 0)
         vlisteners         (volatile! {})
         sys                (->DynamicRoutingBrowserSystem app vnumber vlisteners
                              (fn [{:keys [route params]}]
                                (cond->> (bhu/route->url route params hash?)
                                  (seq prefix) (str prefix "/")))
                              (fn [] (bhu/current-url->route hash? prefix)))
         pop-state-listener (bhu/build-popstate-listener app vnumber vlisteners)]
     (sys/install-routing-system! app sys)
     (bhu/add-popstate-listener! pop-state-listener)
     app)))
