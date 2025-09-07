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
    [clojure.string :as str]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.routing.system :as sys]
    [com.fulcrologic.fulcro.routing.system-protocol :as sp]
    [com.fulcrologic.fulcro.algorithms.do-not-use :refer [base64-encode base64-decode]]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log])
  #?(:clj (:import (java.net URLDecoder URLEncoder)
                   (java.nio.charset StandardCharsets))))

;; js wrappers
(defn push-state! [n url] #?(:cljs (.pushState js/history #js {"n" n} "" url)))
(defn replace-state! [n url] #?(:cljs (.replaceState js/history #js {"n" n} "" url)))
(defn add-popstate-listener! [f] #?(:cljs (.addEventListener js/window "popstate" f)))
(defn browser-back! [] #?(:cljs (.back js/history)))
(defn browser-forward! [] #?(:cljs (.forward js/history)))

(>defn decode-uri-component
  "Decode the given string as a transit and URI encoded CLJ(s) value."
  [v]
  [(? string?) => (? string?)]
  (when (string? v)
    #?(:clj  (URLDecoder/decode ^String v (.toString StandardCharsets/UTF_8))
       :cljs (js/decodeURIComponent v))))

(>defn encode-uri-component
  "Encode a key/value pair of CLJ(s) data such that it can be safely placed in browser query params. If `v` is
   a plain string, then it will not be transit-encoded."
  [v]
  [string? => string?]
  #?(:clj  (URLEncoder/encode ^String v (.toString StandardCharsets/UTF_8))
     :cljs (js/encodeURIComponent v)))

(>defn query-params
  [raw-search-string]
  [string? => map?]
  (enc/try*
    (let [param-string (str/replace raw-search-string #"^[?]" "")]
      (reduce
        (fn [result assignment]
          (let [[k v] (str/split assignment #"=")]
            (cond
              (and k v (= k "_rp_")) (merge result (transit-str->clj (base64-decode (decode-uri-component v))))
              (and k v) (assoc result (keyword (decode-uri-component k)) (decode-uri-component v))
              :else result)))
        {}
        (str/split param-string #"&")))
    (catch :all e
      (log/error e "Cannot decode query param string")
      {})))

(>defn query-string
  "Convert a map to an encoded string that is acceptable on a URL.
  The param-map allows any data type acceptable to transit. The additional key-values must all be strings
  (and will be coerced to string if not). "
  [param-map & {:as string-key-values}]
  [map? (s/* string?) => string?]
  (str "?_rp_="
    (encode-uri-component (base64-encode (transit-clj->str param-map)))
    "&"
    (str/join "&"
      (map (fn [[k v]]
             (str (encode-uri-component (name k)) "=" (encode-uri-component (str v)))) string-key-values))))

(>defn route->url
  "Construct URL from route and params"
  [route params hash-based?]
  [coll? (? map?) boolean? => string?]
  (let [q (query-string (or params {}))]
    (if hash-based?
      (str q "#/" (str/join "/" (map str route)))
      (str "/" (str/join "/" (map str route)) q))))

(defn current-url->route
  "Convert the current browser URL into a route path and parameter map. Returns:

   ```
   {:route [\"path\" \"segment\"]
    :params {:param value}}
   ```

   You can save this value and later use it with `apply-route!`.

   Parameter hash-based? specifies whether to expect hash based routing. If no
   parameter is provided the mode is autodetected from presence of hash segment in URL.
  "
  ([] (current-url->route #?(:clj  false
                             :cljs (some? (seq (.. js/document -location -hash)))) nil))
  ([hash-based?] (current-url->route hash-based? nil))
  ([hash-based? prefix]
   #?(:cljs
      (let [path      (if hash-based?
                        (str/replace (.. js/document -location -hash) #"^[#]" "")
                        (.. js/document -location -pathname))
            pcnt      (count prefix)
            prefixed? (> pcnt 0)
            path      (if (and prefixed? (str/starts-with? path prefix))
                        (subs path pcnt)
                        path)
            route     (vec (drop 1 (str/split path #"/")))
            params    (or (some-> (.. js/document -location -search) (query-params)) {})]
        (log/debug "Decoded params" params)
        {:route  route
         :params params}))))

(defn- notify! [listeners event]
  (doseq [f listeners]
    (enc/catching
      (f event))))

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
                                                      (push-state! @vnumber (route->url rte))
                                                      (notify! (vals @vlisteners) rte))))
                                 ::dr/force? (boolean force?))
                          (dissoc :params)))))
  (-replace-route! [this {:keys [route target params] :as new-route}]
    (let [path (or route (dr/absolute-path app target params))]
      (replace-state! @vnumber (route->url (assoc new-route :route path)))))
  (-current-route [this]
    (let [routes  (dr/active-routes app)
          nroutes (count routes)
          {:keys [path target-component] :as preferred-route} (first routes)]
      (if (> nroutes 1)
        (do
          (log/debug "Current route was ambiguous in code (sibling routers). Returning URL route instead")
          (current-url->route))
        (when path
          {:route  path
           :target target-component}))))
  (-current-route-busy? [this] (not (dr/can-change-route? app)))
  (-back! [this force?]
    (when force?
      (some-> (dr/target-denying-route-changes app) (dr/set-force-route-flag!)))
    (browser-back!))
  (-current-route-params [this] (:params (current-url->route)))
  (-set-route-params! [this params]
    (replace-state! @vnumber (route->url (assoc (current-url->route)
                                           :params params))))
  (-add-route-listener! [this k listener] (vswap! vlisteners assoc k listener) nil)
  (-remove-route-listener! [this k] (vswap! vlisteners dissoc k) nil))

(defn install-dynamic-routing-browser-system!
  "Install the dynamic router system with support for browser history/URL"
  [app]
  (let [vnumber            (volatile! 0)
        vlisteners         (volatile! {})
        vignore            (volatile! 0)
        sys                (->DynamicRoutingBrowserSystem app vnumber vlisteners
                             (fn [{:keys [route params]}] (route->url route params false))
                             current-url->route)
        pop-state-listener (fn [evt]
                             ;; The scheme for handling popstate events is as follows:
                             ;; We keep track of a monotonically increasing number as the user navigates through
                             ;; routes (not forward/back button). If we receive an event, it means the user used
                             ;; forward/back OR we called .forward/.back.
                             ;; The incoming event will contain the assigned route sequence number.
                             ;; When we DO honor that route, we update our sequence tracking such that we are ON
                             ;; that number again (in other words, vnumber tracks the route sequence number we're on)
                             ;; This means that when the user side-effects a history traversal, we move the vnumber to
                             ;; that historical route sequence number. This allows us to detect if the user is going
                             ;; forward or backwards.
                             ;; If the user tries to use forward/back buttons, but the current system does NOT currently
                             ;; allow moving the route, then we immediately UNDO the route  change by calling .back/.forward.
                             ;; Unfortunately this has the effect of triggering a recursive event for us, so we use the
                             ;; vignore as a counter of how many times we've called the API, so we can ignore that many
                             ;; events. The count may be overkill (a boolean might suffice), but trying to cover the
                             ;; case where the user can mash on the fwd/back button quickly and possibly cause multiple
                             ;; events.
                             (if (pos? @vignore)
                               (vswap! vignore dec)
                               (when (rc/isoget-in evt ["state"])
                                 (let [current-sequence-number @vnumber
                                       route-sequence-number   (rc/isoget-in evt ["state" "n"])
                                       forward?                (and
                                                                 route-sequence-number current-sequence-number
                                                                 (< current-sequence-number route-sequence-number))
                                       listeners               (vals @vlisteners)
                                       current-route           (sys/current-route app)
                                       direction               (if forward? :forward :back)
                                       route-event             (assoc current-route
                                                                 :external? true
                                                                 :direction direction)]
                                   (js/console.log "Got pop state event." evt)
                                   (if (sys/current-route-busy? app)
                                     (do
                                       (vswap! vignore inc)
                                       (log/debug "Browser state change event denied")
                                       (notify! listeners {:desired-route (current-url->route)
                                                           :direction     direction
                                                           :external?     true
                                                           :denied?       true})
                                       (if forward?
                                         (browser-back!)
                                         (browser-forward!)))
                                     (do
                                       (log/debug "Navigating history")
                                       (vreset! vnumber route-sequence-number)
                                       (sys/route-to! app (assoc current-route ::external? true))
                                       (notify! listeners route-event)))))))]
    (sys/install-routing-system! app sys)
    (add-popstate-listener! pop-state-listener))
  app)
