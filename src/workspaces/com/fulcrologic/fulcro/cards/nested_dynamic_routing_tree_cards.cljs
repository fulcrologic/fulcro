(ns com.fulcrologic.fulcro.cards.nested-dynamic-routing-tree-cards
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [taoensso.timbre :as log]))

(defsc A1 [this {:keys [:id] :as props}]
  {:query               [:id]
   :route-segment       ["a1"]
   :will-enter          (fn [app params]
                          (log/info "A1 will enter")
                          (dr/route-deferred [:id "a1"]
                            (fn [] (js/setTimeout #(dr/target-ready! app [:id "a1"]) 300))))
   :will-leave          (fn [cls props] (log/info "A1 will leave"))
   :allow-route-change? (fn [c] (log/info "A1 allow route change?") true)
   :initial-state       {:id "a1"}
   :ident               :id}
  (let [parent comp/*parent*]
    (dom/div "A1"
      (dom/button
        {:onClick #(dr/change-route-relative! this this [:.. "a2"])}
        "Go to sibling A2"))))

(defsc A2 [this {:keys [:id] :as props}]
  {:query               [:id]
   :route-segment       ["a2"]
   :will-enter          (fn [app params]
                          (log/info "A2 will enter")
                          (dr/route-deferred [:id "a2"]
                            (fn [] (js/setTimeout #(dr/target-ready! app [:id "a2"]) 300))))
   :will-leave          (fn [cls props] (log/info "A2 will leave"))
   :allow-route-change? (fn [c] (log/info "A2 allow route change?") true)
   :initial-state       {:id "a2"}
   :ident               :id}
  (dom/div "A2"))

(defsc B1 [this {:keys [:id] :as props}]
  {:query               [:id]
   :route-segment       ["b1"]
   :will-enter          (fn [app params]
                          (log/info "B1 will enter")
                          (dr/route-deferred [:id "b1"]
                            (fn [] (js/setTimeout #(dr/target-ready! app [:id "b1"]) 300))))
   :will-leave          (fn [cls props] (log/info "B1 will leave"))
   :allow-route-change? (fn [c] (log/info "B1 allow route change?") true)
   :initial-state       {:id "b1"}
   :ident               :id}
  (dom/div "B1"))

(defsc B2 [this {:keys [:id] :as props}]
  {:query               [:id]
   :route-segment       ["b2"]
   :will-enter          (fn [app params]
                          (log/info "B2 will enter")
                          (dr/route-deferred [:id "b2"]
                            (fn [] (js/setTimeout #(dr/target-ready! app [:id "b2"]) 300))))
   :will-leave          (fn [cls props] (log/info "B2 will leave"))
   :allow-route-change? (fn [c] (log/info "B2 allow route change?") true)
   :initial-state       {:id "b2"}
   :ident               :id}
  (dom/div "B2"))

(defrouter ARouter [this props]
  {:router-targets [A1 A2]})
(def ui-a-router (comp/factory ARouter))

(defrouter BRouter [this props]
  {:router-targets [B1 B2]})
(def ui-b-router (comp/factory BRouter))

(defsc B [this {:keys [id router] :as props}]
  {:query               [:id {:router (comp/get-query BRouter)}]
   :ident               :id
   :route-segment       ["b"]
   :will-enter          (fn [app params]
                          (log/info "B will enter")
                          (dr/route-immediate [:id "b"]))
   :will-leave          (fn [cls props] (log/info "B will leave"))
   :allow-route-change? (fn [c] (log/info "B allow route change?") true)
   :initial-state       {:id "b" :router {}}}
  (dom/div {}
    (dom/h2 "B")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this B ["b1"]))} "B1 (relative)")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this B ["b2"]))} "B2 (relative)")
    (ui-b-router router)))

(def ui-b (comp/factory B {:keyfn :id}))

(defsc A [this {:keys [id router] :as props}]
  {:query               [:id {:router (comp/get-query ARouter)}]
   :route-segment       ["a"]
   :will-enter          (fn [app params]
                          (log/info "A will enter")
                          (dr/route-immediate [:id "a"]))
   :will-leave          (fn [cls props] (log/info "A will leave"))
   :allow-route-change? (fn [c] (log/info "A allow route change?") true)
   :initial-state       {:id "a" :router {}}
   :ident               :id}
  (dom/div {}
    (dom/h2 "A")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this A ["a1"]))} "A1 (relative)")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this A ["a2"]))} "A2 (relative)")
    (ui-a-router router)))

(def ui-a (comp/factory A {:keyfn :id}))

(defrouter ClassRootRouter [this {:keys [route-factory route-props]}]
  {:router-targets      [A B]
   :always-render-body? true}

  (when route-factory
    (dom/div {}
      (dom/p (str 'ClassRootRouter " targeting: " (comp/component-name (dr/current-route-class this))))
      (route-factory (comp/computed route-props (comp/get-computed this))))))

(def ui-class-router (comp/factory ClassRootRouter))

(defrouter HooksRootRouter [this {:keys [route-factory route-props]}]
  {:router-targets      [A B]
   :use-hooks?          true
   :always-render-body? true}

  (hooks/use-effect
    (fn []
      (log/info (str "This is demo number: " (random-uuid)))
      js/undefined)
    [])

  (when route-factory
    (dom/div {}
      (dom/p (str 'HooksRootRouter " targeting: " (comp/component-name (dr/current-route-class this))))
      (route-factory (comp/computed route-props (comp/get-computed this))))))

(def ui-hooks-router (comp/factory HooksRootRouter))

(def FunctionCallRouter (dr/dynamic-router ::FunctionCallRouter
                          [A B]
                          {:always-render-body? true
                           :render              (fn [this {:keys [route-factory route-props]}]
                                                  (when route-factory
                                                    (dom/div {}
                                                      (dom/p (str 'FunctionCallRouter ", a router created with a function call, is targeting: " (comp/component-name (dr/current-route-class this))))
                                                      (route-factory (comp/computed route-props (comp/get-computed this))))))}))

(def ui-function-call-router (comp/factory FunctionCallRouter))

(def FunctionCallRouterHooksEnabled (dr/dynamic-router ::FunctionCallRouterHooksEnabled
                                      [A B]
                                      {:always-render-body? true
                                       :use-hooks?          true
                                       :render              (fn [this {:keys [route-factory route-props]}]
                                                              (hooks/use-effect
                                                                (fn []
                                                                  (log/info (str "Starting " (comp/component-name FunctionCallRouterHooksEnabled)))
                                                                  js/undefined)
                                                                [])
                                                              (when route-factory
                                                                (dom/div {}
                                                                  (dom/p (str 'FunctionCallRouterHooksEnabled ", a router created with a function call, is targeting: " (comp/component-name (dr/current-route-class this))))
                                                                  (route-factory (comp/computed route-props (comp/get-computed this))))))}))

(def ui-function-call-router-hooks-enabled (comp/factory FunctionCallRouterHooksEnabled))

(defsc NavigationAndRouter
  "Common between all Roots that contain the router types being tested"
  [this {::keys [router relative-class-or-instance factory]}]
  {}
  (dom/div
    (dom/button {:onClick (fn [] (dr/change-route-relative! this relative-class-or-instance ["a" "a1"]))} "A1")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this relative-class-or-instance ["b" "b2"]))} "B2")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this relative-class-or-instance ["a"]))} "A")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this relative-class-or-instance ["b"]))} "B")
    (factory router)))

(def ui-navigation-and-router (comp/factory NavigationAndRouter))

(defsc ClassRoot
  "ClassRootRouter is a (default) React Class component"
  [_this {:root/keys [:router]}]
  {:query         [{:root/router (comp/get-query ClassRootRouter)}]
   :initial-state {:root/router {}}}
  (ui-navigation-and-router {::router router ::relative-class-or-instance ClassRoot ::factory ui-class-router}))

(defsc ClassRootWithFunctionalRouter
  "FunctionCallRouter is created with a function call, and created as a React class component"
  [_this {:root/keys [:router]}]
  {:query         [{:root/router (comp/get-query FunctionCallRouter)}]
   :initial-state {:root/router {}}}
  (ui-navigation-and-router {::router router ::relative-class-or-instance FunctionCallRouter ::factory ui-function-call-router}))

(defsc HooksRoot
  "ClassRootRouter is a React hooks component, set by :use-hooks?"
  [this {:root/keys [:router] :as props}]
  {:query         [{:root/router (comp/get-query HooksRootRouter)}]
   :initial-state {:root/router {}}}
  (ui-navigation-and-router {::router router ::relative-class-or-instance HooksRoot ::factory ui-hooks-router}))

(defsc HooksRootWithFunctionalRouter
  "FunctionCallRouter is created with a function call, and created as a React class component"
  [_this {:root/keys [:router]}]
  {:query         [{:root/router (comp/get-query FunctionCallRouterHooksEnabled)}]
   :initial-state {:root/router {}}}
  (ui-navigation-and-router {::router router ::relative-class-or-instance FunctionCallRouterHooksEnabled ::factory ui-function-call-router-hooks-enabled}))

(defonce SPA (atom nil))

(defn client-will-mount [app]
  (reset! SPA app)
  (dr/initialize! app)
  (dr/change-route! app ["a" "a1"]))

(ws/defcard nested-routing-class-demo
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       ClassRoot
     ::ct.fulcro/app        {:client-will-mount client-will-mount}}))

(ws/defcard nested-routing-class-with-functional-router-demo
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       ClassRootWithFunctionalRouter
     ::ct.fulcro/app        {:client-will-mount client-will-mount}}))

(ws/defcard nested-routing-hooks-demo
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       HooksRoot
     ::ct.fulcro/app        {:client-will-mount client-will-mount}}))

(ws/defcard nested-routing-hooks-with-functional-router-demo
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       HooksRootWithFunctionalRouter
     ::ct.fulcro/app        {:client-will-mount client-will-mount}}))
