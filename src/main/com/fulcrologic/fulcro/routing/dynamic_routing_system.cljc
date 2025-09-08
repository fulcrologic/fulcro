(ns com.fulcrologic.fulcro.routing.dynamic-routing-system
  "An implementation of the RoutingSystem abstraction for when you are using dynamic-routing as your UI routing,
   but have NO browser (e.g. React Native). All of the notes in dynamic-routing-browser-system that are NOT URL
   related apply to this namespace.

   The back support works for up to 10 steps, and route parameters properly work but are simply stored in local
   RAM for runtime-only use.

   No history is recorded on startup. It is recommended that you issue an explicit route-to! on the system at startup
   to establish a known location and start of history.
   "
  (:require
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.routing.system :as sys]
    [com.fulcrologic.fulcro.routing.system-protocol :as sp]))

(defn push-route [history route] (take 10 (cons route history)))
(defn pop-route [history] (drop 1 history))

(deftype DynamicRoutingSystem [app vhistory vlisteners]
  sp/RoutingSystem
  (-route-to! [this {:keys [target route params force?]
                     :as   options}]
    (let [Target (or target (dr/resolve-target app route))]
      (dr/route-to! app (-> params
                          (assoc :target Target
                                 :route-params params
                                 :before-change (fn [_ {:keys [path route-params]}]
                                                  (vswap! vhistory push-route {:route  path
                                                                               :params route-params}))
                                 ::dr/force? (boolean force?))
                          (dissoc :params)))))
  (-replace-route! [this {:keys [target route params] :as new-route}]
    (let [path (or route (dr/absolute-path app target params))]
      (vswap! vhistory (fn [old] (cons (assoc new-route :route path) (rest old))))))
  (-current-route [this] (first @vhistory))
  (-current-route-busy? [this] (not (dr/can-change-route? app)))
  (-back! [this force?]
    (if (and (not force?) (sp/-current-route-busy? this))
      (sys/notify! (vals @vlisteners) {:desired-route (second @vhistory)
                                       :direction     :back
                                       :denied?       true})
      (do
        (vswap! vhistory pop-route)
        (let [{:keys [route params] :as rte} (first @vhistory)
              Target (dr/resolve-target app route)]
          (dr/route-to! app {:target       Target
                             :force?       force?
                             :route-params params})
          (sys/notify! (vals @vlisteners) rte)))))
  (-current-route-params [this] (:params (first @vhistory)))
  (-set-route-params! [this params]
    (vswap! vhistory (fn [[rt & others :as old-history]] (cons (assoc rt :params params) others))))
  (-add-route-listener! [this k listener] (vswap! vlisteners assoc k listener) nil)
  (-remove-route-listener! [this k] (vswap! vlisteners dissoc k) nil))

(defn install-dynamic-routing-system!
  "Install the dynamic router system"
  [app]
  (let [vhistory   (volatile! (list))
        vlisteners (volatile! {})
        sys        (->DynamicRoutingSystem app vhistory vlisteners)]
    (sys/install-routing-system! app sys))
  app)
