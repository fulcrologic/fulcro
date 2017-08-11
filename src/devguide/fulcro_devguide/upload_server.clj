(ns fulcro-devguide.upload-server
  (:require [taoensso.timbre :as timbre]
            [om.next.impl.parser :as op]
            [fulcro.server :as server :refer [defmutation defquery-root defquery-entity]]
            [fulcro.easy-server :as easy]
            [om.next :as om]
            om.next.server
            [ring.util.io :as ring-io]
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
  (:import (java.io File FileInputStream)))

; A Test server for trying out file upload

(def image-database (atom {}))

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

; This is both a server module AND hooks into the Om parser for the incoming /api read/mutate requests. The
; modular server support lets you chain as many of these together as you want, allowing you to define
; reusable Om server components.
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

(defrecord ApiHandler []
  server/Module
  (system-key [this] ::api)
  (components [this] {})
  server/APIHandler
  (api-read [this] server/server-read)
  (api-mutate [this] server/server-mutate))

(defn build-api-handler [& [deps]]
  "`deps`: Vector of keys passed as arguments to be
  included as dependecies in `env`."
  (component/using
    (map->ApiHandler {}) deps))

(defn MIDDLEWARE [handler component]
  ((get component :middleware) handler))

; NOTE: The pretend store is using temp disk files that will disappear after a time.
(defn not-found [req]
  {:status  404
   :headers {"Content-Type" "text/plain"}
   :body    "Resource not found."})

(defn wrap-image-library
  "Handle requests at /image-library/ID by serving images from the PretendFileUpload store. Of course this would be
  better if it served resized images based on query params...but for a demo it's fine. Ideally, this function would
  also support not-modified headers to allow browsers to cache the images."
  [handler upload]
  (let [prefix            "/image-library"
        is-file-download? (fn [req] (str/starts-with? (ru/path-info req) prefix))]
    (fn [r]
      (if (is-file-download? r)
        (let [uri (ru/path-info r)
              id  (-> uri (str/split #"/") last Integer/parseInt)
              {:keys [tempfile content-type size]} (get @image-database id)]
          (timbre/info "Request for image with ID " id ". Found " tempfile " with content type " content-type)
          (if tempfile
            (-> (resp/response (new FileInputStream tempfile))
              (resp/content-type content-type))
            (not-found r)))
        (handler r)))))

; IMPORTANT: You want to inject the built-in API handler (which is where modular API handlers get chained)
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
                  ;; Uploaded Image server
                  (wrap-image-library upload)
                  ;; HELPERS
                  wrap-content-type
                  wrap-not-modified
                  wrap-params
                  wrap-multipart-params
                  wrap-gzip))))

(defn build-middleware []
  (component/using
    (map->CustomMiddleware {})
    {:upload      :upload
     :api-handler ::server/api-handler}))

(def last-image-id (atom 0))
(defn next-image-id [] (swap! last-image-id inc))

(defn save-image
  "Save an image in the in-memory database. Returns the ID under which it was stored"
  [{:keys [tempfile filename content-type size] :as filedesc}]
  (let [id (next-image-id)]
    (timbre/info "File to save: " filename " - stored in java.io.File " tempfile " with size " size " and content-type" content-type)
    ; If you're using form submission, you can move the tempfile then. If this is the end of the line, then you should
    ; move the image from the tempfile to a more permanent store.
    (swap! image-database assoc id filedesc)
    id))

(defn get-file
  ^java.io.File [id]
  ; Give back the file. This will be used by form submission processing.
  (get-in @image-database [id :tempfile]))

(defn delete-file [id] (swap! image-database dissoc id))

(defrecord PretendFileUpload []
  component/Lifecycle
  (start [this] this)
  (stop [this] this)
  upload/IFileUpload
  (upload-prefix [this] "/file-upload")
  (is-allowed? [this request] true)
  (store [this file] (save-image file))
  (retrieve [this id] (get-file id))
  (delete [this id] (delete-file id)))

(defn make-system [config-path]
  (server/fulcro-system
    {:components {:config      (server/new-config config-path)
                  ::middleware (build-middleware)
                  :upload      (map->PretendFileUpload {})
                  :web-server  (easy/make-web-server ::middleware)}
     ; The commit mutation will need to access the upload component in order to deal with the files there.
     :modules    [(build-api-handler [:upload])]}))


(defquery-root :server/image-library
  "Get all of the images in the image library"
  (value [env {:keys [page] :or {page 1}}]
    (if (empty? @image-database)
      []
      (let [all-image-ids  (sort (keys @image-database))
            pages          (partition-all 6 all-image-ids)
            page           (min page (count pages))
            page-of-images (if (pos? page) (nth pages (dec page)) [])
            files          (mapv (fn [id] {:db/id id :image/url (str "/image-library/" id)}) page-of-images)]
        files))))

(defmutation image-library/delete-image
  "Fulcro mutation: Delete a server-side image from the image library by ID."
  [{:keys [id]}]
  (action [env]
    (swap! image-database dissoc id)))

(defonce system (atom nil))

(defn go
  "Load the overall web server system and start it."
  []
  (reset! system (make-system "config/upload-server.edn"))
  (swap! system component/start))

(defn stop "Stop the running web server." []
  (when @system
    (swap! system component/stop)
    (reset! system nil)))

(defn restart
  "Stop the web server, refresh all namespace source code from disk, then restart the web server."
  []
  (stop)
  (refresh :after `go))
