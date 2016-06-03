(ns untangled.server.impl.components.access-token-handler-spec
  (:require [clojure.test :as t]
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

(def options {:issuer               "foobar"
              :public-keys          [rsa-pub-key]
              :audience             "http://webapp.com/rest/v1"
              :unsecured-routes     {"/unsafe"        :ok
                                     ["/unsafe/" :id] :ok
                                     "/js" {true :ok}}
              :grace-period-minutes 1})

(def handler (wrap-access-token options (fn [resp] resp)))

(defn test-claim [claim & [path]]
  (let [headers (cond-> claim (seq claim) build-test-header)]
    (-> (request :get (or path "/"))
      (assoc :headers headers)
      handler)))

(def handler-test (wrap-access-token options (fn [_] :ok)))

(defn test-handler [claim & [path]]
  (let [headers (cond-> claim (seq claim) build-test-header)]
    (-> (request :get (or path "/"))
      (assoc :headers headers)
      handler-test)))

(defn unauthorized? [{:keys [user status]}]
  (and (not user) (= status 401)))

(specification "wrap-access-token"
  (assertions
    "Adds claims to request when token is valid"
    (test-claim claim) =fn=> :user
    "Does not add claims to request that is missing access token"
    (test-claim {}) =fn=> unauthorized?
    "Does not add claims to request that has expired access token"
    (test-claim claim-invalid-expired) =fn=> unauthorized?
    "Does not add claims to request that has invalid issuer"
    (test-claim claim-invalid-issuer) =fn=> unauthorized?
    "Does not add claims to request that has invalid audience"
    (test-claim claim-invalid-audience) =fn=> unauthorized?
    "Does not add claims to request that is missing the subject"
    (test-claim claim-missing-sub) =fn=> unauthorized?
    "Sub can 'fallback' to client-id"
    (test-claim claim-missing-sub-with-client-id) =fn=> :user)
  (assertions
    "does not add claims if its an :unsecured-routes"
    (test-claim claim "/unsafe") =fn=> (comp not :user)
    (test-claim claim "/unsafe/13") =fn=> (comp not :user)
    (test-claim claim "/unsafe/or/not!") =fn=> :user
    "we can unsecure a whole folder (eg: /js)"
    (test-claim claim "/js/bar/baz") =fn=> (comp not :user)
    "top level files are unsecured"
    (test-claim claim "/some-file.fake") =fn=> (comp not :user)
    "nested files are by default secured"
    (test-claim claim "/foo/some-file.fake") =fn=> :user)
  (assertions
    "calls the passed in handler"
    (test-handler claim "/does/not/matter") => :ok))

(specification "validate-unsecured-route-handlers!"
  (assertions
    (validate-unsecured-route-handlers!
      {:foo :ok}) => true
    (validate-unsecured-route-handlers!
      {:foo :not/ok})
    =throws=> (AssertionError #"handler was not :ok")
    (validate-unsecured-route-handlers!
      {:foo {:bar :not/ok}})
    =throws=> (AssertionError #"handler was not :ok")))
