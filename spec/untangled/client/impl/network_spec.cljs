(ns untangled.client.impl.network-spec
  (:require
    [untangled.client.impl.network :as net]
    [goog.events :as events]
    [untangled-spec.core :refer-macros [specification behavior assertions provided component when-mocking]]))

(specification "Networking"
  (component "Construction of networking"
    (when-mocking
      (events/listen xhr evt cb) => nil

      (let [url "/some-api"
            atom? (fn [a] (= (type a) Atom))
            n (net/make-untangled-network url :request-transform :transform :global-error-callback (fn [] 5))]
        (assertions
          "sets the URL"
          (:url n) => url
          "creates atoms for internal callbacks"
          (:valid-data-callback n) =fn=> atom?
          (:error-callback n) =fn=> atom?
          "records the request transform"
          (:request-transform n) => :transform
          "records the global error callback"
          (@(:global-error-callback n)) => 5))))

  (behavior "Send"
    (let [body-sent (atom nil)
          headers-sent (atom nil)
          n (net/make-untangled-network "/api")
          fake-xhrio (js-obj "send" (fn [url typ body headers]
                                      (reset! body-sent body)
                                      (reset! headers-sent headers)))
          network (assoc n :xhr-io fake-xhrio)]

      (net/send network {:original 1} nil nil)

      (assertions
        "Sends the original body if no transform is present"
        (js->clj @body-sent) => "[\"^ \",\"~:original\",1]"
        "Uses content-type for transit by default"
        (js->clj @headers-sent) => {"Content-Type" "application/transit+json"}))

    (let [body-sent (atom nil)
          headers-sent (atom nil)
          n (net/make-untangled-network "/api" :request-transform (fn [{:keys [request headers]}]
                                                                    {:request {:new 2}
                                                                     :headers {:other 3}}))
          fake-xhrio (js-obj "send" (fn [url typ body headers]
                                      (reset! body-sent body)
                                      (reset! headers-sent headers)))
          network (assoc n :xhr-io fake-xhrio)]

      (net/send network {:original 1} nil nil)

      (assertions
        "Request transform can replace body"
        (js->clj @body-sent) => "[\"^ \",\"~:new\",2]"
        "Request transform can replace headers"
        (js->clj @headers-sent) => {"other" 3}))))
