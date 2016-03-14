(ns untangled.openid-client
  (:require [clojure.string :as s]
            [clojure.walk :as w]))

(defn params []
  (apply str (rest (-> js/window .-location .-hash))))

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
  based on the token claims in the url's hash fragments."
  [app-state]
  (let [tokens (tokens-from-params (params))
        id-claims (-> tokens (get "id_token") parse-claims js->clj w/keywordize-keys)]
    (swap! app-state update-in [:initial-state]
           (fn [m]
             (assoc m
                    :app/header {:current-username (:name id-claims)}
                    :openid/claims id-claims
                    :openid/access-token (get tokens "access_token"))))
    (aset js/window.location "hash" "")))
