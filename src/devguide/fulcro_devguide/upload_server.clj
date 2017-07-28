(ns fulcro-devguide.upload-server
  (:require [taoensso.timbre :as timbre]
            [om.next.impl.parser :as op]
            [fulcro.server :as server]
            [fulcro.easy-server :as easy]
            [om.next :as om]
            om.next.server
            [ring.util.request :as ru]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.not-modified :refer [wrap-not-modified]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [org.httpkit.server :refer [run-server]]
            [clojure.tools.namespace.repl :refer [disable-reload! refresh clear set-refresh-dirs]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [fulcro.ui.file-upload :as upload]
            [clojure.string :as str]
            [ring.util.response :as resp]
            [cognitect.transit :as transit])
  (:import (java.io File)))

; A Test server for trying out file upload

(def http-kit-opts
  [:ip :port :thread :worker-name-prefix
   :queue-size :max-body :max-line])

(defrecord WebServer [port handler server]
  component/Lifecycle
  (start [this]
    (try
      (let [server-opts    (select-keys (-> this :config :value) http-kit-opts)
            port           (:port server-opts)
            started-server (run-server (:middleware handler) server-opts)]
        (timbre/info "Web server started successfully. With options:" server-opts)
        (assoc this :port port :server started-server))
      (catch Exception e
        (timbre/fatal "Failed to start web server " e)
        (throw e))))
  (stop [this]
    (if-not server this
                   (do (server)
                       (timbre/info "web server stopped.")
                       (assoc this :server nil)))))

(defn make-web-server
  "Builds a web server with an optional argument that
   specifies which component to get `:middleware` from,
   defaults to `:handler`."
  [& [handler]]
  (component/using
    (component/using
      (map->WebServer {})
      [:config])
    {:handler (or handler :handler)}))

(defn logging-mutate [env k params]
  (timbre/info "Mutation Request: " k)
  {})

(defn logging-query [{:keys [ast] :as env} k params]
  (timbre/info "Query: " (op/ast->expr ast))
  {})

; This is both a server module AND hooks into the Om parser for the incoming /api read/mutate requests. The
; modular server support lets you chain as many of these together as you want, allowing you to define
; reusable Om server components.
(defrecord ApiHandler []
  server/Module
  (system-key [this] ::api)
  (components [this] {})
  server/APIHandler
  (api-read [this] logging-query)
  (api-mutate [this] logging-mutate))

(defn build-api-handler [& [deps]]
  "`deps`: Vector of keys passed as arguments to be
  included as dependecies in `env`."
  (component/using
    (map->ApiHandler {}) deps))

(defn MIDDLEWARE [handler component]
  ((get component :middleware) handler))

(defn not-found [req]
  {:status  404
   :headers {"Content-Type" "text/plain"}
   :body    "Resource not found."})

(defrecord CustomMiddleware [middleware api-handler upload]
  component/Lifecycle
  (stop [this] (dissoc this :middleware))
  (start [this]
    (assoc this :middleware
                (-> not-found
                  (MIDDLEWARE api-handler)
                  (upload/wrap-file-upload upload)
                  ;; TRANSIT
                  server/wrap-transit-params
                  server/wrap-transit-response
                  ;; RESOURCES
                  (wrap-resource "public")
                  ;; HELPERS
                  wrap-content-type
                  wrap-not-modified
                  wrap-params
                  wrap-multipart-params
                  wrap-gzip))))

; IMPORTANT: You want to inject the built-in API handler (which is where modular API handlers get chained)
(defn build-middleware []
  (component/using
    (map->CustomMiddleware {})
    {:upload      :upload
     :api-handler ::server/api-handler}))

(defrecord PretendFileUpload []
  component/Lifecycle
  (start [this] this)
  (stop [this] this)
  upload/IFileUpload
  (upload-prefix [this] "/file-upload")
  (is-allowed? [this request] true)
  (store [this file] (timbre/info "Pretending to save file with ID real 42") 42)
  (retrieve [this id] nil)
  (delete [this id] nil))

(defn make-system [config-path]
  (server/fulcro-system
    {:components {:config      (server/new-config config-path)
                  ::middleware (build-middleware)
                  :upload      (map->PretendFileUpload {})
                  :web-server  (easy/make-web-server ::middleware)}
     :modules    [(build-api-handler [])]}))

(defonce system (atom nil))

(defn go
  "Load the overall web server system and start it."
  []
  (reset! system (make-system "config/upload-server.edn"))
  (swap! system component/start))


