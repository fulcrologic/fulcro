(ns com.fulcrologic.fulcro.networking.http-remote-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.networking.http-kit-driver :refer [make-http-kit-driver]]
    [com.fulcrologic.fulcro.networking.http-remote :as hr]
    [com.fulcrologic.fulcro.server.api-middleware :as fmw :refer [not-found-handler wrap-api]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [edn-query-language.core :as eql]
    [fulcro-spec.core :refer [=> =throws=> assertions behavior specification]]
    [org.httpkit.server :as http-server]
    [taoensso.timbre :as log])
  (:import (java.net BindException)))

;; =============================================================================
;; Helpers
;; =============================================================================

(defmacro with-quiet-logging
  "Globally suppress logging below :fatal for the duration of `body`, then restore.
   Needed because transmit! runs on futures (different threads), so thread-local
   binding won't suppress their log output."
  [& body]
  `(let [prev# (:min-level log/*config*)]
     (log/set-min-level! :fatal)
     (try
       ~@body
       (finally
         (log/set-min-level! (or prev# :debug))))))

(defn mock-driver
  "Creates a mock HTTP driver that returns `response-map` for every request.
   Optionally records requests into `request-log` atom."
  ([response-map] (mock-driver response-map nil))
  ([response-map request-log]
   (fn [request]
     (when request-log
       (swap! request-log conj request))
     response-map)))

(defn transit-encode
  "Encode `data` as transit+json string (for building mock responses)."
  [data]
  (transit/transit-clj->str data))

(defn make-send-node
  "Build a minimal send-node for testing transmit!."
  [eql result-handler & {:keys [abort-id]}]
  (cond-> {::txn/ast            (eql/query->ast eql)
           ::txn/result-handler result-handler}
    abort-id (assoc ::txn/options {::txn/abort-id abort-id})))

;; =============================================================================
;; Middleware specs
;; =============================================================================

(specification "wrap-fulcro-request"
  (behavior "encodes body as transit+json and sets headers"
    (let [mw      (hr/wrap-fulcro-request)
          request {:headers {} :body [{:user/id 1}] :url "/api" :method :post}
          result  (mw request)]
      (assertions
        "sets Content-Type header"
        (get-in result [:headers "Content-Type"]) => "application/transit+json"
        "sets Accept header"
        (get-in result [:headers "Accept"]) => "application/transit+json"
        "sets method to :post"
        (:method result) => :post
        "body is a string"
        (string? (:body result)) => true)))

  (behavior "round-trips tempids through transit"
    (let [tid     (tempid/tempid)
          mw      (hr/wrap-fulcro-request)
          request {:headers {} :body [{:user/id tid}] :url "/api" :method :post}
          result  (mw request)
          decoded (transit/transit-str->clj (:body result))]
      (assertions
        "tempid survives encode/decode"
        (first decoded) => {:user/id tid})))

  (behavior "passes through a handler chain"
    (let [mw      (hr/wrap-fulcro-request
                    (fn [r] (assoc r :custom true)))
          request {:headers {} :body [:query] :url "/api" :method :post}
          result  (mw request)]
      (assertions
        "handler receives the processed request"
        (:custom result) => true))))

(specification "wrap-fulcro-response"
  (behavior "decodes a transit body"
    (let [mw       (hr/wrap-fulcro-response)
          body-str (transit-encode {:result 42})
          response {:body body-str :status-code 200 :error :none}
          result   (mw response)]
      (assertions
        "body is decoded EDN"
        (:body result) => {:result 42})))

  (behavior "returns empty map for blank body"
    (let [mw     (hr/wrap-fulcro-response)
          result (mw {:body "" :status-code 200 :error :none})]
      (assertions
        (:body result) => {})))

  (behavior "returns empty map for nil body"
    (let [mw     (hr/wrap-fulcro-response)
          result (mw {:body nil :status-code 200 :error :none})]
      (assertions
        (:body result) => {})))

  (behavior "passes through network errors unchanged"
    (let [mw       (hr/wrap-fulcro-response)
          response {:body "not-transit" :status-code 0 :error :network-error}
          result   (mw response)]
      (assertions
        "body is untouched"
        (:body result) => "not-transit")))

  (behavior "returns 417 for non-transit body"
    (with-quiet-logging
      (let [mw       (hr/wrap-fulcro-response)
            response {:body "plain text" :status-code 200 :error :none}
            result   (mw response)]
        (assertions
          (:status-code result) => 417))))

  (behavior "round-trips tempids"
    (let [tid      (tempid/tempid)
          mw       (hr/wrap-fulcro-response)
          body-str (transit-encode {:user/id tid})
          result   (mw {:body body-str :status-code 200 :error :none})]
      (assertions
        (:body result) => {:user/id tid}))))

(specification "wrap-csrf-token"
  (behavior "adds X-CSRF-Token header"
    (let [mw     (hr/wrap-csrf-token "my-token")
          result (mw {:headers {} :body :ignored})]
      (assertions
        (get-in result [:headers "X-CSRF-Token"]) => "my-token")))

  (behavior "composes with a handler"
    (let [mw     (hr/wrap-csrf-token (fn [r] (assoc r :processed true)) "tok")
          result (mw {:headers {}})]
      (assertions
        (:processed result) => true
        (get-in result [:headers "X-CSRF-Token"]) => "tok"))))

;; =============================================================================
;; extract-response
;; =============================================================================

(specification "extract-response"
  (behavior "maps a successful driver response"
    (let [r (hr/extract-response [:query] {:url "/api"} {:status 200 :headers {"ct" "json"} :body "ok" :error nil})]
      (assertions
        (:status-code r) => 200
        (:error r) => :none
        (:body r) => "ok"
        (:original-transaction r) => [:query])))

  (behavior "maps a network error"
    (let [r (hr/extract-response [:q] {:url "/api"} {:status nil :headers {} :body nil :error nil})]
      (assertions
        (:error r) => :network-error)))

  (behavior "maps a server error"
    (let [r (hr/extract-response [:q] {:url "/api"} {:status 500 :headers {} :body "err" :error nil})]
      (assertions
        (:error r) => :http-error
        (:status-code r) => 500)))

  (behavior "maps a driver exception"
    (let [r (hr/extract-response [:q] {:url "/api"} {:status 200 :headers {} :body "ok" :error (Exception. "boom")})]
      (assertions
        (:error r) => :exception))))

;; =============================================================================
;; fulcro-http-remote: transmit! with mock driver
;; =============================================================================

(specification "fulcro-http-remote transmit!"
  (behavior "calls result-handler with decoded body on success"
    (let [result-promise (promise)
          transit-body   (transit-encode {:user/name "John"})
          driver         (mock-driver {:status 200 :headers {} :body transit-body :error nil})
          remote         (hr/fulcro-http-remote {:http-driver driver})
          send-node      (make-send-node [:user/name]
                           (fn [result] (deliver result-promise result)))]
      ((:transmit! remote) remote send-node)
      (let [result (deref result-promise 5000 :timeout)]
        (assertions
          "does not time out"
          (not= result :timeout) => true
          "body is decoded transit"
          (:body result) => {:user/name "John"}
          "status is 200"
          (:status-code result) => 200))))

  (behavior "calls result-handler with error info on server error"
    (with-quiet-logging
      (let [result-promise (promise)
            driver         (mock-driver {:status 500 :headers {} :body "" :error nil})
            remote         (hr/fulcro-http-remote {:http-driver driver})
            send-node      (make-send-node [:user/name]
                             (fn [result] (deliver result-promise result)))]
        ((:transmit! remote) remote send-node)
        (let [result (deref result-promise 5000 :timeout)]
          (assertions
            "status code reflects error preprocessing (default merges 500)"
            (:status-code result) => 500)))))

  (behavior "sends transit-encoded body to the driver"
    (let [result-promise (promise)
          request-log    (atom [])
          driver         (mock-driver {:status 200 :headers {} :body (transit-encode {}) :error nil}
                           request-log)
          remote         (hr/fulcro-http-remote {:http-driver driver :url "http://test/api"})
          send-node      (make-send-node [:user/name]
                           (fn [_] (deliver result-promise :done)))]
      ((:transmit! remote) remote send-node)
      (deref result-promise 5000 :timeout)
      (let [req (first @request-log)]
        (assertions
          "driver receives the configured URL"
          (:url req) => "http://test/api"
          "driver receives transit content-type"
          (get-in req [:headers "Content-Type"]) => "application/transit+json"
          "body is a string"
          (string? (:body req)) => true))))

  (behavior "handles middleware failure gracefully"
    (with-quiet-logging
      (let [result-promise (promise)
            driver         (mock-driver {:status 200 :headers {} :body (transit-encode {}) :error nil})
            bad-middleware (fn [_req] (throw (Exception. "middleware boom")))
            remote         (hr/fulcro-http-remote {:http-driver        driver
                                                   :request-middleware bad-middleware})
            send-node      (make-send-node [:user/name]
                             (fn [result] (deliver result-promise result)))]
        ((:transmit! remote) remote send-node)
        (let [result (deref result-promise 5000 :timeout)]
          (assertions
            "error handler is called"
            (:error result) => :abort))))))

;; =============================================================================
;; fulcro-http-remote: abort!
;; =============================================================================

(specification "fulcro-http-remote abort!"
  (behavior "cancels a running future and delivers error to result-handler"
    (with-quiet-logging
      (let [result-promise (promise)
            started        (promise)
            driver         (fn [_]
                             (deliver started true)
                             ;; Simulate a long-running request
                             (Thread/sleep 10000)
                             {:status 200 :headers {} :body "" :error nil})
            remote         (hr/fulcro-http-remote {:http-driver driver})
            send-node      (make-send-node [:user/name]
                             (fn [result] (deliver result-promise result))
                             :abort-id :test-abort)]
        ((:transmit! remote) remote send-node)
        ;; Wait for the driver to start executing
        (deref started 5000 :timeout)
        ;; Abort it
        ((:abort! remote) remote :test-abort)
        ;; The cancelled future triggers InterruptedException -> error-handler -> result-handler
        (let [result (deref result-promise 5000 :timeout)]
          (assertions
            "result-handler receives the abort result"
            (not= result :timeout) => true
            "error indicates exception from interruption"
            (:error result) => :exception
            "status-code is set by preprocess-error"
            (:status-code result) => 500))))))

;; =============================================================================
;; Constructor validation
;; =============================================================================

(specification "fulcro-http-remote constructor"
  (behavior "throws when :http-driver is missing"
    (assertions
      (hr/fulcro-http-remote {}) =throws=> #"requires an :http")))

;; =============================================================================
;; Integration test: headless Fulcro app ↔ real HTTP server with Pathom
;; =============================================================================

(def person-db (atom {1 {:person/id 1 :person/name "Alice"}
                      2 {:person/id 2 :person/name "Bob"}}))

(pc/defresolver person-resolver [env {:keys [person/id]}]
  {::pc/input  #{:person/id}
   ::pc/output [:person/name]}
  (get @person-db id))

(m/defmutation set-person-name [{:person/keys [id name]}]
  (remote [env] true))

(pc/defmutation set-person-name-pathom [env {:keys [person/id person/name]}]
  {::pc/sym    `set-person-name
   ::pc/params [:person/id :person/name]
   ::pc/output [:person/id]}
  (swap! person-db assoc-in [id :person/name] name)
  {:person/id id})

(def pathom-parser
  (p/parser
    {::p/env     {::p/reader                 [p/map-reader
                                              pc/reader2
                                              pc/open-ident-reader]
                  ::pc/mutation-join-globals [:tempids]}
     ::p/mutate  pc/mutate
     ::p/plugins [(pc/connect-plugin {::pc/register [person-resolver set-person-name-pathom]})
                  (p/post-process-parser-plugin p/elide-not-found)
                  p/error-handler-plugin]}))

(def test-middleware
  (-> not-found-handler
    (wrap-api {:uri    "/api"
               :parser (fn [query] (pathom-parser {} query))})
    (fmw/wrap-transit-params)
    (fmw/wrap-transit-response)))

(defonce test-server (atom nil))

(def ^:private integration-test-port 12004)

(defn stop-test-server! []
  (when-let [stop-fn @test-server]
    (stop-fn)
    (reset! test-server nil)))

(defn start-test-server! []
  (stop-test-server!)
  (reset! person-db {1 {:person/id 1 :person/name "Alice"}
                     2 {:person/id 2 :person/name "Bob"}})
  (try
    (reset! test-server (http-server/run-server test-middleware {:port integration-test-port}))
    (log/info "Started test server " integration-test-port)
    (catch BindException _e
      (log/warn "Port" integration-test-port "already bound, retrying after stop...")
      (stop-test-server!)
      (reset! test-server (http-server/run-server test-middleware {:port integration-test-port})))))

(defsc Person [_ _]
  {:query [:person/id :person/name]
   :ident :person/id})

(specification "Integration: headless app with real HTTP server (synchronous)"
  (with-quiet-logging
    (try
      (start-test-server!)
      (let [remote (hr/fulcro-http-remote {:http-driver  (make-http-kit-driver {:timeout 1000})
                                           :url          (str "http://localhost:" integration-test-port "/api")
                                           :synchronous? true})
            app    (h/build-test-app {:remotes {:remote remote}})]

        (behavior "df/load! retrieves resolver data and merges into app state"
          (df/load! app [:person/id 1] Person)
          (assertions
            "resolver data is merged correctly"
            (get-in @(::app/state-atom app) [:person/id 1 :person/name]) => "Alice"))

        (behavior "mutation reaches the server and effect is observable on reload"
          (comp/transact! app [(set-person-name {:person/id 1 :person/name "Zara"})])
          (df/load! app [:person/id 1] Person)
          (assertions
            "person name was updated"
            (get-in @(::app/state-atom app) [:person/id 1 :person/name]) => "Zara")))

      (finally
        (stop-test-server!)))))
