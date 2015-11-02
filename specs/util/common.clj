(ns util.common
  (:require
    [ring.mock.request :as m]
    [clojure.data.json :as json]
    )
  )

(defn json-post
  "
  Creates a mock request object as IF a POST had been done with JSON that has ALREADY been decoded to the EDN
  you supply.

  Parameters:
  * `uri` : The URI
  * `edn` : A clojure map of key/value pairs to pretend is the 'decoded' JSON.

  Returns a mock/request map with :uri and :body-params set.
  "
  ;[uri edn]
  ;(-> (m/request :post uri)
       ;(assoc :body-params edn))
  [path body]
  (let [request (-> (m/request
                   :post
                   path (clojure.data.json/write-str body))
                     )
        ]
    request
    )
  )

(defn extract-json [response]
  (-> response
      :body
      (slurp)
      (json/read-json)
      )
  )

(defn get-json
  "Run a GET against a Ring handler that is supposed to return a JSON response.
  Throws AssertionError if the response code is not 200, or the outbound content-type is not JSON."
  [handler path]
  (let [response (-> (m/request :get path)
                     (m/header "Accept" "application/json")
                     (m/content-type "application/json")
                     (handler)
                     )
        data (extract-json response)
        ]
    (assert (= 200 (:status response)) "GET did NOT return a 200 status code")
    (assert (-> response :headers (get "Content-Type") (.startsWith "application/json")) "GET did NOT return JSON")
    data
    )
  )

(defn get-response [handler path]
  (-> (m/request :get path)
      (m/header "Accept" "application/json")
      (m/content-type "application/json")
      (handler)
      )
  )
