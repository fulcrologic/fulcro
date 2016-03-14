(ns untangled.server.impl.jwt-validation
  (:require
    [clj-jwt.core :refer :all]
    [clj-jwt.key :refer [private-key public-key public-key-from-string]]
    [clj-jwt.intdate :refer [joda-time->intdate]]
    [clj-time.core :refer [now plus minutes]]
    [clojure.data.json :as json]))

(defn read-token [str]
  (str->jwt str))

(defn valid-signature? [token pubkeys]
  (loop [pubkey (first pubkeys)
         pubkeys (rest pubkeys)
         valid? (verify token pubkey)]
    (cond
      valid? valid?
      (not (empty? pubkeys)) (recur (first pubkeys) (rest pubkeys) (verify token (first pubkeys)))
      :else false)))

(defn valid-issuer? [token issuer]
  (let [claims (:claims token)]
    (= (:iss claims) issuer)))

(defn valid-audience? [token audience]
  (let [claims (:claims token)
        aud (:aud claims)
        auds (if (vector? aud) aud (vector aud))
        filtered (filter #(= audience %) auds)]
    (> (count filtered) 0)))

(defn valid-expire? [token grace-period-minutes]
  (let [claims (:claims token)
        exp (:exp claims)
        current (joda-time->intdate (plus (now) (minutes grace-period-minutes)))]
    (> exp current)))

(defn public-keys-from-jwks [keys]
  (map (fn [json-web-key]
         (let [x5cert-chain (get json-web-key "x5c")
               key (first x5cert-chain)    ;; key MUST be first cert in the chain, according to JWK spec
               key-str (str "-----BEGIN CERTIFICATE-----\n"
                         key "\n"
                         "-----END CERTIFICATE-----")]
           (public-key-from-string key-str))) keys))

