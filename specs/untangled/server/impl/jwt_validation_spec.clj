(ns untangled.server.impl.jwt-validation-spec
  (:require  [clojure.test :refer :all]
             [clj-jwt.core  :refer :all]
             [clj-jwt.key   :refer [private-key public-key]]
             [clj-time.core :refer [now plus days minus]]
             [untangled-spec.core :refer [specification behavior provided component assertions]]
             [untangled.server.impl.jwt-validation :refer :all]))

(def claim
  {:iss "foobar"
   :exp (plus (now) (days 1))
   :aud ["http://webapp.com/rest/v1", "http://webapp.com/rest/v2"]
   :iat (now)})

(def claim2
  {:iss "foobar"
   :exp (minus (now) (days 1))
   :aud "http://webapp.com/rest/v1"
   :iat (now)})

(def rsa-prv-key (private-key "specs/resources/rsa/private.key" "pass phrase"))
(def rsa-pub-key (public-key  "specs/resources/rsa/public.key"))
(def rsa-pub-key2 (public-key  "specs/resources/rsa/public2.key"))

(def pub-keys-jwks
  [{"kty" "RSA",
    "use" "sig",
    "e"   "AQAB",
    "n"   "AMAVjf2HHQqSKhVK3nx9DiaiNB5rZchukbHpAAkPvvdulmDmPRbH5hQvU0o_SCKm9mTwWvRPXTUdj2r1OQs7OMUw3Rro4P91uKdoaxkPPUwtGTvbODfcOgCBz210Nsk5wIGcIO-0K086CMSpwvCeBFKAysVltZFMGvZoG4X2eu-ly2tOegzoX--FFePknycT9pmbylngKEyswHFAr5WsNNcmGIUL6mT2wvmItfypWg03FOKSb83XlBBwuVyIxvUQ-9NvILNMd35492wQ2dNRLdwWtJw0nZ1dD18y9ONbBzDPlHrfj7y0FCquNiYl2MrofZZ1Wm6cHAZH15sXsjHPa7c",
    "x5t" "MTIyOTI4Ng==",
    "x5c" "[\"MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwBWN/YcdCpIqFUrefH0OJqI0HmtlyG6RsekACQ++926WYOY9FsfmFC9TSj9IIqb2ZPBa9E9dNR2PavU5Czs4xTDdGujg/3W4p2hrGQ89TC0ZO9s4N9w6AIHPbXQ2yTnAgZwg77QrTzoIxKnC8J4EUoDKxWW1kUwa9mgbhfZ676XLa056DOhf74UV4+SfJxP2mZvKWeAoTKzAcUCvlaw01yYYhQvqZPbC+Yi1/KlaDTcU4pJvzdeUEHC5XIjG9RD7028gs0x3fnj3bBDZ01Et3Ba0nDSdnV0PXzL041sHMM+Uet+PvLQUKq42JiXYyuh9lnVabpwcBkfXmxeyMc9rtwIDAQAB\"]"}
   {"kty" "RSA",
    "use" "sig",
    "e" "AQAB",
    "n" "AMAVjf2HHQqSKhVK3nx9DiaiNB5rZchukbHpAAkPvvdulmDmPRbH5hQvU0o_SCKm9mTwWvRPXTUdj2r1OQs7OMUw3Rro4P91uKdoaxkPPUwtGTvbODfcOgCBz210Nsk5wIGcIO-0K086CMSpwvCeBFKAysVltZFMGvZoG4X2eu-ly2tOegzoX--FFePknycT9pmbylngKEyswHFAr5WsNNcmGIUL6mT2wvmItfypWg03FOKSb83XlBBwuVyIxvUQ-9NvILNMd35492wQ2dNRLdwWtJw0nZ1dD18y9ONbBzDPlHrfj7y0FCquNiYl2MrofZZ1Wm6cHAZH15sXsjHPa7c",
    "x5t" "MTIyOTI4Ng==",
    "x5c" "[\"MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwBWN/YcdCpIqFUrefH0OJqI0HmtlyG6RsekACQ++926WYOY9FsfmFC9TSj9IIqb2ZPBa9E9dNR2PavU5Czs4xTDdGujg/3W4p2hrGQ89TC0ZO9s4N9w6AIHPbXQ2yTnAgZwg77QrTzoIxKnC8J4EUoDKxWW1kUwa9mgbhfZ676XLa056DOhf74UV4+SfJxP2mZvKWeAoTKzAcUCvlaw01yYYhQvqZPbC+Yi1/KlaDTcU4pJvzdeUEHC5XIjG9RD7028gs0x3fnj3bBDZ01Et3Ba0nDSdnV0PXzL041sHMM+Uet+PvLQUKq42JiXYyuh9lnVabpwcBkfXmxeyMc9rtwIDAQAB\"]"}])

(defn build-test-token [claim]  ;; RS256 signed JWT
  (-> claim jwt (sign :RS256 rsa-prv-key)) )

(def test-token (build-test-token claim))
(def test-token2 (build-test-token claim2))

(specification "testing Public keys from JWKS"
  (assertions
    "transforms list of JSON Web Keys into a list of key objects"
    (public-keys-from-jwks pub-keys-jwks) =fn=> #(every?
                                                  (fn [i] (= sun.security.rsa.RSAPublicKeyImpl (type i)))
                                                  %)))

(specification "token-validation-tests"
  (assertions
    "Must accept valid token signature"
    (valid-signature? test-token [rsa-pub-key]) => true

    ; Validate with a different key than used to sign the token
    "Must reject invalid token signature"
    (valid-signature? test-token [rsa-pub-key2]) => false

    "Must attempt to validate token using all keys"
    (valid-signature? test-token [rsa-pub-key2 rsa-pub-key]) => true
    (valid-signature? test-token [rsa-pub-key rsa-pub-key2]) => true
    (valid-signature? test-token [rsa-pub-key2 rsa-pub-key2]) => false

    "Must accept a valid issuer"
    (valid-issuer? test-token "foobar") => true

    "Must reject an invalid issuer"
    (valid-issuer? test-token "barfoo") => false

    "Must accept a valid audience that is in a list of audiences in the token"
    (valid-audience? test-token "http://webapp.com/rest/v1") => true

    "Must reject an invalid audience that is in a list of audiences in the token"
    (valid-audience? test-token "http://webapp.com/rest/v3") => false

    "Must accept a valid audience where the token contains a single audience"
    (valid-audience? test-token2 "http://webapp.com/rest/v1") => true

    "Must reject an invalid audience where the token contains a single audience"
    (valid-audience? test-token2 "http://webapp.com/rest/v2") => false

    "Must accept a token that has not expired with one minute grace period"
    (valid-expire? test-token 1) => true

    "Must reject a token that has expired with one minute grace period"
    (valid-expire? test-token2 1) => false
    ))

