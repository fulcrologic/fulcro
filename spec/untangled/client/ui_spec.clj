(ns untangled.client.ui-spec
  (:require
    [clojure.spec :as s]
    [om.next :as om]
    [untangled-spec.core :refer
     [specification behavior assertions when-mocking]]
    [untangled.client.ui :as ui]))

(specification "untangled.client.ui/defui"
  (assertions "injects a deref implementation and wraps the render method"
    (macroexpand
      '(untangled.client.ui/defui Root {:factory :opts}
         static om/Ident (ident [this] [:ui :singleton])
         static om/IQuery (query [this] [:some :query])
         Object (render [this] (dom/div nil "hello world"))))
    =fn=> #(and
             (not (:s/problems %))
             (re-find #":some :query" (str %))
             (re-find #":ui :singleton" (str %))
             (re-find #"IDeref" (str %))
             (re-find #"untangled.client.ui/wrap-render" (str %)))
    "set-defui-xform! injects functions inbetween defui's conform and unform steps"
    (do
      (ui/set-defui-xform!
        (fn [ctx body]
          (update-in body ["Object" :methods "render" :body]
            #(cons '(js/console.log "IT WORKS!") %))))
      (macroexpand
        '(untangled.client.ui/defui Root {:factory :opts}
           static om/Ident (ident [this] [:ui :singleton])
           static om/IQuery (query [this] [:some :query])
           Object (render [this] (dom/div nil "hello world")))))
    =fn=> #(and
             (not (:s/problems %))
             (re-find #"IT WORKS!" (str %)))))
