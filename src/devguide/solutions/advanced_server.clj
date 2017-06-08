(ns solutions.advanced-server
  (:require
    [com.stuartsierra.component :as component]
    [ring.util.response :as ring]
    [taoensso.timbre :as timbre]
    [untangled.easy-server :as h]))

; Exercises 1 and 2 are mainly about the runtime JVM args. There is no real code.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exercise 3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord SampleComponent [config]
  component/Lifecycle
  (start [this]
    (let [my-config (-> config :value :sample)]
      (timbre/info "Configuring sample component with " my-config))
    this)
  (stop [this] this))

(defn make-sample-component []
  (component/using
    (map->SampleComponent {})
    [:config]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exercise 4 - Done completely through environment. No new code needed. You config should contain:
; {:port :env.edn/PORT :sample {:n :env/MESSAGE }}
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exercise 5 (commented out so it doesn't get installed into the multimethod
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#_(defmethod apimutate 'exercise5/trigger [e k p]
    {:action (fn []
               (let [config (-> e :config :value)
                     n (-> config :sample :n)]
                 (timbre/info "Triggered. Config value is " n)))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exercise 6
;; Add to the server with this option:
;;
;; :extra-routes {:routes   ["/sample" :sample]
;;               :handlers {:sample handle-sample}}
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn handle-sample [env match]
  (timbre/info "Route hit " env)
  (let [config (-> env :config :value)
        n (-> config :sample :n)]
    (-> (str "Hello world! Config says: " n)
        ring/response
        (ring/content-type "text/plain"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exercise 7: Add to server with argument :components {:user-hook (make-hook-component)}))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-user [handler]
  (fn [request]
    (handler (assoc request :user {:id "Your Name"}))))

(defrecord HookComponent [handler]
  component/Lifecycle
  (start [this]
    (let [old-pre-hook (h/get-pre-hook handler)]
      (timbre/info "Wrapping User Handler")
      (h/set-pre-hook! handler (comp wrap-user old-pre-hook)))
    this)
  (stop [this] this))

(defn make-hook-component []
  (component/using
    (map->HookComponent {})
    [:handler]))

; the replacement for the BIDI route handler:
(defn handle-sample-with-user [env match]
  (timbre/info "Route hit " env)
  (let [config (-> env :config :value)
        n (-> config :sample :n)
        user (-> env :request :user)]
    (-> (str "Hello " (-> user :id) " Config says: " n)
        ring/response
        (ring/content-type "text/plain"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Exercise 8
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; the replacement for the BIDI route handler:
(defn handle-sample-with-ring-wrapper [env match]
  ((-> (fn [req]
         (timbre/info "Route hit " env)
         (let [config (-> env :config :value)
               n (-> config :sample :n)
               user (-> req :user)]
           (-> (str "Hello " (-> user :id) " Config says: " n)
               ring/response
               (ring/content-type "text/plain"))))
       wrap-user) (:request env)))

