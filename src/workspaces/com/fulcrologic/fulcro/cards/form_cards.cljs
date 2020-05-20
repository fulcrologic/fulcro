(ns com.fulcrologic.fulcro.cards.form-cards
  (:require
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.fulcrologic.fulcro.mutations :as m]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [edn-query-language.core :as eql]))

(defonce remote {:transmit! (fn transmit! [_ {:keys [::txn/ast ::txn/result-handler ::txn/update-handler] :as send-node}]
                              (let [edn        (eql/ast->query ast)
                                    ok-handler (fn [result]
                                                 (try
                                                   (result-handler (select-keys result #{:transaction :status-code :body :status-text}))
                                                   (catch :default e
                                                     (log/error e "Result handler failed with an exception."))))]
                                (ok-handler {:transaction edn :status-code 200 :body {[:form/id 42] {:form/id 42 :form/name "Sam"}}})))})

(defsc Form [this {:form/keys [name] :as props}]
  {:query         [:form/id :form/name fs/form-config-join]
   :ident         :form/id
   :initial-state {:form/id   1
                   :form/name "Sam"}
   :form-fields   #{:form/name}
   :pre-merge     (fn [{:keys [data-tree]}]
                    (fs/add-form-config Form data-tree))}
  (dom/div
    (dom/button {:onClick #(df/load! this [:form/id 42] Form)} "Load!")
    (dom/label "Name")
    (dom/input {:value    name
                :onChange #(m/set-string! this :form/name :event %)})))

(ws/defcard form-pre-merge-sample
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? true
     ::ct.fulcro/root       Form
     ::ct.fulcro/app        {:remotes {:remote remote}}}))
