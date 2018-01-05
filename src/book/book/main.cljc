(ns book.main
  (:require
    [book.macros :refer [defexample deftool]]
    #?@(:cljs [
               [book.ui.d3-example :as d3-example]
               [book.ui.focus-example :as focus-example]
               [book.ui.hover-example :as hover-example]
               [book.ui.victory-example :as victory-example]
               [book.ui.clip-tool-example :as clip-tool-example]
               [book.queries.union-example-1 :as union-example-1]
               [book.queries.union-example-2 :as union-example-2]
               [book.queries.parsing-trace-example :as trace]
               book.queries.parsing-key-trace
               book.queries.naive-read
               book.queries.simple-property-read
               book.queries.parsing-simple-join
               book.queries.parsing-recursion-one
               book.queries.parsing-recursion-two
               book.queries.parsing-parameters
               book.queries.dynamic-queries
               book.queries.dynamic-query-parameters
               book.forms.forms-demo-1
               book.forms.forms-demo-2
               book.forms.forms-demo-3
               book.forms.whole-form-logic
               book.forms.full-stack-forms-demo
               [book.demos.autocomplete :as autocomplete]
               book.basic-i18n
               book.ui-routing
               book.tree-to-db
               book.merge-component
               book.html-converter
               book.server.morphing-example
               book.demos.cascading-dropdowns
               book.demos.component-localized-css
               book.demos.declarative-mutation-refresh
               book.demos.dynamic-ui-routing
               book.demos.dynamic-i18n
               book.demos.initial-app-state
               book.demos.legacy-load-indicators
               book.demos.loading-data-basics
               book.demos.loading-data-targeting-entities
               book.demos.loading-in-response-to-UI-routing
               book.demos.loading-indicators
               book.demos.paginating-large-lists-from-server
               book.demos.parallel-vs-sequential-loading
               book.demos.parent-child-ownership-relations
               book.demos.server-error-handling
               book.demos.server-query-security
               book.demos.server-return-values-as-data-driven-mutation-joins
               book.demos.server-targeting-return-values-into-app-state
               book.demos.server-return-values-manually-merging
               [book.server.ui-blocking-example :as ui-blocking]
               [fulcro-css.css :as css]])
    [book.ui.example-1 :as ui-ex-1]
    [fulcro.server :as server :refer [defquery-root]]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.network :as fcn]
    [fulcro.client.primitives :as prim :refer [defsc]]
    [fulcro.client.dom :as dom]
    [fulcro.client.logging :as log]
    [book.example-1 :as ex1]
    [fulcro.client.data-fetch :as df]
    [fulcro.client :as fc]))

#?(:clj (def clj->js identity))

(defn raise-response
  "For om mutations, converts {'my/mutation {:result {...}}} to {'my/mutation {...}}"
  [resp]
  (reduce (fn [acc [k v]]
            (if (and (symbol? k) (not (nil? (:result v))))
              (assoc acc k (:result v))
              (assoc acc k v)))
    {} resp))

(def parser (server/fulcro-parser))

(defonce latency (atom 100))

(server/defmutation set-server-latency [{:keys [delay]}]
  (action [env]
    (when (<= 100 delay 30000)
      (reset! latency delay))
    (with-meta {:server-control/delay @latency}
      {::nodelay true})))

(defquery-root :server-control
  (value [env params]
    {:server-control/delay @latency}))

#?(:cljs
   (defrecord MockNetwork []
     fcn/FulcroNetwork
     (send [this edn ok error]
       (log/info "Server received " edn)
       (try
         (let [resp        (raise-response (parser {} edn))
               skip-delay? (and (map? resp) (some-> resp first second meta ::nodelay))]
           ; simulates a network delay:
           (if skip-delay?
             (ok resp)
             (js/setTimeout (fn []
                              (log/info "Server responded with " resp)
                              (ok resp)) @latency)))
         (catch :default e
           (log/error "Exception thrown during parse:" e)
           (error {:type (type e)
                   :data (some-> (ex-data e) :body)}))))
     (start [this] this)))

(defsc ServerControl [this {:keys [:server-control/delay ui/hidden?]}]
  {:query         [:server-control/delay :ui/hidden?]
   :initial-state {:ui/hidden? true}
   :ident         (fn [] [:server-control/by-id :server])}
  (dom/div (clj->js {:style {:position        :fixed
                             :width           "180px"
                             :height          "130px"
                             :fontSize        "10px"
                             :backgroundColor :white
                             :zIndex          60000
                             :opacity         1.0
                             :padding         "3px"
                             :border          "3px groove white"
                             :top             0
                             :right           (if hidden? "-179px" "-1px")}})
    (dom/div nil "Latency: " (dom/span nil delay))
    (dom/br nil)
    (dom/button #js {:onClick #(prim/transact! this `[(set-server-latency {:delay ~(+ delay 500)})])} "Slower")
    (dom/button #js {:onClick #(prim/transact! this `[(set-server-latency {:delay ~(- delay 500)})])} "Faster")
    (dom/div #js {:onClick #(m/toggle! this :ui/hidden?)
                  :style   #js {:color           "grey"
                                :backgroundColor "lightgray"
                                :padding         "5px"
                                :paddingLeft     "10px"
                                :fontSize        "14px"
                                :position        :relative
                                :opacity         1.0
                                :transform       "rotate(-90deg) translate(12px, -100px)"}}
      "Server Controls")))

(defmutation set-server-latency [{:keys [delay]}]
  (remote [{:keys [ast state]}]
    (-> ast
      (m/returning state ServerControl))))

(def ui-server-control (prim/factory ServerControl))

(defsc ServerControlRoot [this {:keys [ui/react-key server-control]}]
  {:query         [:ui/react-key {:server-control (prim/get-query ServerControl)}]
   :initial-state {:server-control {}}}
  (dom/div #js {:key react-key}
    (ui-server-control server-control)))

#?(:cljs (defonce example-server (map->MockNetwork {})))

#?(:cljs (deftool ServerControlRoot "server-controls"
           :started-callback (fn [app]
                               (df/load app :server-control ServerControl {:marker false}))
           :networking {:remote example-server}))

#?(:cljs (css/upsert-css "example-css" book.macros/ExampleRoot))
#?(:cljs (defexample "Sample Example" ex1/Root "example-1"))
#?(:cljs (defexample "D3" d3-example/Root "ui-d3"))
#?(:cljs (defexample "Input Focus and React Refs/Lifecycle" focus-example/Root "focus-example"))
#?(:cljs (defexample "Drawing in a Canvas" hover-example/Root "hover-example"))
#?(:cljs (defexample "Using External React Libraries" victory-example/Root "victory-example"))
#?(:cljs (defexample "Image Clip Tool" clip-tool-example/Root "clip-tool-example"))
#?(:cljs (defexample "Unions to Select Type" union-example-1/Root "union-example-1"))
#?(:cljs (defexample "UI Blocking" ui-blocking/Root "ui-blocking-example" :networking book.main/example-server))

;; Parsing Chapter
#?(:cljs (defexample "Parser Tracing" trace/Root "parsing-trace-example"))
#?(:cljs (defexample "Parser Dispatch Trace" book.queries.parsing-key-trace/Root "parsing-key-trace"))
#?(:cljs (defexample "Naive Read" book.queries.naive-read/Root "naive-read"))
#?(:cljs (defexample "Parsing Simple Properties" book.queries.simple-property-read/Root "simple-property-read"))
#?(:cljs (defexample "Parsing a Simple Join" book.queries.parsing-simple-join/Root "parsing-simple-join"))
#?(:cljs (defexample "Recursive Part 1" book.queries.parsing-recursion-one/Root "parsing-recursion-one"))
#?(:cljs (defexample "Recursive Part 2" book.queries.parsing-recursion-two/Root "parsing-recursion-two"))
#?(:cljs (defexample "Parsing Parameters" book.queries.parsing-parameters/Root "parsing-parameters"))

;; Dynamic queries
#?(:cljs (defexample "Dynamic Query" book.queries.dynamic-queries/Root "dynamic-queries"))
#?(:cljs (defexample "Dyanmic Query Parameters" book.queries.dynamic-query-parameters/Root "dynamic-query-parameters"))

#?(:cljs (defexample "Internationalization Demo" book.basic-i18n/Root "basic-i18n"))
#?(:cljs (defexample "Routing Demo" book.ui-routing/Root "ui-routing" :networking book.main/example-server))
#?(:cljs (defexample "Tree to DB with Queries" book.tree-to-db/Root "tree-to-db" :networking book.main/example-server))
#?(:cljs (defexample "Merging with a Component" book.merge-component/Root "merge-component" :networking book.main/example-server))
#?(:cljs (defexample "HTML Converter" book.html-converter/Root "html-converter"))

;; Forms
#?(:cljs (defexample "Basic Form" book.forms.forms-demo-1/Root "forms-demo-1" :networking book.main/example-server))
#?(:cljs (defexample "Validated Form" book.forms.forms-demo-2/Root "forms-demo-2" :networking book.main/example-server))
#?(:cljs (defexample "Validated Form" book.forms.forms-demo-3/Root "forms-demo-3" :networking book.main/example-server))
#?(:cljs (defexample "Whole Form Logic" book.forms.whole-form-logic/Root "whole-form-logic" :networking book.main/example-server))
#?(:cljs (defexample "Full Stack Forms" book.forms.full-stack-forms-demo/Root "full-stack-forms-demo"
           :started-callback book.forms.full-stack-forms-demo/initialize
           :networking book.main/example-server))

#?(:cljs (defexample "Autocomplete" autocomplete/AutocompleteRoot "autocomplete-demo" :networking book.main/example-server))
#?(:cljs (defexample "Cascading Dropdowns" book.demos.cascading-dropdowns/Root "cascading-dropdowns" :networking book.main/example-server))
#?(:cljs (defexample "Component Localized CSS" book.demos.component-localized-css/Root "component-localized-css" :networking book.main/example-server))
#?(:cljs (defexample "Declarative Mutation Refresh" book.demos.declarative-mutation-refresh/Root "declarative-mutation-refresh" :networking book.main/example-server))
#?(:cljs (defexample "dynamicUiRouting" book.demos.dynamic-ui-routing/Root "dynamic-ui-routing"
           :started-callback book.demos.dynamic-ui-routing/application-loaded
           :networking book.main/example-server))

#?(:cljs (defexample "Dynamically Loaded Locales" book.demos.dynamic-i18n/Root "dynamic-i18n"
           :networking book.main/example-server
           :started-callback (fn [] (cljs.loader/set-loaded! :entry-point))))

#?(:cljs (defexample "Loading Data Basics" book.demos.loading-data-basics/Root "loading-data-basics" :networking book.main/example-server :started-callback book.demos.loading-data-basics/initialize))
#?(:cljs (defexample "Loading Data and Targeting Entities" book.demos.loading-data-targeting-entities/Root "loading-data-targeting-entities" :networking book.main/example-server))
#?(:cljs (defexample "Loading In Response To UI Routing" book.demos.loading-in-response-to-UI-routing/Root "loading-in-response-to-UI-routing" :networking book.main/example-server))
#?(:cljs (defexample "Loading Indicators" book.demos.loading-indicators/Root "loading-indicators" :networking book.main/example-server))
#?(:cljs (defexample "Initial State" book.demos.initial-app-state/Root "initial-app-state" :networking book.main/example-server))
#?(:cljs (defexample "Legacy Load Indicators" book.demos.legacy-load-indicators/Root "legacy-load-indicators" :networking book.main/example-server))
#?(:cljs (defexample "Paginating Lists From Server" book.demos.paginating-large-lists-from-server/Root "paginating-large-lists-from-server"
           :started-callback book.demos.paginating-large-lists-from-server/initialize
           :networking book.main/example-server))

#?(:cljs (defexample "Parallel vs. Sequential Loading" book.demos.parallel-vs-sequential-loading/Root "parallel-vs-sequential-loading" :networking book.main/example-server))
#?(:cljs (defexample "Parent-Child Ownership" book.demos.parent-child-ownership-relations/Root "parent-child-ownership-relations" :networking book.main/example-server))

#?(:cljs (defexample "Error Handling" book.demos.server-error-handling/Root "server-error-handling"
           :networking book.main/example-server))
#?(:cljs (defexample "Query Security" book.demos.server-query-security/Root "server-query-security"
           :networking book.main/example-server))
#?(:cljs (defexample "Return Values and Mutation Joins" book.demos.server-return-values-as-data-driven-mutation-joins/Root "server-return-values-as-data-driven-mutation-joins"

           :networking book.main/example-server))
#?(:cljs (defexample "Manually Merging Server Mutation Return Values" book.demos.server-return-values-manually-merging/Root "server-return-values-manually-merging"
           :mutation-merge book.demos.server-return-values-manually-merging/merge-return-value
           :networking book.main/example-server))
#?(:cljs (defexample "Targeting Mutation Return Values" book.demos.server-targeting-return-values-into-app-state/Root "server-targeting-return-values-into-app-state" :networking book.main/example-server))

