(ns user
  (:require
    [clojure.test :refer :all]
    [clojure.repl :refer [doc source]]
    [clojure.tools.namespace.repl :as tools-ns :refer [disable-reload! refresh clear set-refresh-dirs]]
    [expound.alpha :as expound]
    [clojure.spec.alpha :as s]
    [edn-query-language.core :as eql]))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/todomvc"
  "../fulcro-websockets/src/main")
(alter-var-root #'s/*explain-out* (constantly expound/printer))

