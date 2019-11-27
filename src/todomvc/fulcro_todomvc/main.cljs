(ns fulcro-todomvc.main
  (:require
    [fulcro-todomvc.ui :as ui]
    [com.fulcrologic.fulcro.rendering.keyframe-render2 :as kr2]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(defonce app (app/fulcro-app {:remotes           {:remote (http/fulcro-http-remote {})}
                              :optimized-render! kr2/render!}))

(defn start []
  (app/mount! app ui/Root "app")
  (df/load! app :com.wsscode.pathom/trace nil)
  (df/load! app [:list/id 1] ui/TodoList))
