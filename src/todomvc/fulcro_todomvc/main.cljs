(ns fulcro-todomvc.main
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.networking.http-remote :as fhr]
    [com.fulcrologic.fulcro.networking.mock-server-remote :as mock-remote]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [edn-query-language.core :as eql]
    [fulcro-todomvc.ui :as ui]
    [fulcro-todomvc.server :as sapi]
    [taoensso.timbre :as log]))

(goog-define MOCK false)

(defonce app (app/fulcro-app {:remotes {:remote
                                        (if MOCK
                                          (mock-remote/mock-http-server {:parser (fn [req]
                                                                                   (sapi/parser {} req))})
                                          (fhr/fulcro-http-remote {:url "/api"}))}}))

(defn ^:export start []
  (log/info "mount")
  (app/mount! app ui/Root "app")
  (log/info "submit")
  (df/load! app [:list/id 1] ui/TodoList))

(comment
  (comp/registry-key ui/Root)
  (-> app ::app/runtime-atom deref)
  (-> app ::app/state-atom deref))