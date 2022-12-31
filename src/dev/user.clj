(ns user
  (:require
    [clojure.tools.namespace.repl :as tools-ns :refer [set-refresh-dirs]]))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/todomvc")
