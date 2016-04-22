(ns untangled.server.impl.components.access-token-handler-spec
  (:require [clojure.test :refer :all]
            [clj-jwt.core :refer :all]
            [clj-jwt.key :refer [private-key public-key]]
            [clj-time.core :refer [now plus days minus]]
            [ring.mock.request :refer [request]]
            [untangled-spec.core :refer [specification behavior provided component assertions]]
            [untangled.server.impl.components.access-token-handler :refer :all]))

(def claim
  {:iss "foobar"
   :sub "1"
   :exp (plus (now) (days 1))
   :aud ["http://webapp.com/rest/v1", "http://webapp.com/rest/v2"]
   :iat (now)})

(def claim-invalid-expired
  {:iss "foobar"
   :sub "1"
   :exp (minus (now) (days 1))
   :aud "http://webapp.com/rest/v1"
   :iat (now)})

(def claim-invalid-issuer
  {:iss "invalid"
   :sub "1"
   :exp (plus (now) (days 1))
   :aud "http://webapp.com/rest/v1"
   :iat (now)})

(def claim-invalid-audience
  {:iss "foobar"
   :sub "1"
   :exp (plus (now) (days 1))
   :aud "invalid"
   :iat (now)})

(def claim-missing-sub
  {:iss "foobar"
   :exp (plus (now) (days 1))
   :aud "invalid"
   :iat (now)})

(def rsa-prv-key (private-key "specs/resources/rsa/private.key" "pass phrase"))
(def rsa-pub-key (public-key "specs/resources/rsa/public.key"))



(defn build-test-token [claim]                              ;; RS256 signed JWT
  (-> claim jwt (sign :RS256 rsa-prv-key) to-str))

(defn build-test-bearer [token]
  (apply str (concat "Bearer " token)))

(defn build-test-header [claim]
  (let [bearer (build-test-bearer (build-test-token claim))]
    {"authorization" bearer}))

(def options {:issuer               "foobar"
              :public-keys          [rsa-pub-key]
              :audience             "http://webapp.com/rest/v1"
              :authorized-routes    #{"/"}
              :grace-period-minutes 1})

(def handler (wrap-access-token options (fn [resp] resp)))

(specification "wrap-access-token fn"
  (assertions
    "Adds claims to request when token is valid"
    (-> (request :get "/") (assoc :headers (build-test-header claim)) handler) =fn=> :user
    "Does not add claims to request that is missing access token"
    (-> (request :get "/") (assoc :headers {}) handler) =fn=>  #(not (:user %))
    "Does not add claims to request that has expired access token"
    (-> (request :get "/") (assoc :headers (build-test-header claim-invalid-expired)) handler) =fn=> #(not (:user %))
    "Does not add claims to request that has invalid issuer"
    (-> (request :get "/") (assoc :headers (build-test-header claim-invalid-issuer)) handler) =fn=> #(not (:user %))
    "Does not add claims to request that has invalid audience"
    (-> (request :get "/") (assoc :headers (build-test-header claim-invalid-audience)) handler) =fn=> #(not (:user %))
    "Does not add claims to request that is missing the subject"
    (-> (request :get "/") (assoc :headers (build-test-header claim-missing-sub)) handler) =fn=> #(not (:user %))))

