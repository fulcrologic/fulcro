(ns fulcro.client.routing
  (:require [fulcro.client.mutations :as m]
            [fulcro.client.core]
            [fulcro.client.util :refer [conform!]]
            [clojure.spec.alpha :as s]
            [fulcro.client.logging :as log]))

(s/def ::mutation-args (s/cat
                         :sym symbol?
                         :doc (s/? string?)
                         :arglist vector?
                         :body (s/+ (constantly true))))

(defn- emit-union-element [sym ident-fn kws-and-screens]
  (try
    (let [query (reduce (fn [q {:keys [kw sym]}] (assoc q kw `(om.next/get-query ~sym))) {} kws-and-screens)
          first-screen (-> kws-and-screens first :sym)
          screen-render (fn [cls] `((om.next/factory ~cls {:keyfn (fn [props#] ~(name cls))}) (om.next/props ~'this)))
          render-stmt (reduce (fn [cases {:keys [kw sym]}]
                                (-> cases
                                    (conj kw (screen-render sym)))) [] kws-and-screens)]
      `(om.next/defui ~(vary-meta sym assoc :once true)
         ~'static fulcro.client.core/InitialAppState
         (~'initial-state [~'clz ~'params] (fulcro.client.core/get-initial-state ~first-screen ~'params))
         ~'static om.next/Ident
         ~ident-fn
         ~'static om.next/IQuery
         (~'query [~'this] ~query)
         ~'Object
         (~'render [~'this]
           (let [page# (first (om.next/get-ident ~'this))]
             (case page#
               ~@render-stmt
               (om.dom/div nil (str "Cannot route: Unknown Screen " page#)))))))
    (catch Exception e `(def ~sym (log/error "BROKEN ROUTER!")))))

(defn- emit-router [router-id sym union-sym]
  `(om.next/defui ~(vary-meta sym assoc :once true)
     ~'static fulcro.client.core/InitialAppState
     (~'initial-state [~'clz ~'params] {:id ~router-id :current-route (fulcro.client.core/get-initial-state ~union-sym {})})
     ~'static om.next/Ident
     (~'ident [~'this ~'props] [:fulcro.client.routing.routers/by-id ~router-id])
     ~'static om.next/IQuery
     (~'query [~'this] [:id {:current-route (om.next/get-query ~union-sym)}])
     ~'Object
     (~'render [~'this]
       ((om.next/factory ~union-sym) (:current-route (om.next/props ~'this))))))

(s/def ::router-args (s/cat
                       :sym symbol?
                       :router-id keyword?
                       :ident-fn (constantly true)
                       :kws-and-screens (s/+ (s/cat :kw keyword? :sym symbol?))))

(defmacro ^{:doc      "Generates a component with a union query that can route among the given screen, which MUST be
in cljc files. The first screen listed will be the 'default' screen that the router will be initialized to show.

- All screens *must* implement InitialAppState
- All screens *must* have a UI query
- Add screens *must* have state that the ident-fn can use to determine which query to run. E.g. the left member
of running (ident-fn Screen initial-screen-state) => [:kw-for-screen some-id]
"
            :arglists '([sym router-id ident-fn & kws-and-screens])} defrouter
  [& args]
  (let [{:keys [sym router-id ident-fn kws-and-screens]} (conform! ::router-args args)
        union-sym (symbol (str (name sym) "-Union"))]
    `(do
       ~(emit-union-element union-sym ident-fn kws-and-screens)
       ~(emit-router router-id sym union-sym))))

