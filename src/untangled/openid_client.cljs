(ns untangled.openid-client
  (:require [clojure.string :as s]
            [clojure.walk :as w])
  (:import goog.net.Cookies))

(defn params []
  (apply str (rest (-> js/window .-location .-hash))))

(defn get-tokens-from-cookies []
  (let [cookies     (Cookies. js/document)
        cookie-keys (.getKeys cookies)]
    (reduce
      #(assoc %1 %2 (.get cookies %2))
      {}
      cookie-keys)))

(defn tokens-from-params [params]
  (apply merge
         (map (fn [v]
                (let [pairs (s/split v #"=")]
                  {(first pairs) (second pairs)}))
              (s/split params #"&"))))

(defn parse-claims [token]
  (-> token (s/split #"\.") second js/atob js/JSON.parse))

(defn setup
  "Installs openid information into the passed in untangled-client app's initial state,
  based on the token claims in the url's hash fragments.
  Also composes in a request-transform in networking's send function
  to add an Authorization header for each request."
  [app]
  (let [tokens (or
                 (get-tokens-from-cookies)
                 (tokens-from-params (params)))
        id-claims (-> tokens (get "id_token") parse-claims js->clj w/keywordize-keys)]
    (swap! app update-in [:initial-state]
           (fn [m]
             (assoc m
                    :app/header {:current-username (:name id-claims)}
                    :openid/claims id-claims
                    :openid/access-token (get tokens "access_token"))))
    (swap! app update-in [:networking :request-transform]
           #(comp (or % identity)
                  (fn [req]
                      (let [access-token (:openid/access-token @(:reconciler @app))]
                        (assoc-in req [:headers "Authorization"] (str "Bearer " access-token))))))
    (aset js/window.location "hash" "")))
