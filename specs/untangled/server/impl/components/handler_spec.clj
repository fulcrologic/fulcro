(ns ^:focused untangled.server.impl.components.handler-spec
  (:require [untangled-spec.core :refer [specification when-mocking assertions component behavior]]
            [untangled.server.impl.components.handler :as h]
            [om.next.server :as om]
            [taoensso.timbre :as timbre])
  (:import (clojure.lang ExceptionInfo)))

(specification "generate-response"
  (behavior "returns a map with status, header, and body.")
  (behavior "merges Content-Type of transit json to the passed-in headers.")
  (behavior "does not permit"
    (behavior "a \"Content-Type\" key in the header.")
    (behavior "a status code less than 100.")
    (behavior "a status code greater than or equal to 600.")))

(specification "API Endpoint"
  (let [my-read (fn [_ key _] {:value (case key
                                        :foo "success"
                                        :bar (throw (ex-info "Oops" {:my :bad}))
                                        :bar' (throw (ex-info "Oops'" {:status 402 :body "quite an error"}))
                                        :baz (throw (Exception. "My Exception")))})

        parser (om/parser {:read my-read})
        parse-result (fn [query] (h/api {:parser parser :transit-params query}))]

    (when-mocking
      (h/generate-response api-resp) => api-resp

      (behavior "for a valid request"
        (behavior "returns a query response"
          (let [result (parse-result [:foo])]
            (assertions
              "with a body containing the expected parse result."
              (:body result) => {:query-response {:foo "success"}}))))

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
              (:body result) =fn=> (partial instance? ExceptionInfo))))

        (behavior "when the parser does not generate the error"
          (let [result (parse-result [:baz])]
            (assertions
              "returns a 500 http status code"
              (:status result) => 500

              "returns exception data in the response body"
              (str (:body result)) => "java.lang.Exception: My Exception")))))))
