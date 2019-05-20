(ns fulcro-todomvc.main
  (:require
    [fulcro-todomvc.ui :as ui]
    [com.fulcrologic.fulcro.application :as app]))

(defonce app (app/fulcro-app))

(defn ^:export start []
  (app/mount! app ui/Root "app"))

(comment
  app

  )