(ns untangled.server.impl.components.access-token-handler
  (:require [ring.util.response :refer [get-header]]
            [clojure.string :refer [split]]
            [com.stuartsierra.component :as component]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [taoensso.timbre :as log]
            [untangled.server.impl.jwt-validation :refer :all]))

(defn- add-claims-to-request
  "Adds a :user to the request, which is a map of claims."
  [req token options]
  (assoc req :user ((:claims-transform options) (:claims token))))

(defn- default-claims-transform [claims]
  claims)

(defn- get-token-from-bearer [str]
  "Gets access token from the Bearer string."
  (let [[bearer token] (split str #" ")]
    (if (and token (= bearer "Bearer")) token nil)))

(defn- get-token [request]
  (if-let [bearer (-> request :headers (get "authorization"))]
    (some-> bearer (get-token-from-bearer) read-token)
    (some-> (get-in request [:params "openid/access-token"]) read-token)))

(defn- missing-token? [token]
  (nil? token))

(defn- missing-sub? [token]
  (nil? (:sub (:claims token))))

(defn- missing-client-id? [token]
  (nil? (:client-id (:claims token))))

(defn- fail-with [token message]
  (log/debug "Token: " (:claims token) " Failed because: " message)
  false)

(defn- valid-token? [token {:keys [public-keys issuer audience grace-period-minutes]}]
  (cond
    (missing-token? token)                           (fail-with token "Token is missing.")
    (not (valid-signature? token public-keys))       (fail-with token "Invalid signature.")
    (not (valid-issuer? token issuer))               (fail-with token "Invalid issuer.")
    (not (valid-expire? token grace-period-minutes)) (fail-with token "Expired token.")
    (not (valid-audience? token audience))           (fail-with token "Invalid audience.")

    (and (missing-sub? token) (missing-client-id? token))
    (fail-with token (if (missing-sub? token) "Missing subject." "Missing client id."))

    :else true))

(def default-options
  {:authority            ""
   :audience             "api"
   :grace-period-minutes 1
   :claims-transform     default-claims-transform})

(defn wrap-access-token
  "Middleware that validates the request for a JWT access-token that are issued by
  an OpenID Connect server.  Validation rules include access-token signiture, issuer and
  audience.  The requeste wrapper also calls a specified function after validation to allow for
  claims transformation before associating the claims with the request.
  A :claims-principle map will be associated with the session after the claims
  claims transformation function is called.

  :issuer - a string that contains the issuer of the OpenID Connect server - required
  :public-key - a string that contains the public key used to validate the signiature - required
  :audience - a string that contains the Audience expected for this resource - required
  :grace-period-minutes - number of minutes token is allowed after token expiration time - optional (default 1)
  :claims-transform - function that is handle the token claims and must return
    transformed claims - optional"
  {:arglists '([options handler])}
  [options handler]
  (let [merged-options (merge default-options options)]
    (fn [request]
      (if-not (contains? (:authorized-routes merged-options) (:uri request))
        (handler request)
        (let [token (get-token request)]
          (if (valid-token? token merged-options)
            (-> request
              (add-claims-to-request token merged-options)
              handler)
            (handler request)))))))

(defrecord AccessTokenHandler [handler]
  component/Lifecycle
  (start [this]
    (let [pre-hook (.get-pre-hook handler)
          config (-> this :config :value :openid)
          authority (:authority config)
          discovery-doc-url (str authority "/.well-known/openid-configuration")
          discovery-doc (-> discovery-doc-url http/get :body json/read-str)
          issuer (get discovery-doc "issuer")
          public-keys-url (get discovery-doc "jwks_uri")
          public-keys (-> public-keys-url http/get :body json/read-str (get "keys"))
          public-keys' (public-keys-from-jwks public-keys)
          config' (assoc config :public-keys public-keys' :issuer issuer)]
      (.set-pre-hook! handler (comp pre-hook
                                (partial wrap-access-token config')))
      this))
  (stop [this] this))

