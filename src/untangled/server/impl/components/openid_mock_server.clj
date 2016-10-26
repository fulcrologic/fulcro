(ns untangled.server.impl.components.openid-mock-server
  (:require
    [ring.util.response :refer [get-header]]
    [clojure.string :refer [split]]
    [clj-jwt.core :as jwtc]
    [clj-jwt.key :as jwtk]
    [clj-time.core :as time]
    [com.stuartsierra.component :as component]
    [clojure.data.json :as json]
    [untangled.server.impl.jwt-validation :as jwtv])
  (:import (java.net URLEncoder URLDecoder)
           (org.apache.commons.codec.binary Base64)))

(def default-options
  {:path                                  "/openid"
   :response-mode                         :fragment
   :issuer                                "https://localhost:44300"
   :exp-in-hours                          36
   :users                                 {"123-456" {:sub   "123-456"
                                                      :email "test@test.com"
                                                      :realm ["realm1" "realm2"]
                                                      :name  "Duck Dodgers"
                                                      :role  "[\"test\" \"test2\"]"}}
   :aud                                   "api"
   :private-key-path                      "mock-keys/host.key"
   :public-key-path                       "mock-keys/server.crt"
   :alg                                   :RS256
   :grace-period-minutes                  1
   :idp                                   "idsrv"
   :scope                                 ["role" "openid" "profile" "email" "api1"]
   :claims                                ["realm" "role" "sub" "name" "family_name" "given_name" "middle_name" "nickname" "preferred_username" "profile" "picture" "website" "gender" "birthdate" "zoneinfo" "locale" "updated_at" "email" "email_verified"]
   :response-types-supported              ["code" "token" "id_token" "id_token token" "code id_token" "code token" "code id_token token"]
   :response-modes-supported              ["form_post" "query" "fragment"]
   :grant-types-supported                 ["authorization_code" "client_credentials" "password" "refresh_token" "implicit"]
   :subject-types-supported               ["public"]
   :id-token-signing-alg-values-supported ["RS256"]
   :token-endpoint-auth-methods-supported ["client_secret_post" "client_secret_basic"]
   :client-id                             "mvc6"})

(defn authorize [request options]
  (let [params            (apply merge
                            (map (fn [v]
                                   (let [pairs (split v #"=")]
                                     {(first pairs) (second pairs)}
                                     )) (split (:query-string request) #"&")))
        user-id           (get params "user")
        user              (get-in options [:users user-id] (-> options :users first second))
        claims            {:iss   (:issuer options)
                           :exp   (time/plus (time/now) (time/hours (:exp-in-hours options)))
                           :iat   (time/now)
                           :name  (:name user)
                           :role  (:role user)
                           :sub   (:sub user)
                           :realm (:realm user)
                           :email (:email user)
                           :nonce (get params "nonce")}
        access-claims     {:iss       (:issuer options)
                           :exp       (time/plus (time/now) (time/hours (:exp-in-hours options)))
                           :iat       (time/now)
                           :aud       (:aud options)
                                        ;      :auth-time (time/now)
                           :sub       (:sub user)
                           :email     (:email user)
                           :role      (:role user)
                           :nonce     (get params "nonce")
                           :amr       ["passsord"]
                           :idp       (:idp options)
                           :scope     (:scope options)
                           :client-id (:client-id options)}
        id-jwt            (jwtc/jwt claims)
        id-signed-jwt     (jwtc/sign id-jwt (:alg options) (jwtk/private-key (:private-key-path options) "pass phrase"))
        access-jwt        (jwtc/jwt access-claims)
        signed-access-jwt (jwtc/sign access-jwt (:alg options) (jwtk/private-key (:private-key-path options) "pass phrase"))
        query             {"access_token" (jwtc/to-str signed-access-jwt)
                           "token_type"   "bearer"
                           "id_token"     (jwtc/to-str id-signed-jwt)
                           "expires_in"   "3600"
                           "state"        (get params "state")}

        query-as-params (apply str (interpose "&" (map (fn [[k v]] (str k "=" v)) query)))

        redirect-uri (. URLDecoder decode (get params "redirect_uri") "UTF-8")

        input-form-string (apply str (map
                                       (fn [[k v]]
                                         (str "<input type=\"hidden\" name=\"" k "\" value=\"" v "\"/>"))
                                       query))

        form-body (str "<!DOCTYPE html><html><head>"
                    "<title>Submit this form</title>"
                    "<meta name= \" viewport \" content= \" width=device-width, initial-scale=1.0 \"/>"
                    "</head><body>"
                    "<form method= \"post\" action=\"" redirect-uri "\">"
                    input-form-string
                    "</form>"
                    "<script type=\"text/javascript\">(function () {document.forms[0].submit();})();</script>"
                    "</body></html>")]
    (if (= (:response-mode options) :fragment)
      {:status 302 :headers {"Location" (str redirect-uri "#" query-as-params)}}
      {:status 200 :headers {"Content-Type" "text/html"} :body form-body})))



(defn discovery [request options]
  (let [path (str (:issuer options) "/" (:path options))]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/write-str {:issuer                                (:issuer options)
                               :jwks_uri                              (str path "/.well-known/jwks")
                               :authorization_endpoint                (str path "/connect/authorize")
                               :token_endpoint                        (str path "/token")
                               :userinfo_endpoint                     (str path "/userinfo")
                               :end_session_endpoint                  (str path "/endsession")
                               :check_session_iframe                  (str path "/checksession")
                               :revocation_supported                  (str path "/revocation")
                               :scopes_supported                      (:scope options)
                               :claims_supported                      (:claims options)
                               :response_types_supported              (:response-types-supported options)
                               :response_modes_supported              (:response-modes-supported options)
                               :grant_types_supported                 (:grant-types-supported options)
                               :subject-types-supported               (:subject-types-supported options)
                               :id_token_signing_alg_values_supported (:id-token-signing-alg-values-supported options)
                               :token_endpoint_auth_methods_supported (:token-endpoint-auth-methods-supported options)})}))

(defn encode-bigint [v]
  (let [bv (-> v .toByteArray)
        urlsafe (Base64/encodeBase64URLSafeString bv)]
    (apply str (mapv char urlsafe))))

(defn jwks [request options]
  (let [public-key (jwtk/public-key (:public-key-path options))
        raw-data (slurp (:public-key-path options))
        key-data (apply str (-> (clojure.string/split-lines raw-data) rest reverse rest reverse))
        modulus (.getModulus public-key)
        exp (.getPublicExponent public-key)
        thum (.hashCode public-key)
        e (encode-bigint exp)
        n (encode-bigint modulus)
        x5t (apply str (map char (Base64/encodeBase64 (->> thum str (map byte) byte-array))))]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/write-str {:keys [{:kty "RSA" :use "sig" :e e :n n :x5t x5t :x5c [key-data]}]}
                              :escape-slash false)}))

(defn endsession [request options]
  (let [redirect-uri (get-in request [:query-params "post_logout_redirect_uri"])]
    {:status  302
     :headers {"Content-Type" "text/html"
               "Location"     (str redirect-uri)}
     :body    ""}))

(defn wrap-openid-mock
  "Middleware that simulates an openid connect server for development purposes
  only.  This handler is not for production use.


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
      (condp #(= (str (:path merged-options) %1) %2) (:uri request)
        "/connect/authorize" (authorize request merged-options)
        "/.well-known/jwks" (jwks request merged-options)
        "/.well-known/openid-configuration" (discovery request merged-options)
        "/connect/endsession" (endsession request merged-options)
        (handler request)))))

(defn read-keys [config]
  (assoc config :public-keys
    (jwtv/public-keys-from-jwks
      (get (json/read-str (:body (jwks {} config)))
           "keys"))))

(defrecord MockOpenIdConfig [config]
  component/Lifecycle
  (start [this]
    (let [mock-config (-> config :value :openid-mock)]
      (assoc this :value (read-keys (merge default-options mock-config))
        :middleware (partial wrap-openid-mock mock-config))))
  (stop [this] this))
