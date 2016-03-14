(ns untangled.server.impl.components.openid-mock-server
  (:require
    [ring.util.response :refer [get-header]]
    [clojure.string :refer [split]]
    [clj-jwt.core :as jwtc]
    [clj-jwt.key :as jwtk]
    [clj-time.core :as time]
    [com.stuartsierra.component :as component]
    [clojure.data.json :as json])
  (:import (java.net URLEncoder URLDecoder)
           (org.apache.commons.codec.binary Base64)))

(def default-options
  {:path                                  "/openid"
   :response-mode                         :fragment
   :issuer                                "https://localhost:44300"
   :exp-in-hours                          36
   :sub                                   "123-456"
   :email                                 "test@test.com"
   :realm                                 ["realm1" "realm2"]
   :name                                  "Duck Dodgers"
   :role                                  "[\"test\" \"test2\"]"
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
  (let [params (apply merge
                 (map (fn [v]
                        (let [pairs (split v #"=")]
                          {(first pairs) (second pairs)}
                          )) (split (:query-string request) #"&")))
        claims {:iss   (:issuer options)
                :exp   (time/plus (time/now) (time/hours (:exp-in-hours options)))
                :name  (:name options)
                :iat   (time/now)
                :role  (:role options)
                :sub   (:sub options)
                :realm (:realm options)
                :email (:email options)
                :nonce (get params "nonce")}
        access-claims {:iss       (:issuer options)
                       :exp       (time/plus (time/now) (time/hours (:exp-in-hours options)))
                       :iat       (time/now)
                       :aud       (:aud options)
                       ;      :auth-time (time/now)
                       :sub       (:sub options)
                       :email     (:email options)
                       :nonce     (get params "nonce")
                       :amr       ["passsord"]
                       :role      (:role options)
                       :idp       (:idp options)
                       :scope     (:scope options)
                       :client-id (:client-id options)}
        id-jwt (jwtc/jwt claims)
        id-signed-jwt (jwtc/sign id-jwt (:alg options) (jwtk/private-key (:private-key-path options) "pass phrase"))
        access-jwt (jwtc/jwt access-claims)
        signed-access-jwt (jwtc/sign access-jwt (:alg options) (jwtk/private-key (:private-key-path options) "pass phrase"))
        query {"access_token" (jwtc/to-str signed-access-jwt)
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

(def jwks-sample "{\"keys\":
[{\"kty\":\"RSA\",
\"use\":\"sig\",
\"kid\":\"a3rMUgMFv9tPclLa6yF3zAkfquE\",
\"x5t\":\"a3rMUgMFv9tPclLa6yF3zAkfquE\",
\"e\":\"AQAB\",
\"n\":\"qnTksBdxOiOlsmRNd-mMS2M3o1IDpK4uAr0T4_YqO3zYHAGAWTwsq4ms-NWynqY5HaB4EThNxuq2GWC5JKpO1YirOrwS97B5x9LJyHXPsdJcSikEI9BxOkl6WLQ0UzPxHdYTLpR4_O-0ILAlXw8NU4-jB4AP8Sn9YGYJ5w0fLw5YmWioXeWvocz1wHrZdJPxS8XnqHXwMUozVzQj-x6daOv5FmrHU1r9_bbp0a1GLv4BbTtSh4kMyz1hXylho0EvPg5p9YIKStbNAW9eNWvv5R8HN7PPei21AsUqxekK0oW9jnEdHewckToX7x5zULWKwwZIksll0XnVczVgy7fCFw\",
\"x5c\":[\"MIIDBTCCAfGgAwIBAgIQNQb+T2ncIrNA6cKvUA1GWTAJBgUrDgMCHQUAMBIxEDAOBgNVBAMTB0RldlJvb3QwHhcNMTAwMTIwMjIwMDAwWhcNMjAwMTIwMjIwMDAwWjAVMRMwEQYDVQQDEwppZHNydjN0ZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqnTksBdxOiOlsmRNd+mMS2M3o1IDpK4uAr0T4/YqO3zYHAGAWTwsq4ms+NWynqY5HaB4EThNxuq2GWC5JKpO1YirOrwS97B5x9LJyHXPsdJcSikEI9BxOkl6WLQ0UzPxHdYTLpR4/O+0ILAlXw8NU4+jB4AP8Sn9YGYJ5w0fLw5YmWioXeWvocz1wHrZdJPxS8XnqHXwMUozVzQj+x6daOv5FmrHU1r9/bbp0a1GLv4BbTtSh4kMyz1hXylho0EvPg5p9YIKStbNAW9eNWvv5R8HN7PPei21AsUqxekK0oW9jnEdHewckToX7x5zULWKwwZIksll0XnVczVgy7fCFwIDAQABo1wwWjATBgNVHSUEDDAKBggrBgEFBQcDATBDBgNVHQEEPDA6gBDSFgDaV+Q2d2191r6A38tBoRQwEjEQMA4GA1UEAxMHRGV2Um9vdIIQLFk7exPNg41NRNaeNu0I9jAJBgUrDgMCHQUAA4IBAQBUnMSZxY5xosMEW6Mz4WEAjNoNv2QvqNmk23RMZGMgr516ROeWS5D3RlTNyU8FkstNCC4maDM3E0Bi4bbzW3AwrpbluqtcyMN3Pivqdxx+zKWKiORJqqLIvN8CT1fVPxxXb/e9GOdaR8eXSmB0PgNUhM4IjgNkwBbvWC9F/lzvwjlQgciR7d4GfXPYsE1vf8tmdQaY8/PtdAkExmbrb9MihdggSoGXlELrPA91Yce+fiRcKY3rQlNWVd4DOoJ/cPXsXwry8pWjNCo5JD8Q+RQ5yZEy7YPoifwemLhTdsBz3hlZr28oCGJ3kbnpW0xGvQb3VHSTVVbeei0CfXoW6iz1\"]}]}")

(defn jwks [request options]
  (let [public-key (jwtk/public-key (:public-key-path options))
        modulus (.getModulus public-key)
        exp (.getPublicExponent public-key)
        thum (.hashCode public-key)
        raw (apply str (mapv char (Base64/encodeBase64 (.encode public-key))))
        e (encode-bigint exp)
        n (encode-bigint modulus)
        x5t (apply str (map char (Base64/encodeBase64 (->> thum str (map byte) byte-array))))]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/write-str {:keys [{:kty "RSA" :use "sig" :e e :n n :x5t x5t :x5c (str [raw])}]})}))



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
      (cond
        (= (str (:path merged-options) "/connect/authorize") (:uri request)) (authorize request merged-options)
        (= (str (:path merged-options) "/.well-known/jwks") (:uri request)) (jwks request merged-options)
        (= (str (:path merged-options) "/.well-known/openid-configuration") (:uri request)) (discovery request merged-options)
        :otherwise (handler request)))))

(defrecord MockOpenIdServer [handler]
  component/Lifecycle
  (start [this]
    (let [mock-config (-> this :config :value :openid-mock)
          fallback-hook (.get-fallback-hook handler)]
      (.set-fallback-hook! handler (comp fallback-hook
                                     (partial wrap-openid-mock mock-config)))
      this))
  (stop [this] this))

