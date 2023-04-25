(ns com.fulcrologic.fulcro.react.version18
  #?@(:cljs
      [(:require-macros com.fulcrologic.fulcro.react.version18)
       (:require
         [com.fulcrologic.fulcro.application]
         ["react-dom/client" :as dom-client]
         ["react" :as react])]))

(defn react18-options
  "Returns the options that need to be passed to the Fulcro app constructor. See also `with-react18`."
  []
  #?(:cljs
     (let [reactRoot (volatile! nil)]
       {:render-root!  (fn [ui-root mount-node]
                         (when-not @reactRoot
                           (vreset! reactRoot (dom-client/createRoot mount-node)))
                         (.render ^js @reactRoot ui-root)
                         @reactRoot)
        :hydrate-root! (fn [ui-root mount-node] (dom-client/hydrateRoot mount-node ui-root))})
     :clj {}))

(defn with-react18
  "Alters the rendering to support React 18"
  [app]
  #?(:cljs (let [reactRoot (volatile! nil)]
             (-> app
                 (assoc ::reactRoot reactRoot)
                 (assoc-in
                  [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/render-root!]
                  (fn [ui-root mount-node]
                    (when-not @reactRoot
                      (vreset! reactRoot (dom-client/createRoot mount-node)))
                    (.render ^js @reactRoot ui-root)
                    @reactRoot))
                 (assoc-in
                  [:com.fulcrologic.fulcro.application/algorithms :com.fulcrologic.fulcro.algorithm/hydrate-root!]
                  (fn [ui-root mount-node] (dom-client/hydrateRoot mount-node ui-root)))))
     :clj  app))
