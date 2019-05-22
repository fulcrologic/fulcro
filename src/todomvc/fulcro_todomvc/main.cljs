(ns fulcro-todomvc.main
  (:require
    [clojure.core.async :as async]
    [com.fulcrologic.fulcro.algorithms.application-helpers :as ah]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.component-middleware :as cmw]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.rendering.keyframe-render :as kr]
    [edn-query-language.core :as eql]
    [fulcro-todomvc.api :as api]
    [fulcro-todomvc.server :as sapi]
    [fulcro-todomvc.ui :as ui]
    [goog.object :as gobj]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro-css.css :as css]))

(defn handle-remote [{:keys [::txn/ast ::txn/result-handler] :as send-node}]
  (log/info "Remote got AST: " ast)
  (let [query (eql/ast->query ast)]
    (async/go
      (result-handler
        (if-let [result (async/<! (sapi/parser {} query))]
          {:status-code 200 :body result}
          {:status-code 500 :body "Parser Failed to return a value"})))))

(defonce app (app/fulcro-app {:remotes {:remote handle-remote}}))

(defn ^:export start []
  (log/info "mount")
  (app/mount! app ui/Root "app")
  (log/info "submit")
  (df/load! app [:list/id 1] ui/TodoList))

(comment
  (comp/registry-key ui/Root)
  (-> app ::app/runtime-atom deref)
  (-> app ::app/state-atom deref))