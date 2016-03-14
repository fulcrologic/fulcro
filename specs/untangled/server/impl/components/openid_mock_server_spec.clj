(ns untangled.server.impl.components.openid-mock-server-spec
  (:require
    [untangled-spec.core :refer [component specification behavior provided assertions]]
    [untangled.server.impl.components.openid-mock-server :as mock]
    [clj-jwt.core :refer :all :exclusion [record?]]
    [clj-jwt.key :refer [private-key public-key]]
    [ring.mock.request :as rm]
    [ring.middleware.params :as params]
    [clj-jwt.core :as jwtc]
    [clojure.data.json :as json]
    [hickory.core :as hc]
    [hickory.zip :as hz]
    [clojure.zip :as zip]))

(def rsa-prv-key (private-key "mock-keys/host.key" "pass phrase"))
(def rsa-pub-key (public-key "mock-keys/server.crt"))


(def options {:path          "openid"
              :response-mode :post})

(def access-token-sample "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsIng1dCI6ImEzck1VZ01Gdjl0UGNsTGE2eUYzekFrZnF1RSIsImtpZCI6ImEzck1VZ01Gdjl0UGNsTGE2eUYzekFrZnF1RSJ9.eyJjbGllbnRfaWQiOiJtdmM2Iiwic2NvcGUiOlsib3BlbmlkIiwicHJvZmlsZSIsImVtYWlsIiwiYXBpMSJdLCJzdWIiOiI4MTg3MjciLCJhbXIiOlsicGFzc3dvcmQiXSwiYXV0aF90aW1lIjoxNDQ3NjE3NTM4LCJpZHAiOiJpZHNydiIsInJvbGUiOlsiQWRtaW4iLCJHZWVrIl0sImlzcyI6Imh0dHBzOi8vbG9jYWxob3N0OjQ0MzAwIiwiYXVkIjoiaHR0cHM6Ly9sb2NhbGhvc3Q6NDQzMDAvcmVzb3VyY2VzIiwiZXhwIjoxNDQ3NjIxMTU1LCJuYmYiOjE0NDc2MTc1NTV9.jtbjTyE_-HhFWOR8t18d8YkMlrtbiNMi-nTACF_ucHB_wvKkF9Ap4dRoXuFcfl6JwelxsT5DmS5DHbWofr24-ptiOkt0YTyEj6RkV9jeTry7Vi4Rl15y9aTXgc5y2a4Q37kneQL9iZzJ-pSsIFWPu-y5sNWA1i7IvlJI4yB6sgWvfunYfVD3wdOIvMA5cOlU29-kIhYpLLSmjD0ELNNrUOJHRymiJLOQHogAqV_inlloP_EXY_pRD_ghGhLzVio8DX5Z3aqFZk8ZjLcQZu8T6V8goKXC9JamzaSe0m4_Kb7fng-KVmqpIHfRNTRZ7Jpeu8gGjqX2TVE__wQ4FjP0Qg")
(def id-token-sample "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiIsIng1dCI6ImEzck1VZ01Gdjl0UGNsTGE2eUYzekFrZnF1RSIsImtpZCI6ImEzck1VZ01Gdjl0UGNsTGE2eUYzekFrZnF1RSJ9.eyJub25jZSI6IjYzNTgzMjE0MjcyNDUzMTAwMC5abVpoTVRNNU1qY3RNamMxTlMwME16UmpMV0ptTlRZdFpURmpaRGMyTXpnNE1XUmlPRFZtTnpNeVptSXROemN3WWkwMFpXTTBMVGhqWldJdE1HTXdaV1V3Wm1FeVpqZzQiLCJpYXQiOjE0NDc2MTc1NzAsImF0X2hhc2giOiJ1b3JCNHhaNGlOZXAzU01LRUlFOGxRIiwic3ViIjoiODE4NzI3IiwiYW1yIjpbInBhc3N3b3JkIl0sImF1dGhfdGltZSI6MTQ0NzYxNzUzOCwiaWRwIjoiaWRzcnYiLCJuYW1lIjoiQWxpY2UgU21pdGgiLCJnaXZlbl9uYW1lIjoiQWxpY2UiLCJmYW1pbHlfbmFtZSI6IlNtaXRoIiwiZW1haWwiOiJBbGljZVNtaXRoQGVtYWlsLmNvbSIsImVtYWlsX3ZlcmlmaWVkIjp0cnVlLCJpc3MiOiJodHRwczovL2xvY2FsaG9zdDo0NDMwMCIsImF1ZCI6Im12YzYiLCJleHAiOjE0NDc2MTc4NzAsIm5iZiI6MTQ0NzYxNzU3MH0.oOKy06Yxy_ufYAKMoso6wcE-D47ZDihNA937qI_nsjWAFToia7TL7Um23lcN4PmuXGTbrKYCgu9sa1URgGuOISd8XkgoUbMfOsc11OMRLI308QyVid9loUDZ6cwEhYzbZQbwhJqhTvf7JMh-fVKkzt9Ye9o-At3BjOhelBQBxOhSCRSXoUFm_tS5P7Uj9SsVYNKSLdeIYXLKwBZNRSlbEPrzeTwjYP22JphlrtqndF4qbMBW2DMgCjsX2vniQcCLRI6D_5nx7UR0e4U1aKa3R0dbqEYB0iQVvDj5VnFMsJbOGHlHKQsetrCKpWpAS_Qgl61WmMCx1uFa3hwnqEZYnw")



(def handler (mock/wrap-openid-mock options {}))

(specification "Open Id Authorize"
  (let [query {"response_type" "id_token token"
               "client_id"     "s6BhdRkqt3"
               "redirect_uri"  "https://client.example.org/cb"
               "scope"         "openid profile api1"
               "state"         "af0ifjsldkj"
               "nonce"         "n-0S6_WzA2Mj"}
        req (-> (rm/request :get "openid/connect/authorize"))
        hand (params/wrap-params handler)
        resp (-> req (rm/query-string query) hand)
        form-input-elems (:content (-> (hz/hickory-zip (hc/as-hickory (hc/parse (:body resp)))) zip/next zip/next zip/next zip/right zip/next zip/node))
        form-inputs (reduce (fn [acc {:keys [attrs]}]
                              (let [{:keys [name value]} attrs]
                                (assoc acc name value))) {} form-input-elems)
        access-token (-> form-inputs (get "access_token") str->jwt)
        id-token (-> form-inputs (get "id_token") str->jwt)]
    (behavior "responds with an html input form"
      (assertions
        "that contain the correct inputs to submit"
        (keys form-inputs) => '("access_token" "token_type" "id_token" "expires_in" "state")
        "access token header has alg"
        (-> access-token :header :alg) => "RS256"
        "access token header has typ"
        (-> access-token :header :typ) => "JWT"
        "access token has sub"
        (-> access-token :claims :sub) => "123-456"
        "access token has sub"
        (-> access-token :claims :sub) => "123-456"
        "id token has sub"
        (-> id-token :claims :sub) => "123-456"
        "id token has sig"
        (nil? (-> id-token :signature)) => false))))

(specification "Open Id Discovery Document"
  (let [req (-> (rm/request :get "openid/.well-known/openid-configuration"))
        hand (params/wrap-params handler)
        response (-> req hand :body json/read-str)]
    (assertions "contains issuer"
      (-> response (get "issuer")) => "https://localhost:44300"
      "jwks_uri"
      (-> response (get "jwks_uri")) => "https://localhost:44300/openid/.well-known/jwks"
      "authorization_endpoint"
      (-> response (get "authorization_endpoint")) => "https://localhost:44300/openid/connect/authorize"
      "token_endpoint"
      (-> response (get "token_endpoint")) => "https://localhost:44300/openid/token"
      "userinfo_endpoint"
      (-> response (get "userinfo_endpoint")) => "https://localhost:44300/openid/userinfo"
      "end_session_endpoint"
      (-> response (get "end_session_endpoint")) => "https://localhost:44300/openid/endsession"
      "check_session_iframe"
      (-> response (get "check_session_iframe")) => "https://localhost:44300/openid/checksession"
      "revocation_supported"
      (-> response (get "revocation_supported")) => "https://localhost:44300/openid/revocation"
      "scopes_supported"
      (-> response (get "scopes_supported")) => ["role" "openid" "profile" "email" "api1"]
      "claims_supported"
      (-> response (get "claims_supported")) => ["realm" "role" "sub" "name" "family_name" "given_name" "middle_name" "nickname" "preferred_username" "profile" "picture" "website" "gender" "birthdate" "zoneinfo" "locale" "updated_at" "email" "email_verified"]
      "response_types_supported"
      (-> response (get "response_types_supported")) => ["code" "token" "id_token" "id_token token" "code id_token" "code token" "code id_token token"]
      "grant_types_supported"
      (-> response (get "grant_types_supported")) => ["authorization_code" "client_credentials" "password" "refresh_token" "implicit"]
      "subject-types-supported"
      (-> response (get "subject-types-supported")) => ["public"]
      "id_token_signing_alg_values_supported"
      (-> response (get "id_token_signing_alg_values_supported")) => ["RS256"]
      "token_endpoint_auth_methods_supported"
      (-> response (get "token_endpoint_auth_methods_supported")) => ["client_secret_post" "client_secret_basic"])))

(specification "Open Id jwks endpoint"
  (let [req (-> (rm/request :get "openid/.well-known/jwks"))
        hand (params/wrap-params handler)
        response (-> req hand :body json/read-str)
        first-key (first (get response "keys"))]
    (assertions "contains array of keys"
      (-> response (get "keys")) =fn=> vector?
      "contains kty"
      (-> first-key (get "kty")) => "RSA"
      "contains use"
      (-> first-key (get "use")) => "sig"
      "contains n"
      (-> first-key (get "n") count) =fn=> pos?
      "contains e"
      (-> first-key (get "e") count) =fn=> pos?
      "contains x5c"
      (-> first-key (get "x5c") count) =fn=> pos?)))