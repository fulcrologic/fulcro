(ns fulcro.client.network-spec
  (:require
    [fulcro.client.network :as net :refer [progress%]]
    [goog.events :as events]
    [fulcro-spec.core :refer-macros [specification behavior assertions provided component when-mocking]])
  (:import [goog.net XhrIo EventType ErrorCode]))

(specification "Fulcro HTTP Remote"
  (component "error state map"
    (assertions
      "Contains mappings for the XhrIO error result states"
      (get net/xhrio-error-states 0) => :none
      (get net/xhrio-error-states 5) => :exception
      (get net/xhrio-error-states 6) => :http-error
      (get net/xhrio-error-states 7) => :abort
      (get net/xhrio-error-states 8) => :timeout))

  (component "xhrio-error-code"
    (when-mocking
      (net/xhrio-status-code x) => 0
      (net/xhrio-raw-error x) =1x=> (.-ABORT ErrorCode)
      (net/xhrio-raw-error x) =1x=> (.-EXCEPTION ErrorCode)
      (net/xhrio-raw-error x) =1x=> (.-TIMEOUT ErrorCode)

      (assertions
        "Normally maps xhrio errors to keywords"
        (net/xhrio-error-code :mock-xhrio) => :abort
        (net/xhrio-error-code :mock-xhrio) => :exception
        (net/xhrio-error-code :mock-xhrio) => :timeout))

    (when-mocking
      (net/xhrio-status-code x) => 404
      (net/xhrio-raw-error x) => (.-HTTP_ERROR ErrorCode)

      (assertions
        "Uses :http-error when there is a non-zero status code for an error"
        (net/xhrio-error-code :mock-xhrio) => :http-error))
    (when-mocking
      (net/xhrio-status-code x) => 0
      (net/xhrio-raw-error x) => (.-HTTP_ERROR ErrorCode)

      (assertions
        "Converts a zero status code and HTTP ERROR to :network-error"
        (net/xhrio-error-code :mock-xhrio) => :network-error)))

  (component "extract-response"
    (let [xhrio (net/make-xhrio)]
      (try
        (.send xhrio "http://localhost:10/lkhadsf" "GET" "" #js {})
        (.abort xhrio)

        (let [r (net/extract-response [:a?] {:a? false} xhrio)]
          (assertions
            "places the critical data from XhrIO on the response "
            (contains? r :body) => true
            (contains? r :status-code) => true
            (contains? r :status-text) => true
            (contains? r :error) => true
            (contains? r :error-text) => true
            "Includes the original tx"
            (:transaction r) => [:a?]
            "Includes the outgoing request"
            (:outgoing-request r) => {:a? false}
            "Transforms headers to clj map"
            (:headers r) => {}))
        (finally
          (.dispose xhrio)))))

  (component "clear-request*"
    (let [active-requests {1 #{:mock-1 :mock-2}}]
      (assertions
        "Removes the specific instance of xhrio from active requests"
        (net/clear-request* active-requests 1 :mock-1) => {1 #{:mock-2}}
        "Removes the entire mapping if that was the last active request for that ID"
        (-> active-requests
          (net/clear-request* 1 :mock-2)
          (net/clear-request* 1 :mock-1)) => {})))

  (component "response-extractor*"
    (let [extracted-response {:status-code 200 :body "Hello world!"}
          edn                [:a?]
          request            {}
          xhrio              :mock-xhrio
          middleware-called  (atom false)
          middleware         (fn [r]
                               (reset! middleware-called r)
                               (with-meta r {:middleware true}))]

      (when-mocking
        (net/extract-response t r x) => extracted-response

        (let [get-response (net/response-extractor* middleware edn request xhrio)
              response     (get-response)]

          (assertions
            "transforms the response through the middleware"
            @middleware-called => extracted-response
            "returns the result of the middleware processing"
            response => extracted-response
            (meta response) => {:middleware true}))

        (behavior "on middleware exceptions:"
          (let [bad-middleware (fn [r]
                                 (reset! middleware-called true)
                                 (throw (ex-info "Bummer!" {})))
                get-response   (net/response-extractor* bad-middleware edn request xhrio)
                response       (get-response)]

            (assertions
              "merges the middleware exception with the raw response"
              (select-keys response #{:status-code :body}) => extracted-response
              (contains? response :middleware-exception) => true
              (:error response) => :middleware-failure))))))

  (component "cleanup-routine*"
    (when-mocking
      (net/clear-request* a id x) =1x=> (do
                                          (assertions
                                            "Clears any outstanding request for the xhrio/id combo"
                                            id => :id
                                            x => :mock-xhrio)
                                          a)
      (net/xhrio-dispose x) =1x=> (assertions
                                    "Disposes of xhrio resources"
                                    x => :mock-xhrio)

      (let [active-requests (atom {})
            cleanup         (net/cleanup-routine* :id active-requests :mock-xhrio)]

        (cleanup))))

  (component "ok-routine*"
    (let [progress                   (atom {})
          complete?                  (atom false)
          failed?                    (atom false)
          reset-test                 (fn [] (reset! progress {}) (reset! complete? false) (reset! failed? false))
          ok-response                {:status 200 :body "OK"}
          get-ok-resp                (fn [] (with-meta ok-response {:middleware true}))
          faulty-middleware          (fn [] {:error :middleware-failure})
          progress-fn                (fn [phase event] (reset! progress {phase event}))
          complete-fn                #(reset! complete? %)
          fail-fn                    #(reset! failed? %1)

          ok-with-good-response      (net/ok-routine* progress-fn get-ok-resp complete-fn fail-fn)
          ok-with-middleware-failure (net/ok-routine* progress-fn faulty-middleware complete-fn fail-fn)]

      (behavior "Handling a good response:"

        (reset-test)
        (ok-with-good-response {:xhrio :event})

        (assertions
          "Calls the completion function with the middleware response"
          @complete? => ok-response
          (meta @complete?) => {:middleware true}
          "Indicates that progress is compelete"
          @progress => {:complete {:xhrio :event}}))

      (behavior "Handling a middleware failure:"

        (reset-test)
        (ok-with-middleware-failure {:xhrio :event})

        (assertions
          "Calls the progress function with :failure"
          @progress => {:failed {:xhrio :event}}
          "calls the failure function with the original response but will error set to
           :middleware-failure."
          @complete? => false
          @failed? => {:error :middleware-failure}))))

  (component "progress-routine*"
    (let [no-progress (net/progress-routine* (constantly {:mock :response}) nil)
          updated     (atom {})
          progress    (net/progress-routine* (constantly {:mock :response}) (fn [evt] (reset! updated evt)))]
      (assertions
        "Can be used even if constructed with a nil callback"
        (no-progress :x {}) => nil)

      (progress :sending {:detail 1})

      (assertions
        "Merges the progress phase and event with the current response (in progress)"
        @updated => {:mock           :response
                     :progress-phase :sending
                     :progress-event {:detail 1}})))

  (component "error-routine*"
    (behavior "when there is a legal status code"
      (let [middleware?     (atom false)
            progress-update (atom nil)
            error-report    (atom nil)
            errant-response {:status-code 500 :error :http-error}
            get-response-fn (fn []
                              (reset! middleware? true)
                              errant-response)
            progress-fn     (fn [phase event] (reset! progress-update {phase event}))
            error-fn        (fn [resp] (reset! error-report resp))
            error           (net/error-routine* get-response-fn identity progress-fn error-fn)]

        (error {:xhrio :event})

        (assertions
          "Indicates failure to the progress routine"
          @progress-update => {:failed {:xhrio :event}}
          "Passes the error resposne through the response middleware"
          @middleware? => true
          "Reports the :error to the error handler"
          @error-report => errant-response)))
    (behavior "when it looks like a low-level network error"
      (let [error-report     (atom nil)
            errant-response  {:status-code 0 :error :http-error}
            get-response-fn  (fn [] errant-response)
            progress-routine identity
            error-fn         (fn [resp] (reset! error-report resp))
            error            (net/error-routine* get-response-fn identity progress-routine error-fn)]

        (error {:xhrio :event})

        (assertions
          "Reports the :error as a low-level :network-error to the error handler"
          @error-report => errant-response))))

  (component "FulcroHTTPRemote"
    (component "transmit"
      (provided "Updates are not desired"
        (net/make-xhrio) => :mock-xhrio
        (net/response-extractor* middleware edn request xhrio) => :response-extractor
        (net/cleanup-routine* abort-id requests xhrio) => :cleanup-function
        (net/progress-routine* r update-fn) => :progress-reporter
        (net/ok-routine* progress-fn get-response-fn complete-fn error-fn) => (do
                                                                                (assertions
                                                                                  "constructs ok routine with the correct helpers"
                                                                                  progress-fn => :progress-reporter
                                                                                  get-response-fn => :response-extractor
                                                                                  complete-fn => :fulcro-complete-fn
                                                                                  error-fn => :fulcro-error-fn)
                                                                                :ok-function)
        (net/error-routine* get-resp ok progress error) => (do
                                                          (assertions
                                                            "constructs error routine with correct helpers"
                                                            get-resp => :response-extractor
                                                            progress => :progress-reporter
                                                            error => :fulcro-error-fn)
                                                          :error-function)
        (events/listen x ev fn) =1x=> (assertions
                                        "registers for success events on the ok helper"
                                        ev => (.-SUCCESS EventType))
        (events/listen x ev fn) =1x=> (assertions
                                        "registers for abort events on the error helper"
                                        ev => (.-ABORT EventType))
        (events/listen x ev fn) =1x=> (assertions
                                        "registers for error events on the error helper"
                                        ev => (.-ERROR EventType))
        (net/xhrio-send x u v b h) => (assertions
                                        "Sends the computed network request"
                                        x => :mock-xhrio
                                        u => "http://server:2000/my-api"
                                        v => "POST"
                                        "which has the middleware request on it"
                                        b => :middleware-body
                                        h => :middleware-headers)

        (let [remote (net/fulcro-http-remote {:url                 "http://server:2000/my-api"
                                              :request-middleware  (fn [r] (merge
                                                                             r
                                                                             {:body :middleware-body :headers :middleware-headers}))
                                              :response-middleware (fn [r] (merge r {:middleware-in true}))})]

          (net/transmit remote {::net/ok-handler       :fulcro-complete-fn
                                ::net/error-handler    :fulcro-error-fn
                                ::net/progress-handler nil})))

      (provided "Updates are requested"
        (net/make-xhrio) => :mock-xhrio
        (net/xhrio-enable-progress-events x) => (assertions
                                                  "Enables progress events on the xhrio object"
                                                  x => :mock-xhrio)
        (net/response-extractor* middleware edn request xhrio) => :response-extractor
        (net/cleanup-routine* abort-id requests xhrio) => :cleanup-function
        (net/progress-routine* r update-fn) => :progress-reporter
        (net/ok-routine* progress-fn get-response-fn complete-fn error-fn) => :ok-function
        (net/error-routine* get-resp ok progress error) => :error-function
        (events/listen x ev fn) =1x=> (assertions
                                        "registers for download progress events"
                                        ev => (.-DOWNLOAD_PROGRESS EventType))
        (events/listen x ev fn) =1x=> (assertions
                                        "registers for upload progress events"
                                        ev => (.-UPLOAD_PROGRESS EventType))
        (events/listen x ev fn) => :other-events
        (net/xhrio-send x u v b h) => (assertions
                                        "Sends the computed network request"
                                        x => :mock-xhrio
                                        u => "http://server:2000/my-api"
                                        v => "POST"
                                        "which has the middleware request on it"
                                        b => :middleware-body
                                        h => :middleware-headers)

        (let [remote (net/fulcro-http-remote {:url                 "http://server:2000/my-api"
                                              :request-middleware  (fn [r] (merge
                                                                             r
                                                                             {:body :middleware-body :headers :middleware-headers}))
                                              :response-middleware (fn [r] (merge r {:middleware-in true}))})]

          (net/transmit remote {::net/ok-handler       :fulcro-complete-fn
                                ::net/error-handler    :fulcro-error-fn
                                ::net/progress-handler :fulcro-update-fn})))

      (provided "An abort ID is supplied"
        (net/make-xhrio) => :mock-xhrio
        (net/response-extractor* middleware edn request xhrio) => :response-extractor
        (net/cleanup-routine* abort-id requests xhrio) => :cleanup-function
        (net/progress-routine* r update-fn) => :progress-reporter
        (net/ok-routine* progress-fn get-response-fn complete-fn error-fn) => :ok-function
        (net/error-routine* get-resp ok progress error) => :error-function
        (events/listen x ev fn) => :ok
        (net/xhrio-send x u v b h) => :sent

        (let [remote (net/fulcro-http-remote {:url                 "http://server:2000/my-api"
                                              :request-middleware  (fn [r] (merge
                                                                             r
                                                                             {:body :middleware-body :headers :middleware-headers}))
                                              :response-middleware (fn [r] (merge r {:middleware-in true}))})]

          (net/transmit remote {::net/edn              []
                                ::net/abort-id         :ID
                                ::net/ok-handler       :fulcro-complete-fn
                                ::net/error-handler    :fulcro-error-fn
                                ::net/progress-handler nil})

          (assertions "Adds the xhrio object to active requests under that ID"
            (some-> remote :active-requests deref) => {:ID #{:mock-xhrio}}))))))

(specification "progress% calculator"
  (let [total            (atom 0)
        loaded           (atom 0)
        current-progress (fn [phase l t]
                           (reset! total t)
                           (reset! loaded l)
                           {::net/progress {:progress-event :mock-event
                                            :progress-phase phase}})]
    (when-mocking
      (net/xhrio-progress p) => {:total @total :loaded @loaded}

      (component "overall progress"
        (assertions
          "reports 0 when just started sending"
          (progress% (current-progress :sending 0 6) :overall) => 0
          "reports numbers in range 0-49 during sends"
          (progress% (current-progress :sending 3 6) :overall) => 24
          (progress% (current-progress :sending 6 6) :overall) => 49
          "reports numbers 50-100 during receive"
          (progress% (current-progress :receiving 0 10) :overall) => 50
          (progress% (current-progress :receiving 5 10) :overall) => 75
          (progress% (current-progress :receiving 10 10) :overall) => 100
          "reports 100 if phase is complete"
          (progress% (current-progress :complete 9 10) :overall) => 100
          "reports 0 if phase is failed"
          (progress% (current-progress :failed 9 10) :overall) => 0))

      (component "send progress"
        (assertions
          "reports 0 when just started sending"
          (progress% (current-progress :sending 0 6) :sending) => 0
          "reports numbers in range 0-100 during sends"
          (progress% (current-progress :sending 3 6) :sending) => 50
          (progress% (current-progress :sending 6 6) :sending) => 100
          "reports 100 during receive"
          (progress% (current-progress :receiving 0 10) :sending) => 100
          (progress% (current-progress :receiving 5 10) :sending) => 100
          (progress% (current-progress :receiving 10 10) :sending) => 100
          "reports 100 if phase is complete"
          (progress% (current-progress :complete 9 10) :sending) => 100
          "reports 0 if phase is failed"
          (progress% (current-progress :failed 9 10) :sending) => 0))

      (component "receive progress"
        (assertions
          "reports 0 when sending"
          (progress% (current-progress :sending 0 6) :receiving) => 0
          (progress% (current-progress :sending 3 6) :receiving) => 0
          (progress% (current-progress :sending 6 6) :receiving) => 0
          "reports numbers in range 0-100 during receive"
          (progress% (current-progress :receiving 0 6) :receiving) => 0
          (progress% (current-progress :receiving 3 6) :receiving) => 50
          (progress% (current-progress :receiving 6 6) :receiving) => 100
          "reports 100 if phase is complete"
          (progress% (current-progress :complete 9 10) :receiving) => 100
          "reports 0 if phase is failed"
          (progress% (current-progress :failed 9 10) :receiving) => 0)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs for largely-deprecated API:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(specification "Networking"
  (component "Construction of networking"
    (let [url   "/some-api"
          atom? (fn [a] (= (type a) Atom))
          n     (net/make-fulcro-network url :request-transform :transform :global-error-callback (fn [status body] status))]
      (assertions
        "sets the URL"
        (:url n) => url
        "records the request transform"
        (:request-transform n) => :transform
        "records the global error callback"
        (@(:global-error-callback n) 200 "Body") => 200)))

  (behavior "Send"
    (let [body-sent    (atom nil)
          headers-sent (atom nil)
          network      (net/make-fulcro-network "/api")
          fake-xhrio   (js-obj "send" (fn [url typ body headers]
                                        (reset! body-sent body)
                                        (reset! headers-sent headers)))]

      (when-mocking
        (net/make-xhrio) => fake-xhrio
        (events/listen _ _ _) => nil

        (net/send network {:original 1} nil nil))

      (assertions
        "Sends the original body if no transform is present"
        (js->clj @body-sent) => "[\"^ \",\"~:original\",1]"
        "Uses content-type for transit by default"
        (js->clj @headers-sent) => {"Content-Type" "application/transit+json"}))

    (let [body-sent    (atom nil)
          headers-sent (atom nil)
          network      (net/make-fulcro-network "/api" :request-transform (fn [{:keys [request headers]}]
                                                                            {:body    {:new 2}
                                                                             :headers {:other 3}}))
          fake-xhrio   (js-obj "send" (fn [url typ body headers]
                                        (reset! body-sent body)
                                        (reset! headers-sent headers)))]

      (when-mocking
        (net/make-xhrio) => fake-xhrio
        (events/listen _ _ _) => nil

        (net/send network {:original 1} nil nil))

      (assertions
        "Request transform can replace body"
        (js->clj @body-sent) => "[\"^ \",\"~:new\",2]"
        "Request transform can replace headers"
        (js->clj @headers-sent) => {"other" 3}))))
