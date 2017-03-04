(ns untangled.client.impl.network-spec
  (:require
    [untangled.client.impl.network :as net]
    [goog.events :as events]
    [untangled-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]))

(specification "Networking"
  (component "Construction of networking"
    (let [url "/some-api"
          atom? (fn [a] (= (type a) Atom))
          n (net/make-untangled-network url :request-transform :transform :global-error-callback (fn [status body] status))]
      (assertions
        "sets the URL"
        (:url n) => url
        "records the request transform"
        (:request-transform n) => :transform
        "records the global error callback"
        (@(:global-error-callback n) 200 "Body") => 200)))

  (behavior "Send"
    (let [body-sent (atom nil)
          headers-sent (atom nil)
          network (net/make-untangled-network "/api")
          fake-xhrio (js-obj "send" (fn [url typ body headers]
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

    (let [body-sent (atom nil)
          headers-sent (atom nil)
          network (net/make-untangled-network "/api" :request-transform (fn [{:keys [request headers]}]
                                                                          {:body {:new 2}
                                                                           :headers {:other 3}}))
          fake-xhrio (js-obj "send" (fn [url typ body headers]
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
