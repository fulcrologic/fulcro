(ns untangled.server.impl.components.handler-spec
  (:require [untangled-spec.core :refer [specification assertions provided component behavior]]
            [clojure.test :as t]
            [untangled.server :as server :refer [augment-response]]
            [untangled.easy-server :as easy]
            [com.stuartsierra.component :as component]
            [om.next.server :as om]
            [taoensso.timbre :as timbre])
  (:import (clojure.lang ExceptionInfo)))

(t/use-fixtures
  :once #(timbre/with-merged-config
           {:level :fatal}
           (%)))

(specification "generate-response"
  (assertions
    "returns a map with status, header, and body."
    (keys (server/generate-response {})) => [:status :body :headers]

    "merges Content-Type of transit json to the passed-in headers."
    (:headers (server/generate-response {:headers {:my :header}})) => {:my            :header
                                                                  "Content-Type" "application/transit+json"}

    "preserves extra response keys from input"
    (server/generate-response {:status 200 :body {} :session {:user-id 123}})
    => {:status  200
        :headers {"Content-Type" "application/transit+json"}
        :body    {}
        :session {:user-id 123}})

  (behavior "does not permit"
    (assertions
      "a \"Content-Type\" key in the header."
      (server/generate-response {:headers {"Content-Type" "not-allowed"}}) =throws=> (AssertionError #"headers")

      "a status code less than 100."
      (server/generate-response {:status 99}) =throws=> (AssertionError #"100")

      "a status code greater than or equal to 600."
      (server/generate-response {:status 600}) =throws=> (AssertionError #"600"))))

(specification "An API Response"
  (let [my-read (fn [_ key _]
                  {:value (case key
                            :foo "success"
                            :foo-session (augment-response {:some "data"} #(assoc-in % [:session :uid] "current-user"))
                            :bar (throw (ex-info "Oops" {:my :bad}))
                            :bar' (throw (ex-info "Oops'" {:status 402 :body "quite an error"}))
                            :baz (throw (IllegalArgumentException.)))})

        my-mutate (fn [_ key params]
                    {:action (condp = key
                               'foo (fn [] "success")
                               'overrides (fn [] (augment-response {} #(assoc % :body "override"
                                                                         :status 201
                                                                         :cookies {:uid (:uid params)})))
                               'bar (fn [] (throw (ex-info "Oops" {:my :bad})))
                               'bar' (fn [] (throw (ex-info "Oops'" {:status 402 :body "quite an error"})))
                               'baz (fn [] (throw (IllegalArgumentException.))))})

        parser (om/parser {:read my-read :mutate my-mutate})
        parse-result (fn [query] (easy/api {:parser parser :transit-params query}))]

    (behavior "for Om reads"
      (behavior "for a valid request"
        (behavior "returns a query response"
          (let [result (parse-result [:foo])]
            (assertions
              "with a body containing the expected parse result."
              (:body result) => {:foo "success"}))))

      (behavior "for an invalid request"
        (behavior "when the parser generates an expected error"
          (let [result (parse-result [:bar'])]
            (assertions
              "returns a status code."
              (:status result) =fn=> (complement nil?)

              "returns body if provided."
              (:body result) => "quite an error")))

        (behavior "when the parser generates an unexpected error"
          (let [result (parse-result [:bar])]
            (assertions
              "returns a 500 http status code."
              (:status result) => 500

              "contains an exception in the response body."
              (:body result) => {:type "class clojure.lang.ExceptionInfo" :message "Oops" :data {:my :bad}})))

        (behavior "when the parser does not generate the error"
          (let [result (parse-result [:baz])]
            (assertions
              "returns a 500 http status code."
              (:status result) => 500

              "returns exception data in the response body."
              (:body result) => {:type "class java.lang.IllegalArgumentException", :message nil})))))

    (behavior "for Om mutates"
      (behavior "for a valid request"
        (behavior "returns a query response"
          (let [result (parse-result ['(foo)])]
            (assertions
              "with a body containing the expected parse result."
              (:body result) => {'foo "success"}))))

      (behavior "for invalid requests (where one or more mutations fail)"
        (let [bar-result (parse-result ['(bar')])
              bar'-result (parse-result ['(bar)])
              baz-result (parse-result ['(baz)])]

          (behavior "returns a status code of 400."
            (doall (map #(t/is (= 400 (:status %))) [bar'-result bar-result baz-result])))

          (behavior "returns failing mutation result in the body."
            (letfn [(get-error [result] (-> result :body vals first :om.next/error))]
              (assertions
                (get-error bar-result) => {:type    "class clojure.lang.ExceptionInfo",
                                           :message "Oops'",
                                           :data    {:status 402, :body "quite an error"}}

                (get-error bar'-result) => {:type    "class clojure.lang.ExceptionInfo",
                                            :message "Oops",
                                            :data    {:my :bad}}

                (get-error baz-result) => {:type    "class java.lang.IllegalArgumentException",
                                           :message nil}))))))

    (behavior "for updating the response"
      (behavior "adds the response keys to the ring response"
        (let [result (parse-result [:foo-session])]
          (assertions
            (:session result) => {:uid "current-user"})))
      (behavior "user can override response fields, eg status & body"
        (assertions
          (parse-result ['(overrides {:uid "new-user"})])
          => {:status 201, :body "override", :cookies {:uid "new-user"}
              :headers {"Content-Type" "application/transit+json"}})))))

(defn run [handler req]
  ((:middleware (component/start handler)) req))
(specification "the handler"
  (behavior "takes an extra-routes map containing bidi :routes & :handlers"
    (let [make-handler (fn [extra-routes]
                         (easy/build-handler (constantly nil) #{}
                           :extra-routes extra-routes))]
      (assertions
        (-> {:routes   ["/" {"test" :test}]
             :handlers {:test (fn [env match]
                                {:body "test"
                                 :status 200})}}
            (make-handler)
            (run {:uri "/test"}))
        => {:body "test"
            :headers {"Content-Type" "application/octet-stream"}
            :status 200}

        "handler functions get passed the bidi match as an arg"
        (-> {:routes   ["/" {["test/" :id] :test-with-params}]
             :handlers {:test-with-params (fn [env match]
                                            {:body (:id (:route-params match))
                                             :status 200})}}
            (make-handler)
            (run {:uri "/test/foo"}))
        => {:body "foo"
            :status 200
            :headers {"Content-Type" "application/octet-stream"}}

        "and the request in the environment"
        (-> {:routes   ["/" {["test"] :test}]
             :handlers {:test (fn [env match]
                                {:body {:req (:request env)}
                                 :status 200})}}
            (make-handler)
            (run {:uri "/test"}))
        => {:body {:req {:uri "/test"}}
            :status 200
            :headers {"Content-Type" "application/octet-stream"}}

        "also dispatches on :request-method"
        (-> {:routes   ["/" {["test/" :id] {:post :test-post}}]
             :handlers {:test-post (fn [env match]
                                     {:body "post"
                                      :status 200})}}
            (make-handler)
            (run {:uri "/test/foo"
                  :request-method :post}))
        => {:body "post"
            :headers {"Content-Type" "application/octet-stream"}
            :status 200}

        "has to at least take a valid (but empty) :routes & :handlers"
        (-> {:routes ["" {}], :handlers {}}
            make-handler
            (run {:uri "/"})
            (dissoc :body))
        => {:headers {"Content-Type" "text/html"}
            :status 200})))

  (behavior "calling (get/set)-(pre/fallback)-hook can modify the ring handler stack"
    (letfn [(make-test-system []
              (.start (component/system-map
                        :config {}
                        :logger {}
                        :handler (easy/build-handler (constantly nil) {}))))]
      (assertions
        "the pre-hook which can short-circuit before the extra-routes, wrap-resource, or /api"
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-pre-hook! handler (fn [h]
                                     (fn [req] {:status 200
                                                :headers {"Content-Type" "text/text"}
                                                :body "pre-hook"})))

          (:body ((:middleware handler) {})))
        => "pre-hook"

        "the fallback hook will only get called if all other handlers do nothing"
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-fallback-hook! handler (fn [h]
                                          (fn [req] {:status 200
                                                     :headers {"Content-Type" "text/text"}
                                                     :body "fallback-hook"})))
          (:body ((:middleware handler) {:uri "/i/should/fail"})))
        => "fallback-hook"

        "get-(pre/fallback)-hook returns whatever hook is currently installed"
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-pre-hook! handler (fn [h] '_))
          (easy/get-pre-hook handler))
        =fn=> #(= '_ (%1 nil))
        (let [{:keys [handler]} (make-test-system)]
          (easy/set-fallback-hook! handler (fn [h] '_))
          (easy/get-fallback-hook handler))
        =fn=> #(= '_ (%1 nil))))))
