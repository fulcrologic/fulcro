(ns fulcro.server
  (:require-macros fulcro.server)
  (:require
    [fulcro.client.network :as net]
    [fulcro.util :as util]
    [fulcro.client.logging :as log]
    [fulcro.client.primitives :as prim]))

;; This namespace is useful for building mock servers in cljs demos

(defmulti read-entity
  "The multimethod for Fulcro's built-in support for reading an entity."
  (fn [env entity-type id params] entity-type))
(defmulti read-root
  "The multimethod for Fulcro's built-in support for querying with a keyword "
  (fn [env keyword params] keyword))

(defn server-read
  "A built-in read method for Fulcro's built-in server parser."
  [env k params]
  (let [k (-> env :ast :key)]
    (if (util/ident? k)
      (read-entity env (first k) (second k) params)
      (read-root env k params))))

(defmulti server-mutate prim/dispatch)

(defn fulcro-parser
  "Builds and returns a parser that uses Fulcro's query and mutation handling. See `defquery-entity`, `defquery-root`,
  and `defmutation` in the `fulcro.server` namespace."
  []
  (prim/parser {:read server-read :mutate server-mutate}))

(defn generate-response
  "Generate a Fulcro-compatible response containing at least a status code, headers, and body. You should
  pre-populate at least the body of the input-response.
  The content type of the returned response will always be pegged to 'application/transit+json'."
  [{:keys [status body headers] :or {status 200} :as input-response}]
  (-> (assoc input-response :status status :body body)
    (update :headers assoc "Content-Type" "application/transit+json")))

(defn raise-response [resp]
  (reduce (fn [acc [k v]]
            (if (and (symbol? k) (not (nil? (:result v))))
              (assoc acc k (:result v))
              (assoc acc k v)))
    {} resp))

(defn valid-response? [result]
  (and
    (not (contains? result ::exception))
    (not (some (fn [[_ {:keys [fulcro.client.primitives/error]}]] (some? error)) result))))

(defn augment-map [response]
  (->> (keep #(some-> (second %) meta :fulcro.server/augment-response) response)
    (reduce (fn [response f] (f response)) {})))

(defn process-errors [error] {:status 500 :body error})

(defn handle-api-request [parser env query]
  (generate-response
    (let [parse-result (try
                         (raise-response (parser env query))
                         (catch :default e {::exception e}))]
      (if (valid-response? parse-result)
        (merge {:status 200 :body parse-result} (augment-map parse-result))
        (process-errors parse-result)))))

(defrecord ServerEmulator [parser]
  net/FulcroNetwork
  (send [this edn done-callback error-callback]
    (let [{:keys [body status] :as response} (handle-api-request parser {} edn)]
      (if (= 200 status)
        (done-callback body)
        (do
          (log/error "Server responded with an error" response)
          (error-callback body)))))
  (start [this]))

(defn new-server-emulator
  "Create a server emulator that can be installed as client-side networking. If you do not supply a parser,
  then it will create one that works with the normal server-side macros."
  ([] (ServerEmulator. (fulcro-parser)))
  ([parser] (ServerEmulator. parser)))
