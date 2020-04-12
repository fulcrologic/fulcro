(ns com.fulcrologic.fulcro.cards.react-hooks-cards
  (:require [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
            [nubank.workspaces.core :as ws]
            [com.fulcrologic.fulcro-css.localized-dom :as dom]
            [com.fulcrologic.fulcro.components :as fc]
            [com.fulcrologic.fulcro.react.hooks :as hooks]
            ["react" :as react]))

(defn hook-demo-card [f]
  (ct.fulcro/fulcro-card
    {::ct.fulcro/root
     (fc/configure-hooks-component!
       f
       {:componentName (keyword (gensym "hook-demo"))})
     ::ct.fulcro/wrap-root?
     false}))

(ws/defcard use-state-card
  (hook-demo-card
    (fn []
      (let [[count set-count!] (hooks/use-state 0)]
        (dom/button {:onClick #(set-count! inc)}
          (str "Counter: " count))))))

(ws/defcard use-state-lazy-card
  (hook-demo-card
    (fn []
      (let [[count set-count!] (hooks/use-state (fn [] 0))]
        (dom/button {:onClick #(set-count! inc)}
          (str "Counter: " count))))))

(ws/defcard use-effect-card
  (hook-demo-card
    (fn []
      (hooks/use-effect (fn [] (js/console.log "Mount")
                          (fn []
                            (js/console.log "Unmount"))))
      (dom/div "check console"))))

(ws/defcard use-effect-deps-card
  (hook-demo-card
    (fn []
      (let [[counter set-counter!] (hooks/use-state 0)]
        (hooks/use-effect
          (fn []
            (js/console.log ">>>> Only In")
            js/undefined)
          [])
        (hooks/use-effect
          (fn []
            (js/console.log ">>>> Empty Deps")
            (fn []
              (js/console.log "<<<< Empty Deps")))
          [])
        (hooks/use-effect
          (fn []
            (js/console.log ">>>> No deps")
            (fn []
              (js/console.log "<<<< No Deps"))))
        (hooks/use-effect
          (fn []
            (js/console.log ">>>> Count Dep")
            (fn []
              (js/console.log "<<<< Count Dep")))
          [counter])
        (dom/button {:onClick #(set-counter! (inc counter))}
          (str "Click and check console: " counter))))))

(def themes
  {:light {:background "#fff"
           :color      "#000"}
   :dark  {:background "#000"
           :color      "#fff"}})

(def SampleContext (react/createContext (:light themes)))

(fc/defsc ContextDemoThemedButton
  [this props]
  {:use-hooks? true}
  (let [theme (hooks/use-context SampleContext)]

    (dom/button {:style theme} (fc/children this))))
(def context-demo-themed-button (fc/factory ContextDemoThemedButton))

(fc/defsc ContextDemoToolbar
  [this {::keys []}]
  {:use-hooks? true}
  (dom/div
    (context-demo-themed-button {} "Button Content")))

(def context-demo-toolbar (fc/factory ContextDemoToolbar))

(fc/defsc ContextDemoApp
  [this {::keys []}]
  {:use-hooks? true}
  (react/createElement (.-Provider SampleContext) #js {:value (:dark themes)}
    (context-demo-toolbar {})))

(def context-demo-app (fc/factory ContextDemoApp))

(ws/defcard use-context-card
  (hook-demo-card
    (fn []
      (context-demo-app {}))))

(defn sample-reducer [state {:keys [type] :as action}]
  (case type
    ::increment
    (update state :count inc)
    ::decrement
    (update state :count dec)))

(ws/defcard use-reducer-card
  (hook-demo-card
    (fn []
      (let [[state dispatch] (hooks/use-reducer sample-reducer {:count 0})]
        (dom/div
          (dom/div "Count: " (:count state))
          (dom/button {:onClick #(dispatch {:type ::decrement})} "-")
          (dom/button {:onClick #(dispatch {:type ::increment})} "+"))))))

(ws/defcard use-callback-card
  (hook-demo-card
    (fn []
      (let [[counter set-counter!] (hooks/use-state 0)
            callback (hooks/use-callback #(set-counter! inc) [])]
        (dom/div
          (dom/div "FN Hash: " (hash callback))
          (dom/button {:onClick callback}
            (str "Counter: " counter)))))))

(ws/defcard use-memo-card
  (hook-demo-card
    (fn []
      (let [[counter set-counter!] (hooks/use-state 0)
            callback (hooks/use-memo (fn [] #(set-counter! inc)) [])]
        (dom/div
          (dom/div "FN Hash: " (hash callback))
          (dom/button {:onClick callback}
            (str "Counter: " counter)))))))

(ws/defcard use-ref-card
  (hook-demo-card
    (fn []
      (let [ref (hooks/use-ref nil)
            cb  (hooks/use-callback #(.focus (.-current ref)))]
        (dom/div
          (dom/input {:ref ref :type "text"})
          (dom/button {:onClick cb} "Focus the input"))))))

(ws/defcard use-layout-effect-card
  (hook-demo-card
    (fn []
      (hooks/use-layout-effect
        (fn []
          (js/console.log ">>>> Layout Effect")
          (fn []
            (js/console.log "<<<< Layout Effect")))
        [])
      (dom/div "Check console"))))

(ws/defcard use-debug-value-card
  (hook-demo-card
    (fn []
      (let [[count set-count!] (hooks/use-state (fn [] 0))]
        (hooks/use-debug-value (if (< count 5) "Small" "Large"))
        (hooks/use-debug-value (if (< count 5) "Small" "Large") cljs.core/count)
        (dom/button {:onClick #(set-count! inc)}
          (str "Counter: " count))))))

(ws/defworkspace hooks
  "[\"^ \",\"c10\",[[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-state-card\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"minH\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-state-lazy-card\",\"w\",2,\"h\",4,\"x\",2,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-context-card\",\"w\",2,\"h\",4,\"x\",4,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-reducer-card\",\"w\",2,\"h\",4,\"x\",6,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-callback-card\",\"w\",2,\"h\",4,\"x\",8,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-memo-card\",\"w\",2,\"h\",4,\"x\",0,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-ref-card\",\"w\",2,\"h\",4,\"x\",2,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-layout-effect-card\",\"w\",2,\"h\",4,\"x\",4,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"~$com.fulcrologic.fulcro.cards.react-hooks-cards/use-debug-value-card\",\"w\",2,\"h\",4,\"x\",6,\"y\",4,\"^1\",2]],\"c8\",[[\"^ \",\"w\",2,\"x\",0,\"i\",\"^0\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",2,\"i\",\"^2\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",4,\"i\",\"^3\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",6,\"i\",\"^4\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",0,\"i\",\"^5\",\"y\",4,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",2,\"i\",\"^6\",\"y\",4,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",4,\"i\",\"^7\",\"y\",4,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",6,\"i\",\"^8\",\"y\",4,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",0,\"i\",\"^9\",\"y\",8,\"^1\",2,\"h\",4]],\"c16\",[[\"^ \",\"w\",2,\"x\",0,\"i\",\"^0\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",2,\"i\",\"^2\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",4,\"i\",\"^3\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",6,\"i\",\"^4\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",8,\"i\",\"^5\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",10,\"i\",\"^6\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",12,\"i\",\"^7\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",14,\"i\",\"^8\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",0,\"i\",\"^9\",\"y\",4,\"^1\",2,\"h\",4]],\"c14\",[[\"^ \",\"i\",\"^0\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^2\",\"w\",2,\"h\",4,\"x\",2,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^3\",\"w\",2,\"h\",4,\"x\",4,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^4\",\"w\",2,\"h\",4,\"x\",6,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^5\",\"w\",2,\"h\",4,\"x\",8,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^6\",\"w\",2,\"h\",4,\"x\",10,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^7\",\"w\",2,\"h\",4,\"x\",12,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^8\",\"w\",2,\"h\",4,\"x\",0,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^9\",\"w\",2,\"h\",4,\"x\",2,\"y\",4,\"^1\",2]],\"c2\",[[\"^ \",\"i\",\"^0\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^2\",\"w\",2,\"h\",4,\"x\",0,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^3\",\"w\",2,\"h\",4,\"x\",0,\"y\",8,\"^1\",2],[\"^ \",\"i\",\"^4\",\"w\",2,\"h\",4,\"x\",0,\"y\",12,\"^1\",2],[\"^ \",\"i\",\"^5\",\"w\",2,\"h\",4,\"x\",0,\"y\",16,\"^1\",2],[\"^ \",\"i\",\"^6\",\"w\",2,\"h\",4,\"x\",0,\"y\",20,\"^1\",2],[\"^ \",\"i\",\"^7\",\"w\",2,\"h\",4,\"x\",0,\"y\",24,\"^1\",2],[\"^ \",\"i\",\"^8\",\"w\",2,\"h\",4,\"x\",0,\"y\",28,\"^1\",2],[\"^ \",\"i\",\"^9\",\"w\",2,\"h\",4,\"x\",0,\"y\",32,\"^1\",2]],\"c12\",[[\"^ \",\"w\",2,\"x\",0,\"i\",\"^0\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",2,\"i\",\"^2\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",4,\"i\",\"^3\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",6,\"i\",\"^4\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",8,\"i\",\"^5\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",10,\"i\",\"^6\",\"y\",0,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",0,\"i\",\"^7\",\"y\",4,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",2,\"i\",\"^8\",\"y\",4,\"^1\",2,\"h\",4],[\"^ \",\"w\",2,\"x\",4,\"i\",\"^9\",\"y\",4,\"^1\",2,\"h\",4]],\"c4\",[[\"^ \",\"i\",\"^0\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^2\",\"w\",2,\"h\",4,\"x\",2,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^3\",\"w\",2,\"h\",4,\"x\",0,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^4\",\"w\",2,\"h\",4,\"x\",2,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^5\",\"w\",2,\"h\",4,\"x\",0,\"y\",8,\"^1\",2],[\"^ \",\"i\",\"^6\",\"w\",2,\"h\",4,\"x\",2,\"y\",8,\"^1\",2],[\"^ \",\"i\",\"^7\",\"w\",2,\"h\",4,\"x\",0,\"y\",12,\"^1\",2],[\"^ \",\"i\",\"^8\",\"w\",2,\"h\",4,\"x\",2,\"y\",12,\"^1\",2],[\"^ \",\"i\",\"^9\",\"w\",2,\"h\",4,\"x\",0,\"y\",16,\"^1\",2]],\"c18\",[[\"^ \",\"i\",\"^0\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^2\",\"w\",2,\"h\",4,\"x\",2,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^3\",\"w\",2,\"h\",4,\"x\",4,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^4\",\"w\",2,\"h\",4,\"x\",6,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^5\",\"w\",2,\"h\",4,\"x\",8,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^6\",\"w\",2,\"h\",4,\"x\",10,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^7\",\"w\",2,\"h\",4,\"x\",12,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^8\",\"w\",2,\"h\",4,\"x\",14,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^9\",\"w\",2,\"h\",4,\"x\",16,\"y\",0,\"^1\",2]],\"c20\",[[\"^ \",\"i\",\"^0\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^2\",\"w\",2,\"h\",4,\"x\",2,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^3\",\"w\",2,\"h\",4,\"x\",4,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^4\",\"w\",2,\"h\",4,\"x\",6,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^5\",\"w\",2,\"h\",4,\"x\",8,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^6\",\"w\",2,\"h\",4,\"x\",10,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^7\",\"w\",2,\"h\",4,\"x\",12,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^8\",\"w\",2,\"h\",4,\"x\",14,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^9\",\"w\",2,\"h\",4,\"x\",16,\"y\",0,\"^1\",2]],\"c6\",[[\"^ \",\"i\",\"^0\",\"w\",2,\"h\",4,\"x\",0,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^2\",\"w\",2,\"h\",4,\"x\",2,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^3\",\"w\",2,\"h\",4,\"x\",4,\"y\",0,\"^1\",2],[\"^ \",\"i\",\"^4\",\"w\",2,\"h\",4,\"x\",0,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^5\",\"w\",2,\"h\",4,\"x\",2,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^6\",\"w\",2,\"h\",4,\"x\",4,\"y\",4,\"^1\",2],[\"^ \",\"i\",\"^7\",\"w\",2,\"h\",4,\"x\",0,\"y\",8,\"^1\",2],[\"^ \",\"i\",\"^8\",\"w\",2,\"h\",4,\"x\",2,\"y\",8,\"^1\",2],[\"^ \",\"i\",\"^9\",\"w\",2,\"h\",4,\"x\",4,\"y\",8,\"^1\",2]]]")
