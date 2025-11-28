(ns com.fulcrologic.fulcro.react.hooks-spec
  "Tests for CLJ headless hooks emulation."
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom-server :as dom]
    [com.fulcrologic.fulcro.headless :as h]
    [com.fulcrologic.fulcro.headless.hiccup :as hic]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.react.hooks :as hooks]
    [fulcro-spec.core :refer [assertions behavior component specification]]))

(declare =>)

;; =============================================================================
;; Test Helper
;; =============================================================================

(defn last-frame-hiccup
  "Get the hiccup representation of the last rendered frame."
  [app]
  (hic/rendered-tree->hiccup (:rendered (h/last-frame app))))

;; =============================================================================
;; Test Components with Hooks
;; =============================================================================

(defsc HookCounter [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-counter])
   :initial-state {}}
  (let [[count set-count!] (hooks/use-state 0)]
    (dom/div {:id "counter"}
      (dom/span {:id "count"} (str count))
      (dom/button {:id "increment" :onClick #(set-count! inc)} "+")
      (dom/button {:id "decrement" :onClick #(set-count! dec)} "-")
      (dom/button {:id "reset" :onClick #(set-count! 0)} "Reset"))))

(defsc HookCounterWithInitFn [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-counter-init-fn])
   :initial-state {}}
  (let [[count set-count!] (hooks/use-state (fn [] 42))]
    (dom/div {:id "counter-init-fn"}
      (dom/span {:id "count"} (str count))
      (dom/button {:id "increment" :onClick #(set-count! inc)} "+"))))

(def effect-log (atom []))

(defsc HookWithEffect [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-with-effect])
   :initial-state {}}
  (let [[count set-count!] (hooks/use-state 0)]
    (hooks/useEffect
      (fn []
        (swap! effect-log conj {:event :mount :count count})
        (fn cleanup []
          (swap! effect-log conj {:event :cleanup :count count})))
      (to-array []))
    (dom/div {:id "effect-component"}
      (dom/span {:id "count"} (str count))
      (dom/button {:id "increment" :onClick #(set-count! inc)} "+"))))

(defsc HookWithDepsEffect [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-with-deps-effect])
   :initial-state {}}
  (let [[count set-count!] (hooks/use-state 0)
        [label set-label!] (hooks/use-state "initial")]
    (hooks/useEffect
      (fn []
        (swap! effect-log conj {:event :effect-run :count count})
        (fn cleanup []
          (swap! effect-log conj {:event :effect-cleanup :count count})))
      (to-array [count]))
    (dom/div {:id "deps-effect-component"}
      (dom/span {:id "count"} (str count))
      (dom/span {:id "label"} label)
      (dom/button {:id "increment" :onClick #(set-count! inc)} "+")
      (dom/button {:id "change-label" :onClick #(set-label! "changed")} "Change Label"))))

(defsc HookWithRef [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-with-ref])
   :initial-state {}}
  (let [render-count-ref (hooks/use-ref 0)
        [count set-count!] (hooks/use-state 0)]
    ;; Increment render count each render (use CLJC-compatible functions)
    (hooks/set-ref-current! render-count-ref (inc (hooks/ref-current render-count-ref)))
    (dom/div {:id "ref-component"}
      (dom/span {:id "count"} (str count))
      (dom/span {:id "render-count"} (str (hooks/ref-current render-count-ref)))
      (dom/button {:id "increment" :onClick #(set-count! inc)} "+"))))

(defsc HookWithMemo [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-with-memo])
   :initial-state {}}
  (let [[a set-a!] (hooks/use-state 1)
        [b set-b!] (hooks/use-state 2)
        [unrelated set-unrelated!] (hooks/use-state "x")
        expensive-value (hooks/use-memo (fn []
                                          (swap! effect-log conj {:event :memo-compute :a a :b b})
                                          (* a b))
                          [a b])]
    (dom/div {:id "memo-component"}
      (dom/span {:id "a"} (str a))
      (dom/span {:id "b"} (str b))
      (dom/span {:id "result"} (str expensive-value))
      (dom/span {:id "unrelated"} unrelated)
      (dom/button {:id "inc-a" :onClick #(set-a! inc)} "Inc A")
      (dom/button {:id "inc-b" :onClick #(set-b! inc)} "Inc B")
      (dom/button {:id "change-unrelated" :onClick #(set-unrelated! "y")} "Change Unrelated"))))

(defsc HookWithCallback [this _]
  {:query         []
   :ident         (fn [] [:component/id ::hook-with-callback])
   :initial-state {}}
  (let [[count set-count!] (hooks/use-state 0)
        callback-ref (hooks/use-ref nil)
        increment-fn (hooks/use-callback (fn [] (set-count! inc)) [set-count!])]
    ;; Track if callback identity changes (use CLJC-compatible functions)
    (when (and (hooks/ref-current callback-ref) (not (identical? (hooks/ref-current callback-ref) increment-fn)))
      (swap! effect-log conj {:event :callback-changed}))
    (hooks/set-ref-current! callback-ref increment-fn)
    (dom/div {:id "callback-component"}
      (dom/span {:id "count"} (str count))
      (dom/button {:id "increment" :onClick increment-fn} "+"))))

;; Root component that can conditionally show/hide the counter
(defsc ConditionalRoot [_ {:ui/keys [show-counter?]}]
  {:query         [:ui/show-counter?]
   :initial-state {:ui/show-counter? true}}
  (dom/div {:id "conditional-root"}
    (when show-counter?
      (let [ui-counter (comp/factory HookCounter)]
        (ui-counter {})))))

;; Root component that can conditionally show/hide a component with effect (for cleanup testing)
(defsc ConditionalEffectRoot [_ {:ui/keys [show-effect?]}]
  {:query         [:ui/show-effect?]
   :initial-state {:ui/show-effect? true}}
  (dom/div {:id "conditional-effect-root"}
    (when show-effect?
      (let [ui-effect (comp/factory HookWithEffect)]
        (ui-effect {})))))

(defmutation toggle-counter [_]
  (action [{:keys [state]}]
    (swap! state update :ui/show-counter? not)))

(defmutation toggle-effect [_]
  (action [{:keys [state]}]
    (swap! state update :ui/show-effect? not)))

(defsc HookCounterRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-counter (comp/factory HookCounter)]
    (dom/div {:id "counter-root"}
      (ui-counter {}))))

(defsc HookCounterInitFnRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-counter (comp/factory HookCounterWithInitFn)]
    (dom/div {:id "counter-init-fn-root"}
      (ui-counter {}))))

(defsc HookEffectRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-effect (comp/factory HookWithEffect)]
    (dom/div {:id "effect-root"}
      (ui-effect {}))))

(defsc HookDepsEffectRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-effect (comp/factory HookWithDepsEffect)]
    (dom/div {:id "deps-effect-root"}
      (ui-effect {}))))

(defsc HookRefRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-ref (comp/factory HookWithRef)]
    (dom/div {:id "ref-root"}
      (ui-ref {}))))

(defsc HookMemoRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-memo (comp/factory HookWithMemo)]
    (dom/div {:id "memo-root"}
      (ui-memo {}))))

(defsc HookCallbackRoot [_ _]
  {:query         []
   :initial-state {}}
  (let [ui-callback (comp/factory HookWithCallback)]
    (dom/div {:id "callback-root"}
      (ui-callback {}))))

;; =============================================================================
;; Tests
;; =============================================================================

(specification "useState"
  (component "basic state"
    (behavior "initializes state with default value"
      (let [app    (h/build-test-app {:root-class HookCounterRoot})
            hiccup (last-frame-hiccup app)]
        (assertions
          "count starts at 0"
          (hic/element-text (hic/find-by-id hiccup "count")) => "0")))

    (behavior "initializes state with function"
      (let [app    (h/build-test-app {:root-class HookCounterInitFnRoot})
            hiccup (last-frame-hiccup app)]
        (assertions
          "count starts at 42 (from init fn)"
          (hic/element-text (hic/find-by-id hiccup "count")) => "42"))))

  (component "state updates"
    (behavior "updates state when setter is called"
      (let [app    (h/build-test-app {:root-class HookCounterRoot})
            hiccup (last-frame-hiccup app)]
        ;; Click increment - auto-renders!
        (hic/click! (hic/find-by-id hiccup "increment"))
        (assertions
          "count is now 1"
          (hic/element-text (hic/find-by-id (last-frame-hiccup app) "count")) => "1")))

    (behavior "supports functional updates"
      (let [app    (h/build-test-app {:root-class HookCounterRoot})
            hiccup (last-frame-hiccup app)]
        ;; Click increment multiple times - each click auto-renders
        (hic/click! (hic/find-by-id hiccup "increment"))
        (hic/click! (hic/find-by-id (last-frame-hiccup app) "increment"))
        (hic/click! (hic/find-by-id (last-frame-hiccup app) "increment"))
        (assertions
          "count is now 3"
          (hic/element-text (hic/find-by-id (last-frame-hiccup app) "count")) => "3")))

    (behavior "state persists across renders"
      (let [app    (h/build-test-app {:root-class HookCounterRoot})
            hiccup (last-frame-hiccup app)]
        ;; Increment to 5 - each click auto-renders
        (dotimes [_ 5]
          (hic/click! (hic/find-by-id (last-frame-hiccup app) "increment")))
        ;; Force additional renders to verify state persists
        (h/render-frame! app)
        (h/render-frame! app)
        (assertions
          "count remains 5 after multiple renders"
          (hic/element-text (hic/find-by-id (last-frame-hiccup app) "count")) => "5")))

    (behavior "can reset to specific value"
      (let [app (h/build-test-app {:root-class HookCounterRoot})]
        ;; Increment a few times - each click auto-renders
        (dotimes [_ 3]
          (hic/click! (hic/find-by-id (last-frame-hiccup app) "increment")))
        ;; Reset to 0 - auto-renders
        (hic/click! (hic/find-by-id (last-frame-hiccup app) "reset"))
        (assertions
          "count is reset to 0"
          (hic/element-text (hic/find-by-id (last-frame-hiccup app) "count")) => "0")))))

(specification "useRef"
  (component "ref persistence"
    (behavior "ref persists across renders and can be mutated"
      (let [app           (h/build-test-app {:root-class HookRefRoot})
            hiccup1       (last-frame-hiccup app)
            render-count1 (hic/element-text (hic/find-by-id hiccup1 "render-count"))]
        ;; Trigger a re-render by changing state - auto-renders!
        (hic/click! (hic/find-by-id hiccup1 "increment"))
        ;; No explicit render-frame! needed - setter auto-renders
        (let [hiccup2       (last-frame-hiccup app)
              render-count2 (hic/element-text (hic/find-by-id hiccup2 "render-count"))]
          (assertions
            "first render count is 1"
            render-count1 => "1"
            "second render count is 2 (ref was mutated and persisted)"
            render-count2 => "2"))))))

(specification "useEffect"
  (component "mount effects"
    (behavior "effect runs on mount"
      (reset! effect-log [])
      (let [app (h/build-test-app {:root-class HookEffectRoot})]
        (assertions
          "effect ran on mount"
          (some #(= (:event %) :mount) @effect-log) => true
          "mount event shows initial count"
          (:count (first (filter #(= (:event %) :mount) @effect-log))) => 0))))

  (component "cleanup effects"
    (behavior "cleanup runs when component unmounts"
      (reset! effect-log [])
      (let [app (h/build-test-app {:root-class ConditionalEffectRoot})]
        ;; Component is mounted, effect should have run
        (assertions
          "effect ran on mount"
          (some #(= (:event %) :mount) @effect-log) => true)
        ;; Now unmount by toggling
        (comp/transact! app [(toggle-effect {})])
        (h/wait-for-idle! app)
        (assertions
          "cleanup ran when component unmounted"
          (some #(= (:event %) :cleanup) @effect-log) => true))))

  (component "dependency tracking"
    (behavior "effect runs when deps change"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookDepsEffectRoot})
            hiccup (last-frame-hiccup app)]
        ;; Effect should have run on mount
        (let [initial-count (count (filter #(= (:event %) :effect-run) @effect-log))]
          (assertions
            "effect ran once on mount"
            initial-count => 1)
          ;; Change the count (which is a dep) - auto-renders
          (hic/click! (hic/find-by-id hiccup "increment"))
          (let [after-dep-change (count (filter #(= (:event %) :effect-run) @effect-log))]
            (assertions
              "effect ran again after dep changed"
              after-dep-change => 2)))))

    (behavior "effect does not run when non-deps change"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookDepsEffectRoot})
            hiccup (last-frame-hiccup app)]
        ;; Effect should have run on mount
        (let [initial-count (count (filter #(= (:event %) :effect-run) @effect-log))]
          ;; Change the label (which is NOT a dep) - auto-renders
          (hic/click! (hic/find-by-id hiccup "change-label"))
          (let [after-non-dep-change (count (filter #(= (:event %) :effect-run) @effect-log))]
            (assertions
              "effect did not run when non-dep changed"
              after-non-dep-change => initial-count)))))))

(specification "useMemo"
  (component "memoization"
    (behavior "computes value on mount"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookMemoRoot})
            hiccup (last-frame-hiccup app)]
        (assertions
          "computed value is correct"
          (hic/element-text (hic/find-by-id hiccup "result")) => "2"
          "memo computed once"
          (count (filter #(= (:event %) :memo-compute) @effect-log)) => 1)))

    (behavior "recomputes when deps change"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookMemoRoot})
            hiccup (last-frame-hiccup app)]
        ;; Increment a (a dep) - auto-renders
        (hic/click! (hic/find-by-id hiccup "inc-a"))
        (assertions
          "computed value updated"
          (hic/element-text (hic/find-by-id (last-frame-hiccup app) "result")) => "4"
          "memo recomputed"
          (count (filter #(= (:event %) :memo-compute) @effect-log)) => 2)))

    (behavior "does not recompute when non-deps change"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookMemoRoot})
            hiccup (last-frame-hiccup app)]
        (let [initial-compute-count (count (filter #(= (:event %) :memo-compute) @effect-log))]
          ;; Change unrelated state - auto-renders
          (hic/click! (hic/find-by-id hiccup "change-unrelated"))
          (assertions
            "unrelated state changed"
            (hic/element-text (hic/find-by-id (last-frame-hiccup app) "unrelated")) => "y"
            "memo was NOT recomputed"
            (count (filter #(= (:event %) :memo-compute) @effect-log)) => initial-compute-count))))))

(specification "useCallback"
  (component "callback stability"
    (behavior "callback identity is stable when deps don't change"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookCallbackRoot})
            hiccup (last-frame-hiccup app)]
        ;; Click increment (which calls the callback but doesn't change deps) - auto-renders
        (hic/click! (hic/find-by-id hiccup "increment"))
        ;; Click again - auto-renders
        (hic/click! (hic/find-by-id (last-frame-hiccup app) "increment"))
        (assertions
          "count increased correctly"
          (hic/element-text (hic/find-by-id (last-frame-hiccup app) "count")) => "2"
          "callback identity did not change (no callback-changed events)"
          (count (filter #(= (:event %) :callback-changed) @effect-log)) => 0)))))

(specification "Multiple hooks in same component"
  (component "hook ordering"
    (behavior "multiple hooks maintain correct state"
      (reset! effect-log [])
      (let [app    (h/build-test-app {:root-class HookMemoRoot})
            hiccup (last-frame-hiccup app)]
        ;; Initial state
        (assertions
          "a starts at 1"
          (hic/element-text (hic/find-by-id hiccup "a")) => "1"
          "b starts at 2"
          (hic/element-text (hic/find-by-id hiccup "b")) => "2"
          "unrelated starts at x"
          (hic/element-text (hic/find-by-id hiccup "unrelated")) => "x")
        ;; Modify each state independently - each click auto-renders
        (hic/click! (hic/find-by-id hiccup "inc-a"))
        (hic/click! (hic/find-by-id (last-frame-hiccup app) "inc-b"))
        (hic/click! (hic/find-by-id (last-frame-hiccup app) "change-unrelated"))
        (let [final-hiccup (last-frame-hiccup app)]
          (assertions
            "a is now 2"
            (hic/element-text (hic/find-by-id final-hiccup "a")) => "2"
            "b is now 3"
            (hic/element-text (hic/find-by-id final-hiccup "b")) => "3"
            "unrelated is now y"
            (hic/element-text (hic/find-by-id final-hiccup "unrelated")) => "y"
            "result is 2*3=6"
            (hic/element-text (hic/find-by-id final-hiccup "result")) => "6"))))))
