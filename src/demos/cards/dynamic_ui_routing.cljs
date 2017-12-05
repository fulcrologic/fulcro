(ns cards.dynamic-ui-routing
  (:require [fulcro.client.routing :as r]
            [fulcro.client.dom :as dom]
            [fulcro.client.primitives :as prim :refer [defsc InitialAppState initial-state]]
            [cljs.loader :as loader]))

(defsc Login [this {:keys [label login-prop]}]
  {:initial-state (fn [params] {r/dynamic-route-key :login :label "LOGIN" :login-prop "login data"})
   :ident         (fn [] [:login :singleton])
   :query         [r/dynamic-route-key :label :login-prop]}
  (dom/div #js {:style #js {:backgroundColor "green"}}
    (str label " " login-prop)))

(defsc NewUser [this {:keys [label new-user-prop]}]
  {:initial-state (fn [params] {r/dynamic-route-key :new-user :label "New User" :new-user-prop "new user data"})
   :ident         (fn [] [:new-user :singleton])
   :query         [r/dynamic-route-key :label :new-user-prop]}
  (dom/div #js {:style #js {:backgroundColor "skyblue"}}
    (str label " " new-user-prop)))

(defsc Root [this {:keys [ui/react-key top-router :fulcro.client.routing/pending-route]}]
  {:initial-state (fn [params] (merge
                                 (r/routing-tree
                                   (r/make-route :main [(r/router-instruction :top-router [:main :singleton])])
                                   (r/make-route :login [(r/router-instruction :top-router [:login :singleton])])
                                   (r/make-route :new-user [(r/router-instruction :top-router [:new-user :singleton])]))
                                 {:top-router (prim/get-initial-state r/DynamicRouter {:id :top-router})}))
   :query         [:ui/react-key {:top-router (r/get-dynamic-router-query :top-router)}
                   :fulcro.client.routing/pending-route]}
  (let [my-query (prim/get-query Root (prim/app-state (prim/get-reconciler this)))]
    (dom/div #js {:key react-key}
      ; Sample nav mutations
      (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :main})])} "Main") " | "
      (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :new-user})])} "New User") " | "
      (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :login})])} "Login") " | "
      (dom/div nil (if pending-route "Loading" "Done"))
      (r/ui-dynamic-router top-router))))

; these would happen as a result of module loads:
(defn application-loaded [{:keys [reconciler]}]
  ; Let the dynamic router know that two of the routes are already loaded.
  (prim/transact! reconciler `[(r/install-route {:target-kw :new-user :component ~NewUser})
                               (r/install-route {:target-kw :login :component ~Login})
                               (r/route-to {:handler :login})])
  (loader/set-loaded! :entry-point))

