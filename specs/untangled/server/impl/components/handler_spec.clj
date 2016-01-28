(ns ^:focused untangled.server.impl.components.handler-spec
  (:require [untangled-spec.core :refer [specification when-mocking assertions component behavior]]
            [untangled.server.impl.components.handler :as h]
            [om.next.server :as om]))

(specification "API Endpoint"
  (let [my-read (fn [_ key _] {:value (case key
                                        :foo "success"
                                        :bar (throw (ex-info "Oops" {:my :bad}))
                                        :bar' (throw (ex-info "Oops'" {:status 402 :body "quite an error"}))
                                        :baz (throw (Exception.)))})

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
          (let [result (parse-result [:bar])]))

        (behavior "when the parser does not generate the error"
          (assertions
            "returns a status code == 500"
            false => true

            "returns exception data in the response body"
            false => true))))))
