(ns com.fulcrologic.fulcro.networking.http-kit-driver
  "An HTTP driver adapter for http-kit. Suitable for passing as `:http-driver` to
   `fulcro-http-remote`.

   Requires http-kit to be on your classpath."
  (:require
    [org.httpkit.client :as http]))

(defn make-http-kit-driver
  "Creates a synchronous HTTP driver using http-kit.

   `default-opts` is an optional map of default http-kit request options (e.g. `:timeout`,
   `:keepalive`) that will be merged under each request."
  ([] (make-http-kit-driver {}))
  ([default-opts]
   (fn [{:keys [url method headers body]}]
     (let [resp @(http/request (merge default-opts
                                 {:url     url
                                  :method  (or method :post)
                                  :headers headers
                                  :body    body}))]
       {:status  (:status resp)
        :headers (:headers resp)
        :body    (:body resp)
        :error   (:error resp)}))))
