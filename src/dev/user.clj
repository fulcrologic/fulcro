(ns user
  (:require
    [clojure.tools.namespace.repl :refer [set-refresh-dirs]]))

(set-refresh-dirs "src/main" "src/test" "src/dev" "src/todomvc")
