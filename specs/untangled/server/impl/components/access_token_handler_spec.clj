(ns untangled.server.impl.components.access-token-handler-spec
  (:require [clojure.test :as t]
            [clojure.set :as set]
            [clj-jwt.core :refer :all]
            [clj-jwt.key :refer [private-key public-key]]
            [clj-time.core :refer [now plus days minus]]
            [ring.mock.request :refer [request]]
            [untangled-spec.core :refer [specification behavior provided component assertions]]
            [untangled.server.impl.components.access-token-handler :refer :all]
            [taoensso.timbre :as timbre]))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:ns-blacklist ["untangled.server.impl.components.access-token-handler"]}
           (%)))

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

(def claim-missing-sub-with-client-id
  {:iss "foobar"
   :exp (plus (now) (days 1))
   :aud "http://webapp.com/rest/v1"
   :iat (now)
   :client_id "fake-client-id"})

(def rsa-prv-key (private-key "specs/resources/rsa/private.key" "pass phrase"))
(def rsa-pub-key (public-key "specs/resources/rsa/public.key"))

(defn build-test-token [claim]                              ;; RS256 signed JWT
  (-> claim jwt (sign :RS256 rsa-prv-key) to-str))

(defn build-test-bearer [token]
  (apply str (concat "Bearer " token)))

(defn build-test-header [claim]
  (let [bearer (build-test-bearer (build-test-token claim))]
    {"authorization" bearer}))

(defn build-test-query-params [claim]
  {"openid/access-token" (build-test-token claim)})

(defn build-test-form-params [claim]
  {"access_token" (build-test-token claim)})

(defn build-test-cookies [claim]
  {"access_token" {:value (build-test-token claim)}})

(def options {:issuer               "foobar"
              :public-keys          [rsa-pub-key]
              :audience             "http://webapp.com/rest/v1"
              :unsecured-routes     {"/unsafe"        :ok
                                     ["/unsafe/" :id] :ok
                                     "/js"            {true :ok}}
              :grace-period-minutes 1})

(def handler (wrap-access-token options (fn [resp] resp)))

(defn test-claim [req-key gen-fn]
  (fn [claim & [path]]
    (let [req-val (cond-> claim (seq claim) gen-fn)]
      (-> (request :get (or path "/api"))
        (assoc req-key req-val)
        handler))))

(defn test-header-claim [claim & [path]]
  ((test-claim :headers build-test-header) claim path))

(defn test-query-params [claim & [path]]
  ((test-claim :params build-test-query-params) claim path))

(defn test-form-params [claim & [path]]
  ((test-claim :form-params build-test-form-params) claim path))

(defn test-cookies [claim & [path]]
  ((test-claim :cookies build-test-cookies) claim path))

(def dummy-hander-test (wrap-access-token options (fn [_] :ok)))

(defn test-handler [claim & [path]]
  (let [headers (cond-> claim (seq claim) build-test-header)]
    (-> (request :get (or path "/"))
      (assoc :headers headers)
      dummy-hander-test)))

(defn unauthorized? [req] (not (:user req)))

(specification "wrap-access-token"
  (assertions
    "Adds claims to request when token is valid"
    (test-header-claim claim) =fn=> :user
    (test-query-params claim) =fn=> :user
    (test-form-params claim) =fn=> :user
    (test-cookies claim) =fn=> :user
    "Does not add claims to request that is missing access token"
    (test-header-claim {}) =fn=> unauthorized?
    "Does not add claims to request that has expired access token"
    (test-header-claim claim-invalid-expired) =fn=> unauthorized?
    "Does not add claims to request that has invalid issuer"
    (test-header-claim claim-invalid-issuer) =fn=> unauthorized?
    "Does not add claims to request that has invalid audience"
    (test-header-claim claim-invalid-audience) =fn=> unauthorized?
    "Does not add claims to request that is missing the subject"
    (test-header-claim claim-missing-sub) =fn=> unauthorized?
    "Sub can 'fallback' to client-id"
    (test-header-claim claim-missing-sub-with-client-id) =fn=> :user)
  (assertions
    "calls the passed in handler"
    (test-handler claim "/does/not/matter") => :ok))
