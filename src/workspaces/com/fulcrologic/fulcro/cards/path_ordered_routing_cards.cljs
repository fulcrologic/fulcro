(ns com.fulcrologic.fulcro.cards.path-ordered-routing-cards
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom :as dom]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr :refer [defrouter]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [taoensso.timbre :as log]))

(pc/defmutation b-mutation-ss [env input]
  {::pc/sym    `b-mutation
   ::pc/output [:server-value]}
  (log/spy :info "Server-side b returning"
    {:server-value (rand-int 100)}))

(pc/defmutation b1-mutation-ss [env input]
  {::pc/sym `b1-mutation}
  (log/info "Server-side b1"))

(pc/defmutation b2-mutation-ss [env input]
  {::pc/sym `b2-mutation}
  (log/info "Server-side b2"))

(defmutation a1-mutation [_]
  (action [{:keys [state]}]
    (log/info "a1-mutation")))

(defsc A1 [this {:keys [:id] :as props}]
  {:query         [:id]
   :route-segment ["a1"]
   :will-enter    (fn [app params]
                    (dr/route-with-path-ordered-transaction [:id "a1"]
                      [(a1-mutation {:n 1})]))
   :initial-state {:id "a1"}
   :ident         :id}
  (let [parent comp/*parent*]
    (dom/div "A1"
      (dom/button
        {:onClick #(dr/change-route-relative! this this [:.. "a2"])}
        "Go to sibling A2"))))

(defmutation a2-mutation [_]
  (action [{:keys [state]}]
    (log/info "a2-mutation")))

(defsc A2 [this {:keys [:id] :as props}]
  {:query         [:id]
   :route-segment ["a2"]
   :will-enter    (fn [app params]
                    (dr/route-with-path-ordered-transaction [:id "a2"]
                      [(a2-mutation {:n 2})]))
   :initial-state {:id "a2"}
   :ident         :id}
  (dom/div "A2"))

(defmutation b1-mutation [_]
  (action [{:keys [state]}]
    (log/info "b1-mutation sees server value" (:server-value @state)))
  (remote [_] true))

(defsc B1 [this {:keys [:id] :as props}]
  {:query               [:id]
   :route-segment       ["b1"]
   :will-enter          (fn [app params]
                          (dr/route-with-path-ordered-transaction [:id "b1"]
                            [(b1-mutation {:n 3})]))
   :will-leave          (fn [cls props] (log/info "B1 will leave"))
   :allow-route-change? (fn [c] (log/info "B1 allow route change?") true)
   :initial-state       {:id "b1"}
   :ident               :id}
  (dom/div "B1"))

(defmutation b2-mutation [_]
  (action [{:keys [state]}]
    (log/info "b2-mutation sees server value" (:server-value @state)))
  (remote [_] true))

(defsc B2 [this {:keys [:id] :as props}]
  {:query               [:id]
   :route-segment       ["b2"]
   :will-enter          (fn [app params]
                          (dr/route-with-path-ordered-transaction [:id "b2"]
                            [(b1-mutation {:n 3})]))
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

(defmutation b-mutation [_]
  (action [{:keys [state]}]
    (log/info "b-mutation"))
  (ok-action [{:keys [result state]}]
    (swap! state assoc :server-value (get-in result [:body `b-mutation :server-value])))
  (remote [env] true))

(defsc B [this {:keys [id router] :as props}]
  {:query         [:id {:router (comp/get-query BRouter)}]
   :ident         :id
   :route-segment ["b"]
   :will-enter    (fn [app params]
                    (dr/route-with-path-ordered-transaction [:id "b"]
                      [(b-mutation {:n 3})]))
   :initial-state {:id "b" :router {}}}
  (dom/div {}
    (dom/h2 "B")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this B ["b1"]))} "B1 (relative)")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this B ["b2"]))} "B2 (relative)")
    (ui-b-router router)))

(def ui-b (comp/factory B {:keyfn :id}))

(defmutation a-mutation [_]
  (action [{:keys [state]}]
    (log/info "a-mutation")))

(defsc A [this {:keys [id router] :as props}]
  {:query         [:id {:router (comp/get-query ARouter)}]
   :route-segment ["a"]
   :will-enter    (fn [app params]
                    (dr/route-with-path-ordered-transaction [:id "a"]
                      [(a-mutation {:n 5})]))
   :initial-state {:id "a" :router {}}
   :ident         :id}
  (dom/div {}
    (dom/h2 "A")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this A ["a1"]))} "A1 (relative)")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this A ["a2"]))} "A2 (relative)")
    (ui-a-router router)))

(def ui-a (comp/factory A {:keyfn :id}))

(defrouter RootRouter [this props]
  {:router-targets [A B]})
(def ui-router (comp/factory RootRouter))

(defsc Root [this {:root/keys [:router] :as props}]
  {:query         [{:root/router (comp/get-query RootRouter)}]
   :initial-state {:root/router {}}}
  (dom/div
    (dom/button {:onClick (fn [] (dr/change-route-relative! this Root ["a" "a1"]))} "A1 ")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this Root ["b" "b2"]))} "B2 ")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this Root ["a"]))} "A")
    (dom/button {:onClick (fn [] (dr/change-route-relative! this Root ["b"]))} "B")
    (ui-router router)))

(def resolvers [b-mutation-ss
                b1-mutation-ss
                b2-mutation-ss])

(def pathom-parser (p/parser {::p/env     {::p/reader                 [p/map-reader
                                                                       pc/reader2
                                                                       pc/open-ident-reader]
                                           ::pc/mutation-join-globals [:tempids]}
                              ::p/mutate  pc/mutate
                              ::p/plugins [(pc/connect-plugin {::pc/register [resolvers]})
                                           (p/post-process-parser-plugin p/elide-not-found)
                                           p/error-handler-plugin]}))

(ws/defcard routing-side-effects
  (let [process-eql (fn [eql] (async/go (pathom-parser {} eql)))]
    (ct.fulcro/fulcro-card
      {::ct.fulcro/wrap-root? false
       ::ct.fulcro/root       Root
       ::ct.fulcro/app        {:remotes           {:remote (mock-http-server {:parser process-eql})}
                               :client-will-mount (fn [app]
                                                    (dr/initialize! app)
                                                    (dr/change-route! app ["a" "a1"]))}})))
