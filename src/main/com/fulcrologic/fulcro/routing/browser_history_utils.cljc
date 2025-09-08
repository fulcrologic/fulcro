(ns com.fulcrologic.fulcro.routing.browser-history-utils
  (:require
    [clojure.string :as str]
    [com.fulcrologic.guardrails.core :refer [>defn => ?]]
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.transit :refer [transit-clj->str transit-str->clj]]
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.routing.system :as sys :refer [notify!]]
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

(defn build-popstate-listener
  "Returns a fn that can be used as a popstate listener that will properly support handling user forward/back
   events from the browser.

   See dynamic-routing-browser-system for how this should be integrated into your own browser support.

   `vnumber` - A volatile holding the current routing sequence number
   `vlisteners` - A volatile holding a map from listener key to listener function"
  [app-ish vnumber vlisteners]
  (let [app                (rc/any->app app-ish)
        vignore            (volatile! 0)
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
    pop-state-listener))

