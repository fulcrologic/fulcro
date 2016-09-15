(ns untangled.openid-client
  (:require [clojure.string :as s]
            [clojure.walk :as w]
            [om.next :as om])
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
  (some-> token (s/split #"\.") second js/atob js/JSON.parse))

(defn add-auth-header
  "Adds an Authorization header for each request based on the claims in the cookies or the url's hash fragments"
  [req]
  (let [access-token (-> (or (get-tokens-from-cookies)
                             (tokens-from-params (params)))
                       (get "access_token"))]
    (assoc-in req [:headers "Authorization"] (str "Bearer " access-token))))

(defn install-state!
  "Installs openid information into the passed in untangled-client app's initial state,
  based on the token claims in the cookies or the url's hash fragments."
  [reconciler & {:keys [custom-state-fn] :or {custom-state-fn (constantly {})}}]
  (let [hash-tokens (tokens-from-params (params))
        tokens (or (get-tokens-from-cookies)
                   hash-tokens)
        id-claims (some-> tokens (get "id_token") parse-claims js->clj w/keywordize-keys)]
    (when (= tokens hash-tokens)
      ;ie: dont always clear the hash, as it might be used by routing
      (aset js/window.location "hash" ""))
    (swap! (om/app-state reconciler) merge
      (merge
        (custom-state-fn id-claims)
        {:openid/claims id-claims
         :openid/access-token (get tokens "access_token")}))))
