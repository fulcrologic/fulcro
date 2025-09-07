(ns fulcro.routing.dynamic-browser
  (:require
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.routing.system :as sys]
    [com.fulcrologic.fulcro.routing.system :as rsys]
    [com.fulcrologic.fulcro.routing.dynamic-routing-browser-system :as drb]
    [taoensso.timbre :as log]))

(defonce app (drb/install-dynamic-routing-browser-system! (app/fulcro-app {})))

(defn allow-routing? [this]
  (some-> this comp/props :ui/allow-routing? boolean))

(m/defmutation update-a-stuff [{:keys [x]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:component/id :A :a/stuff] (cond-> x
                                                        (string? x) (parse-long)))))

(defsc A [this {:a/keys [stuff]}]
  {:query               [:a/stuff
                         [:ui/allow-routing? '_]]
   :ident               (fn [] [:component/id :A])
   :route-segment       ["a" :x]
   :allow-route-change? allow-routing?
   :will-enter          (fn [app {:keys [x] :as params}] (log/info "A params" params)
                          (let [i (comp/get-ident A {})]
                            (dr/route-deferred i
                              (fn []
                                (comp/transact! app [(update-a-stuff {:x x})
                                                     (dr/target-ready {:target i})])))))
   :initial-state       {:a/stuff 1}}
  (dom/div (str "A" stuff)))

(defsc B [this {:b/keys [stuff]}]
  {:query               [:b/stuff
                         [:ui/allow-routing? '_]]
   :ident               (fn [] [:component/id :B])
   :route-segment       ["b"]
   :will-enter          (fn [app params] (log/info "B params" params)
                          (dr/route-deferred (comp/get-ident B {})
                            (fn []
                              (log/info "B I/O")
                              (dr/target-ready! app (comp/get-ident B {})))))
   :allow-route-change? allow-routing?
   :initial-state       {:b/stuff 1}}
  (dom/div (str "B" stuff)))

(defsc C [this {:c/keys [stuff]}]
  {:query               [:c/stuff
                         [:ui/allow-routing? '_]]
   :ident               (fn [] [:component/id :C])
   :route-segment       ["c"]
   :will-enter          (fn [_ params] (log/info "C params" params)
                          (dr/route-immediate (comp/get-ident C {})))
   :allow-route-change? allow-routing?
   :initial-state       {:c/stuff 1}}
  (dom/div (str "C" stuff)))

(dr/defrouter Router [this props]
  {:router-targets [A B C]})

(def ui-router (comp/factory Router))

(m/defmutation toggle-routing [_]
  (action [{:keys [state]}]
    (swap! state update :ui/allow-routing? not)))

(defsc Root [this {:ui/keys [allow-routing?]
                   :keys    [router]}]
  {:query         [:ui/allow-routing?
                   {:router (comp/get-query Router)}]
   :initial-state {:router            {}
                   :ui/allow-routing? true}}
  (dom/div
    (dom/pre {}
      (str
        "Current route: " (sys/current-route app)
        ))
    (dom/button {:onClick (fn [] (comp/transact! this [(toggle-routing)]))} (str "Allow? " allow-routing?))
    (dom/button {:onClick (fn [] (sys/route-to! this {:target `A
                                                      :params {:x (rand-int 11)
                                                               :y (rand-int 6)}}))} "Goto A")
    (dom/button {:onClick (fn [] (sys/route-to! this {:target `B
                                                      :params {:x 33}}))} "Goto B")
    (dom/button {:onClick (fn [] (sys/route-to! this {:target `C
                                                      :params {:x 34}}))} "Goto C")
    (ui-router router)))

(defn refresh []
  (app/force-root-render! app))

(defn init []
  (log/info "Mounting")
  (app/mount! app Root "app")
  (sys/route-to! app {:target A
                      :params {:start? true}}))
