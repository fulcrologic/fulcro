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
    "x5c" ["MIIDfDCCAmSgAwIBAgIJAOirkGtL6OOYMA0GCSqGSIb3DQEBBQUAMDIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpTb21lLVN0YXRlMQ4wDAYDVQQKEwVOYXZpczAeFw0xNjAzMTExODI1MjlaFw0xNjA0MTAxODI1MjlaMDIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpTb21lLVN0YXRlMQ4wDAYDVQQKEwVOYXZpczCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMfy0WwJCbuUGpGs7zKz0h6ylWlUQi9xum5Q/s5nID5mKedG4Mbt+AKmGnnG7ufBDn9ECY54us87O9u9hNr2Jcn5ZFTgcE4WvXG4oO/YxrbylDQzPSKCF7gymA6pXB29nt7MSVxqTRA+t4+B8kl51PlKmEB5uTq1Na/zgM3lsr3oP7FItLlDBf7Xp2s8hfP5dIfXX4jguIag9HUvLQ5CE2vEifaD++fabb/gl3Tgw1EX2TOyzaKjYYZfOnSNF9BgUv9203/F2PrXtxLOmDZufHp1bifzOSazylhL2aHkb1vCwDJcWsh9mCcCuBCopZJbgcV8KsrwLIBJHWVBJnLsW+cCAwEAAaOBlDCBkTAdBgNVHQ4EFgQUIOFPEXEGu9xYPkc8gdLgMoGxvOQwYgYDVR0jBFswWYAUIOFPEXEGu9xYPkc8gdLgMoGxvOShNqQ0MDIxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpTb21lLVN0YXRlMQ4wDAYDVQQKEwVOYXZpc4IJAOirkGtL6OOYMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEFBQADggEBAC4ocYX7FaWtlkNz2ZzPMsMjFx2ECukYOKqEIXLNUhZ/MFNnWWHFl2zIy9iP+mrO20hRDx6VCfhgocXcPVqX3rTPUyySdbRfgTGRXsDwQjsIdzYB6mDr3jB/Qh4wws+drILxGE2nuPojKyiG4vRG49TTa5hFiIebEN2OKDiqRGKSNDY/Cvm87OFk90CgtICYHS2LeZiJIZ+t30YT7EADJk7sXRFGqh9TvL8uAbblKbbBeZhZDKqi8Z2EpVGnP9E7JShvkOD47UwBwe7DJZ4WdrdOzMcRUb+KYlZ2v3PW66+oBcu3ZP/kFbim9OXmuCUmP+IZCwqu7Cqr7Q5BlMRq6mw="]}])

(defn build-test-token [claim]                              ;; RS256 signed JWT
  (-> claim jwt (sign :RS256 rsa-prv-key)))

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

