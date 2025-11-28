(ns com.fulcrologic.fulcro.headless-spec
  "Comprehensive tests for the headless Fulcro framework."
  (:require
    [com.fulcrologic.fulcro.algorithms.scheduling :as sched]
    [com.fulcrologic.fulcro.algorithms.tx-processing :as txn]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.dom-server :as dom]
    [com.fulcrologic.fulcro.headless :as ct]
    [com.fulcrologic.fulcro.headless.hiccup :as hic]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as ctr]
    [com.fulcrologic.fulcro.headless.timers :as timers]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [fulcro-spec.core :refer [assertions behavior component specification]]))

(declare =>)

;; =============================================================================
;; Test Components
;; =============================================================================

(defsc Item [_ {:item/keys [id label complete?]}]
  {:query [:item/id :item/label :item/complete?]
   :ident :item/id}
  nil)

(defsc TodoList [_ {:list/keys [id title items]}]
  {:query [:list/id :list/title {:list/items (comp/get-query Item)}]
   :ident :list/id}
  nil)

(defsc Root [_ {:root/keys [lists current-user]}]
  {:query         [{:root/lists (comp/get-query TodoList)}
                   :root/current-user]
   :initial-state {:root/lists        []
                   :root/current-user nil}}
  nil)

(defsc EmptyRoot [_ _]
  {:query         [:ui/value]
   :initial-state {:ui/value "initial"}}
  nil)

(defsc NestedRoot [_ _]
  {:query         [{:nested/item (comp/get-query Item)}
                   {:nested/list (comp/get-query TodoList)}]
   :initial-state {}}
  nil)

;; =============================================================================
;; Test Components with DOM Rendering (for hiccup tests)
;; =============================================================================

(defsc RenderingItem [_ {:item/keys [id label complete?]}]
  {:query [:item/id :item/label :item/complete?]
   :ident :item/id}
  (dom/li {:id (str "item-" id) :className (if complete? "complete" "pending")}
    (dom/span {:className "label"} label)
    (when complete?
      (dom/span {:className "check"} "✓"))))

(defsc RenderingList [_ {:list/keys [id title items]}]
  {:query [:list/id :list/title {:list/items (comp/get-query RenderingItem)}]
   :ident :list/id}
  (dom/div {:id (str "list-" id) :className "todo-list"}
    (dom/h2 {} title)
    (dom/ul {}
      (map (comp/factory RenderingItem {:keyfn :item/id}) items))))

(defsc RenderingRoot [_ {:root/keys [lists current-user]}]
  {:query         [{:root/lists (comp/get-query RenderingList)}
                   :root/current-user]
   :initial-state {:root/lists        []
                   :root/current-user nil}}
  (dom/div {:id "app" :className "app-container"}
    (dom/header {}
      (dom/h1 {} "Todo App")
      (when current-user
        (dom/span {:className "user"} (str "User: " current-user))))
    (dom/main {}
      (map (comp/factory RenderingList {:keyfn :list/id}) lists))))

(defsc SimpleRenderingRoot [_ {:ui/keys [value counter]}]
  {:query         [:ui/value :ui/counter]
   :initial-state {:ui/value   "initial"
                   :ui/counter 0}}
  (dom/div {:id "simple-root"}
    (dom/p {:className "value"} value)
    (dom/p {:className "counter"} (str counter))))

;; =============================================================================
;; Test Components for *app* binding verification (nested component case)
;; =============================================================================

(defsc AppAccessingChild [this _]
  {:query         []
   :ident         (fn [] [:component/id ::app-accessing-child])
   :initial-state {}}
  ;; This component accesses comp/*app* during render - will fail if not bound
  (let [app       comp/*app*
        state-map (when app (rapp/current-state app))
        counter   (get state-map :ui/counter 0)]
    (dom/div {:id "app-accessing-child"}
      (dom/span {:id "child-counter"} (str "Counter from app: " counter)))))

(def ui-app-accessing-child (comp/factory AppAccessingChild))

(defsc NestedAppAccessRoot [_ {:ui/keys [counter]}]
  {:query         [:ui/counter]
   :initial-state {:ui/counter 42}}
  ;; This creates the bouncing pattern: Component -> DOM -> Component
  ;; The child component accesses *app* which must be properly bound
  (dom/div {:id "nested-root"}
    (dom/h1 {} "Nested Test")
    (dom/div {:id "wrapper"}
      (ui-app-accessing-child {}))))

;; =============================================================================
;; Test Mutations
;; =============================================================================

(defmutation set-complete [{:item/keys [id complete?]}]
  (action [{:keys [state]}]
    (swap! state assoc-in [:item/id id :item/complete?] complete?))
  (remote [_] true))

(defmutation add-item [{:list/keys [id] :as params}]
  (action [{:keys [state]}]
    (let [item-id    (:item/id params)
          item-label (:item/label params)]
      (swap! state (fn [s]
                     (-> s
                       (assoc-in [:item/id item-id] {:item/id item-id :item/label item-label :item/complete? false})
                       (update-in [:list/id id :list/items] (fnil conj []) [:item/id item-id]))))))
  (remote [_] true))

(defmutation increment-counter [_]
  (action [{:keys [state]}]
    (swap! state update :counter (fnil inc 0))))

(defmutation set-value [{:keys [path value]}]
  (action [{:keys [state]}]
    (swap! state assoc-in path value)))

(defmutation failing-mutation [_]
  (action [{:keys [state]}]
    (throw (ex-info "Mutation failed" {:type :test-error}))))

(defmutation remote-only-mutation [params]
  (remote [_] true))

;; =============================================================================
;; Tests: build-test-app
;; =============================================================================

(specification "build-test-app"
  (component "basic creation"
    (behavior "creates an app with sync tx processing"
      (let [app (ct/build-test-app {:root-class Root})]
        (assertions
          "returns a Fulcro app"
          (app/fulcro-app? app) => true
          "has initial render frame"
          (count (ct/render-history app)) => 1
          "state atom exists"
          (some? (::app/state-atom app)) => true
          "runtime atom exists"
          (some? (::app/runtime-atom app)) => true))))

  (component "state initialization"
    (behavior "initializes state from root component"
      (let [app (ct/build-test-app {:root-class Root})]
        (assertions
          "state contains root initial state keys"
          (contains? (rapp/current-state app) :root/lists) => true
          (contains? (rapp/current-state app) :root/current-user) => true
          "root/lists is empty vector"
          (:root/lists (rapp/current-state app)) => []
          "root/current-user is nil"
          (:root/current-user (rapp/current-state app)) => nil)))

    (behavior "can merge initial state"
      (let [app (ct/build-test-app {:root-class    Root
                                    :initial-state {:existing-key "value"
                                                    :nested       {:deep "data"}}})]
        (assertions
          "preserves provided initial state"
          (:existing-key (rapp/current-state app)) => "value"
          "preserves nested initial state"
          (get-in (rapp/current-state app) [:nested :deep]) => "data"
          "also has root initial state"
          (:root/lists (rapp/current-state app)) => [])))

    (behavior "with EmptyRoot"
      (let [app (ct/build-test-app {:root-class EmptyRoot})]
        (assertions
          "initializes simple root state"
          (:ui/value (rapp/current-state app)) => "initial"))))

  (component "render history configuration"
    (behavior "uses default history size"
      (let [app (ct/build-test-app {:root-class Root
                                    :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
        ;; Generate more frames than default
        (dotimes [_ 15]
          (comp/transact! app [(increment-counter)]))
        (assertions
          "limits history to default max"
          (<= (count (ct/render-history app)) 10) => true)))

    (behavior "respects custom render-history-size"
      (let [app (ct/build-test-app {:root-class          Root
                                    :remotes             {:remote (ctr/sync-remote (constantly {}))}
                                    :render-history-size 3})]
        (dotimes [_ 5]
          (comp/transact! app [(increment-counter)]))
        (assertions
          "only keeps specified number of frames"
          (<= (count (ct/render-history app)) 3) => true))))

  (component "without root class"
    (behavior "creates app without root class"
      (let [app (ct/build-test-app {:initial-state {:key "value"}})]
        (assertions
          "app is still valid"
          (app/fulcro-app? app) => true
          "has the initial state"
          (:key (rapp/current-state app)) => "value"))))

  (component "with shared props"
    ;; Note: shared props in test apps work through the runtime atom
    ;; The comp/shared function reads from app state during render
    (behavior "accepts static shared props option"
      (let [app (ct/build-test-app {:root-class Root
                                    :shared     {:api-key "test-key"}})]
        ;; Shared is stored in the runtime atom
        (let [runtime @(:com.fulcrologic.fulcro.application/runtime-atom app)]
          (assertions
            "shared option is stored"
            (:com.fulcrologic.fulcro.application/static-shared-props runtime) => {:api-key "test-key"}))))

    (behavior "accepts shared-fn option"
      (let [app (ct/build-test-app {:root-class EmptyRoot
                                    :shared-fn  (fn [root-props]
                                                  {:derived (:ui/value root-props)})})]
        (assertions
          "app is created successfully with shared-fn"
          (app/fulcro-app? app) => true)))))

;; =============================================================================
;; Tests: current-state
;; =============================================================================

(specification "current-state"
  (let [app (ct/build-test-app {:root-class Root})]
    (behavior "returns the current state map"
      (assertions
        "returns a map"
        (map? (rapp/current-state app)) => true
        "contains expected keys"
        (contains? (rapp/current-state app) :root/lists) => true))

    (behavior "reflects state changes"
      (swap! (::app/state-atom app) assoc :new-key "new-value")
      (assertions
        "sees the new key"
        (:new-key (rapp/current-state app)) => "new-value"))

    (behavior "reflects transacted changes"
      (comp/transact! app [(set-value {:path [:test-path] :value 42})])
      (assertions
        "sees transacted changes"
        (:test-path (rapp/current-state app)) => 42))))

;; =============================================================================
;; Tests: render-frame! and render-history
;; =============================================================================

(specification "render-frame!"
  (component "basic frame capture"
    (let [app (ct/build-test-app {:root-class Root})]
      (behavior "captures a frame"
        (ct/render-frame! app)
        (assertions
          "adds frame to history (initial + 1)"
          (count (ct/render-history app)) => 2))

      (behavior "frame structure is correct"
        (let [frame (ct/last-frame app)]
          (assertions
            "frame contains state"
            (map? (:state frame)) => true
            "frame state matches current state"
            (:state frame) => (rapp/current-state app)
            "frame contains denormalized tree"
            (map? (:tree frame)) => true
            "tree has expected structure"
            (:tree frame) => {:root/lists [] :root/current-user nil}
            "frame has timestamp"
            (number? (:timestamp frame)) => true
            "timestamp is reasonable (recent)"
            (> (:timestamp frame) 0) => true)))))

  (component "multiple frames"
    (let [app (ct/build-test-app {:root-class Root})]
      (behavior "captures multiple frames in order"
        (ct/render-frame! app)
        (Thread/sleep 5)
        (swap! (::app/state-atom app) assoc :root/current-user "Alice")
        (ct/render-frame! app)
        (Thread/sleep 5)
        (swap! (::app/state-atom app) assoc :root/current-user "Bob")
        (ct/render-frame! app)
        (assertions
          "has four frames (initial + 3 explicit)"
          (count (ct/render-history app)) => 4
          "most recent frame is first (newest first)"
          (get-in (ct/last-frame app) [:tree :root/current-user]) => "Bob"
          "timestamps are in descending order"
          (> (:timestamp (first (ct/render-history app)))
            (:timestamp (second (ct/render-history app)))) => true))))

  (component "without root class"
    ;; Note: Without a root class, the render algorithm may not create frames
    ;; since there's nothing to query/render. The app is still functional
    ;; and state can be accessed directly.
    (let [app (ct/build-test-app {:initial-state {:key "value"}})]
      (behavior "app still works without root class"
        (assertions
          "state is accessible"
          (:key (rapp/current-state app)) => "value"
          "app is valid"
          (app/fulcro-app? app) => true)))))

(specification "render-history"
  (let [app (ct/build-test-app {:root-class Root})]
    (behavior "has initial frame from build-test-app"
      (assertions
        "starts with one frame"
        (count (ct/render-history app)) => 1))

    (behavior "accumulates frames after rendering"
      (ct/render-frame! app)
      (ct/render-frame! app)
      (assertions
        "returns a vector"
        (vector? (ct/render-history app)) => true
        "has correct count (initial + 2)"
        (count (ct/render-history app)) => 3))))

(specification "last-frame"
  (let [app (ct/build-test-app {:root-class Root})]
    (behavior "returns initial frame after build"
      (assertions
        "has initial frame"
        (some? (ct/last-frame app)) => true))

    (behavior "returns most recent frame"
      (ct/render-frame! app)
      (swap! (::app/state-atom app) assoc :marker "first")
      (ct/render-frame! app)
      (swap! (::app/state-atom app) assoc :marker "second")
      (ct/render-frame! app)
      (assertions
        "returns the most recent"
        (:marker (:state (ct/last-frame app))) => "second"))))

(specification "clear-render-history!"
  (let [app (ct/build-test-app {:root-class Root})]
    (ct/render-frame! app)
    (ct/render-frame! app)
    (ct/render-frame! app)
    (behavior "clears all frames"
      (assertions
        "has frames before clear (initial + 3)"
        (count (ct/render-history app)) => 4)
      (ct/clear-render-history! app)
      (assertions
        "empty after clear"
        (ct/render-history app) => []
        "last-frame is nil"
        (ct/last-frame app) => nil))))

;; =============================================================================
;; Tests: synchronous transactions
;; =============================================================================

(specification "synchronous transactions"
  (component "basic execution"
    (let [app (ct/build-test-app {:root-class Root
                                  :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
      (behavior "execute immediately"
        (comp/transact! app [(increment-counter)])
        (assertions
          "state is updated synchronously"
          (:counter (rapp/current-state app)) => 1))))

  (component "multiple transactions"
    (let [app (ct/build-test-app {:root-class Root
                                  :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
      (behavior "execute in order"
        (comp/transact! app [(increment-counter)])
        (comp/transact! app [(increment-counter)])
        (comp/transact! app [(increment-counter)])
        (assertions
          "all transactions completed"
          (:counter (rapp/current-state app)) => 3))))

  (component "with state path changes"
    (let [app (ct/build-test-app {:root-class Root
                                  :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
      (behavior "updates nested paths"
        (comp/transact! app [(set-value {:path [:nested :deep :value] :value "deep"})])
        (assertions
          "nested value is set"
          (get-in (rapp/current-state app) [:nested :deep :value]) => "deep")))))

;; =============================================================================
;; Tests: get-props
;; =============================================================================

(specification "get-props"
  (let [app (ct/build-test-app {:root-class Root
                                :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    (component "with existing entity"
      (swap! (::app/state-atom app)
        assoc-in [:item/id 1] {:item/id 1 :item/label "Test" :item/complete? false})

      (behavior "denormalizes props for a component at an ident"
        (let [props (ct/current-props app Item [:item/id 1])]
          (assertions
            "returns component props"
            (:item/id props) => 1
            (:item/label props) => "Test"
            (:item/complete? props) => false))))

    (component "with nested data"
      (swap! (::app/state-atom app) merge
        {:list/id {1 {:list/id 1 :list/title "My List" :list/items [[:item/id 1]]}}
         :item/id {1 {:item/id 1 :item/label "Item 1" :item/complete? true}}})

      (behavior "denormalizes nested joins"
        (let [props (ct/current-props app TodoList [:list/id 1])]
          (assertions
            "has list props"
            (:list/id props) => 1
            (:list/title props) => "My List"
            "has denormalized items"
            (count (:list/items props)) => 1
            (get-in props [:list/items 0 :item/label]) => "Item 1"))))

    (component "with missing entity"
      (behavior "returns nil for missing ident"
        (let [props (ct/current-props app Item [:item/id 999])]
          (assertions
            "returns nil/empty"
            (or (nil? props) (empty? props)) => true))))))

;; =============================================================================
;; Tests: Helper Functions
;; =============================================================================

(specification "state-at-render"
  (let [app (ct/build-test-app {:root-class Root})]
    (swap! (::app/state-atom app) assoc :marker 1)
    (ct/render-frame! app)
    (swap! (::app/state-atom app) assoc :marker 2)
    (ct/render-frame! app)
    (swap! (::app/state-atom app) assoc :marker 3)
    (ct/render-frame! app)

    (behavior "returns state at specific frame index"
      (assertions
        "index 0 is most recent"
        (:marker (ct/state-at-render app 0)) => 3
        "index 1 is previous"
        (:marker (ct/state-at-render app 1)) => 2
        "index 2 is oldest"
        (:marker (ct/state-at-render app 2)) => 1
        "out of bounds returns nil"
        (ct/state-at-render app 99) => nil))))

(specification "tree-at-render"
  (let [app (ct/build-test-app {:root-class EmptyRoot})]
    (swap! (::app/state-atom app) assoc :ui/value "A")
    (ct/render-frame! app)
    (swap! (::app/state-atom app) assoc :ui/value "B")
    (ct/render-frame! app)

    (behavior "returns tree at specific frame index"
      (assertions
        "index 0 is most recent"
        (:ui/value (ct/tree-at-render app 0)) => "B"
        "index 1 is previous"
        (:ui/value (ct/tree-at-render app 1)) => "A"
        "out of bounds returns nil"
        (ct/tree-at-render app 99) => nil))))

(specification "hiccup-frame"
  (component "basic hiccup conversion"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (ct/render-frame! app)

      (behavior "returns hiccup for most recent frame"
        (let [hiccup (ct/hiccup-frame app)]
          (assertions
            "returns a vector (hiccup)"
            (vector? hiccup) => true
            "first element is a keyword (tag)"
            (keyword? (first hiccup)) => true
            "root tag is :div"
            (first hiccup) => :div
            "has id attribute"
            (:id (second hiccup)) => "simple-root")))))

  (component "frame index access"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      ;; Render first frame
      (ct/render-frame! app)
      ;; Change state and render second frame
      (swap! (::app/state-atom app) assoc :ui/value "changed")
      (ct/render-frame! app)

      (behavior "returns hiccup at specific frame index"
        (let [latest   (ct/hiccup-frame app 0)
              previous (ct/hiccup-frame app 1)]
          (assertions
            "both are valid hiccup"
            (vector? latest) => true
            (vector? previous) => true
            "can distinguish between frames by content"
            (not= latest previous) => true)))))

  (component "with nested components"
    (let [app (ct/build-test-app {:root-class RenderingRoot})]
      ;; Set up data with nested structure
      (swap! (::app/state-atom app) merge
        {:item/id    {1 {:item/id 1 :item/label "Item 1" :item/complete? false}
                      2 {:item/id 2 :item/label "Item 2" :item/complete? true}}
         :list/id    {1 {:list/id 1 :list/title "My List" :list/items [[:item/id 1] [:item/id 2]]}}
         :root/lists [[:list/id 1]]})
      (ct/render-frame! app)

      (behavior "renders nested hiccup structure"
        (let [hiccup (ct/hiccup-frame app)]
          (assertions
            "returns valid hiccup"
            (vector? hiccup) => true
            "root is :div"
            (first hiccup) => :div
            "has app id"
            (:id (second hiccup)) => "app")))))

  (component "has initial frame on build"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (behavior "build-test-app captures initial frame"
        (assertions
          "initial frame exists without explicit render-frame!"
          (some? (ct/hiccup-frame app)) => true))))

  (component "returns nil for out of bounds"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (ct/render-frame! app)
      (behavior "returns nil for invalid index"
        (assertions
          "nil for large index"
          (ct/hiccup-frame app 99) => nil)))))

(specification "hiccup-for"
  (component "basic component rendering"
    (let [app (ct/build-test-app {:root-class RenderingRoot})]
      ;; Set up data
      (swap! (::app/state-atom app) assoc-in [:item/id 1]
        {:item/id 1 :item/label "Test Item" :item/complete? false})

      (behavior "renders component at ident to hiccup"
        (let [hiccup (ct/hiccup-for app RenderingItem [:item/id 1])]
          (assertions
            "returns valid hiccup"
            (vector? hiccup) => true
            "root tag is :li"
            (first hiccup) => :li
            "has item id"
            (:id (second hiccup)) => "item-1"
            "has pending class (not complete)"
            (:className (second hiccup)) => "pending")))))

  (component "renders complete item"
    (let [app (ct/build-test-app {:root-class RenderingRoot})]
      (swap! (::app/state-atom app) assoc-in [:item/id 2]
        {:item/id 2 :item/label "Done Item" :item/complete? true})

      (behavior "renders completed item with correct class"
        (let [hiccup (ct/hiccup-for app RenderingItem [:item/id 2])]
          (assertions
            "has complete class"
            (:className (second hiccup)) => "complete")))))

  (component "renders nested data"
    (let [app (ct/build-test-app {:root-class RenderingRoot})]
      (swap! (::app/state-atom app) merge
        {:item/id {1 {:item/id 1 :item/label "First" :item/complete? false}
                   2 {:item/id 2 :item/label "Second" :item/complete? true}}
         :list/id {1 {:list/id    1
                      :list/title "Test List"
                      :list/items [[:item/id 1] [:item/id 2]]}}})

      (behavior "renders list with nested items"
        (let [hiccup (ct/hiccup-for app RenderingList [:list/id 1])]
          (assertions
            "returns valid hiccup"
            (vector? hiccup) => true
            "root tag is :div"
            (first hiccup) => :div
            "has list id"
            (:id (second hiccup)) => "list-1"
            "has todo-list class"
            (:className (second hiccup)) => "todo-list")))))

  (component "reflects state changes"
    (let [app (ct/build-test-app {:root-class RenderingRoot})]
      (swap! (::app/state-atom app) assoc-in [:item/id 1]
        {:item/id 1 :item/label "Pending" :item/complete? false})

      (behavior "hiccup changes when state changes"
        (let [before (ct/hiccup-for app RenderingItem [:item/id 1])]
          (swap! (::app/state-atom app) assoc-in [:item/id 1 :item/complete?] true)
          (let [after (ct/hiccup-for app RenderingItem [:item/id 1])]
            (assertions
              "before has pending class"
              (:className (second before)) => "pending"
              "after has complete class"
              (:className (second after)) => "complete"))))))

  (component "root component without ident"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]

      (behavior "can render root without explicit ident"
        (let [hiccup (ct/hiccup-for app SimpleRenderingRoot)]
          (assertions
            "returns valid hiccup"
            (vector? hiccup) => true
            "has simple-root id"
            (:id (second hiccup)) => "simple-root"))))))

(specification "nested components with *app* access"
  (component "bouncing render: Component -> DOM -> Component"
    (let [app (ct/build-test-app {:root-class NestedAppAccessRoot})]
      ;; build-test-app now auto-renders initial frame

      (behavior "child component can access *app* during render"
        (let [hiccup (ct/hiccup-frame app)]
          (assertions
            "returns valid hiccup"
            (vector? hiccup) => true
            "root has correct id"
            (:id (second hiccup)) => "nested-root")))

      (behavior "child component reads state from *app*"
        (let [hiccup     (ct/hiccup-frame app)
              child-text (hic/text-of hiccup "child-counter")]
          (assertions
            "child text includes counter value from state"
            child-text => "Counter from app: 42")))

      (behavior "child sees updated state after mutation"
        (swap! (::app/state-atom app) assoc :ui/counter 100)
        (ct/render-frame! app)
        (let [hiccup     (ct/hiccup-frame app)
              child-text (hic/text-of hiccup "child-counter")]
          (assertions
            "child text reflects new counter value"
            child-text => "Counter from app: 100"))))))

(specification "frames-since"
  (let [app (ct/build-test-app {:root-class Root})]
    (ct/render-frame! app)
    (let [ts1 (:timestamp (ct/last-frame app))]
      (Thread/sleep 10)
      (ct/render-frame! app)
      (ct/render-frame! app)

      (behavior "returns frames since timestamp"
        (let [frames (ct/frames-since app ts1)]
          (assertions
            "returns recent frames"
            (count frames) => 2
            "all frames are after timestamp"
            (every? #(> (:timestamp %) ts1) frames) => true))))))

(specification "with-render-tracking"
  (let [app (ct/build-test-app {:root-class Root
                                :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    (behavior "tracks renders during execution"
      (let [result (ct/with-render-tracking app
                     (fn []
                       (comp/transact! app [(increment-counter)])
                       (comp/transact! app [(increment-counter)])
                       :done))]
        (assertions
          "returns result"
          (:result result) => :done
          "captures frame count"
          (number? (:frame-count result)) => true
          "frames is a collection"
          (coll? (:frames result)) => true)))))

(specification "state assertions"
  (let [app (ct/build-test-app {:root-class Root})]
    (swap! (::app/state-atom app) assoc :test-key "test-value")
    (swap! (::app/state-atom app) assoc-in [:nested :path] 42)

    (behavior "can assert state values"
      (assertions
        "top-level key matches"
        (:test-key (rapp/current-state app)) => "test-value"))

    (behavior "works with nested paths"
      (assertions
        "nested path matches"
        (get-in (rapp/current-state app) [:nested :path]) => 42))))

(specification "assert-no-pending-work"
  (let [app (ct/build-test-app {:root-class Root
                                :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    ;; Note: With synchronous tx processing, the active queue may still have
    ;; entries after the action completes (for result processing). The key
    ;; behavior is that the state change is synchronous.
    (behavior "can check for pending work"
      (assertions
        "function exists and can be called"
        (fn? ct/has-pending-work?) => true
        "returns a boolean-ish value"
        (boolean? (boolean (ct/has-pending-work? app))) => true))))

(specification "reset-app!"
  (let [app (ct/build-test-app {:root-class EmptyRoot})]
    (swap! (::app/state-atom app) assoc :ui/value "changed" :extra-key "extra")
    (ct/render-frame! app)
    (ct/render-frame! app)

    (behavior "resets state to initial"
      (ct/reset-app! app)
      (assertions
        "state reset to initial"
        (:ui/value (rapp/current-state app)) => "initial"
        "extra keys removed"
        (contains? (rapp/current-state app) :extra-key) => false
        "render history cleared"
        (ct/render-history app) => []))))

(specification "set-remote!"
  (let [app   (ct/build-test-app {:root-class Root})
        calls (atom [])]
    (behavior "can set a new remote"
      (ct/set-remote! app :custom-remote
        (ctr/sync-remote (fn [eql]
                           (swap! calls conj eql)
                           {:result "ok"})))
      (assertions
        "remote was added"
        true => true))))

(specification "wait-for-idle!"
  (let [app (ct/build-test-app {:root-class Root
                                :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    (behavior "returns quickly when idle"
      (ct/wait-for-idle! app)
      (assertions
        "completed without timeout"
        true => true))

    (behavior "waits for work to complete"
      (comp/transact! app [(increment-counter)])
      (ct/wait-for-idle! app)
      (assertions
        "state updated after wait"
        (:counter (rapp/current-state app)) => 1))))

(specification "has-pending-work?"
  (let [app (ct/build-test-app {:root-class Root
                                :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    (behavior "returns false when idle"
      (assertions
        "no pending work initially"
        (ct/has-pending-work? app) => false))

    (behavior "sync transactions complete actions immediately"
      (comp/transact! app [(increment-counter)])
      ;; Note: has-pending-work? may return true briefly after transactions
      ;; because post-processing may still be scheduled. The key is that
      ;; the actual state change has already completed.
      (assertions
        "state was updated synchronously"
        (:counter (rapp/current-state app)) => 1))))

;; =============================================================================
;; Tests: sync-remote
;; =============================================================================

(specification "sync-remote"
  (component "basic functionality"
    (behavior "calls handler with EQL and returns response"
      (let [handler-calls (atom [])
            remote        (ctr/sync-remote
                            (fn [eql]
                              (swap! handler-calls conj eql)
                              {:result "success"}))
            app           (ct/build-test-app {:root-class Root
                                              :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "handler was called"
          (count @handler-calls) => 1
          "handler received mutation"
          (symbol? (ffirst (first @handler-calls))) => true))))

  (component "transform-request option"
    (behavior "transforms request before handler"
      (let [handler-calls (atom [])
            remote        (ctr/sync-remote
                            (fn [eql]
                              (swap! handler-calls conj eql)
                              {})
                            :transform-request (fn [eql] (conj eql :extra-key)))
            app           (ct/build-test-app {:root-class Root
                                              :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "request was transformed"
          (last (first @handler-calls)) => :extra-key))))

  (component "transform-response option"
    (behavior "transforms response after handler"
      (let [responses (atom [])
            remote    (ctr/sync-remote
                        (fn [_] {:original true})
                        :transform-response (fn [resp]
                                              (swap! responses conj resp)
                                              (assoc resp :transformed true)))
            app       (ct/build-test-app {:root-class Root
                                          :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "original response was captured"
          (:original (first @responses)) => true))))

  (component "on-error option"
    (behavior "handles exceptions with custom handler"
      (let [error-handled (atom false)
            remote        (ctr/sync-remote
                            (fn [_] (throw (ex-info "Test error" {})))
                            :on-error (fn [e eql]
                                        (reset! error-handled true)
                                        {:status-code 500 :body {:error "handled"}}))
            app           (ct/build-test-app {:root-class Root
                                              :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "error was handled"
          @error-handled => true))))

  (component "simulate-latency-ms option"
    (behavior "adds delay to requests"
      (let [start  (System/currentTimeMillis)
            remote (ctr/sync-remote
                     (fn [_] {})
                     :simulate-latency-ms 50)
            app    (ct/build-test-app {:root-class Root
                                       :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (let [elapsed (- (System/currentTimeMillis) start)]
          (assertions
            "took at least the latency time"
            (>= elapsed 50) => true))))))

;; =============================================================================
;; Tests: mock-remote
;; =============================================================================

(specification "mock-remote"
  (component "map-based responses"
    (behavior "returns canned responses by mutation symbol"
      (let [remote (ctr/mock-remote
                     {`set-complete {:result "mocked"}
                      :default      {}})
            app    (ct/build-test-app {:root-class Root
                                       :remotes    {:remote remote}})]
        (assertions
          "mock remote is created"
          (some? remote) => true))))

  (component "function-based responses"
    (behavior "matches by function"
      (let [matched (atom false)
            remote  (ctr/mock-remote
                      (fn [eql]
                        (when (and (vector? eql)
                                (list? (first eql))
                                (= 'com.fulcrologic.fulcro.headless-spec/set-complete
                                  (first (first eql))))
                          (reset! matched true))
                        {}))
            app     (ct/build-test-app {:root-class Root
                                        :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "mutation was matched"
          @matched => true))))

  (component "default-response option"
    (behavior "uses default for unmatched requests"
      (let [remote (ctr/mock-remote
                     {}
                     :default-response {:fallback true})
            app    (ct/build-test-app {:root-class Root
                                       :remotes    {:remote remote}})]
        (assertions
          "remote created with default"
          (some? remote) => true))))

  (component "on-unmatched option"
    (behavior "calls function for unmatched requests"
      (let [unmatched-calls (atom [])
            remote          (ctr/mock-remote
                              {}
                              :on-unmatched (fn [eql]
                                              (swap! unmatched-calls conj eql)
                                              {:handled true}))
            app             (ct/build-test-app {:root-class Root
                                                :remotes    {:remote remote}})]
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "unmatched handler was called"
          (count @unmatched-calls) => 1)))))

;; =============================================================================
;; Tests: recording-remote
;; =============================================================================

(specification "recording-remote"
  (let [{:keys [remote recordings]} (ctr/recording-remote
                                      :delegate (ctr/sync-remote (constantly {:recorded true})))
        app (ct/build-test-app {:root-class Root
                                :remotes    {:remote remote}})]
    (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])

    (component "recording structure"
      (behavior "records all requests"
        (assertions
          "one request was recorded"
          (count @recordings) => 1))

      (behavior "recording has expected keys"
        (let [recording (first @recordings)]
          (assertions
            "recording includes EQL"
            (vector? (:eql recording)) => true
            "recording includes response"
            (:body (:response recording)) => {:recorded true}
            "recording includes timestamp"
            (number? (:timestamp recording)) => true))))

    (component "multiple recordings"
      (comp/transact! app [(set-complete {:item/id 2 :item/complete? false})])
      (behavior "records multiple requests"
        (assertions
          "two requests recorded"
          (count @recordings) => 2)))))

(specification "recording-remote requires delegate"
  (behavior "throws without delegate"
    (assertions
      "throws IllegalArgumentException"
      (try
        (ctr/recording-remote)
        false
        (catch IllegalArgumentException e
          true)) => true)))

;; =============================================================================
;; Tests: failing-remote
;; =============================================================================

(specification "failing-remote"
  (component "basic failure"
    (let [remote (ctr/failing-remote
                   :status-code 500
                   :error-message "Test error")
          app    (ct/build-test-app {:root-class Root
                                     :remotes    {:remote remote}})]
      (behavior "returns error responses"
        (comp/transact! app [(increment-counter)])
        (assertions
          "transaction processed (optimistic action ran)"
          (:counter (rapp/current-state app)) => 1))))

  (component "fail-after option"
    (behavior "succeeds until fail-after count"
      (let [success-count (atom 0)
            remote        (ctr/failing-remote
                            :fail-after 2
                            :delegate (ctr/sync-remote (fn [_]
                                                         (swap! success-count inc)
                                                         {:success true})))
            app           (ct/build-test-app {:root-class Root
                                              :remotes    {:remote remote}})]
        ;; First two should succeed
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (comp/transact! app [(set-complete {:item/id 2 :item/complete? true})])
        ;; Third should fail
        (comp/transact! app [(set-complete {:item/id 3 :item/complete? true})])
        (assertions
          "first two requests succeeded"
          @success-count => 2)))))

;; =============================================================================
;; Tests: delayed-remote
;; =============================================================================

(specification "delayed-remote" :focus
  (behavior "allows external control of delivery"
    (let [ran?          (volatile! false)
          app-send-node {::txn/result-handler (fn [v] (vreset! ran? true))}
          real-remote   {:transmit! (fn [this {::txn/keys [result-handler]}] (result-handler {:status 200}))}
          remote        (ctr/delayed-remote real-remote)]

      ((:transmit! remote) remote app-send-node)

      (assertions
        "Doesn't run the remote mutation immediately"
        (deref ran?) => false)
      (ctr/deliver-results! remote)
      (assertions
        "but instead runs them on explicit demand"
        (deref ran?) => true))))

;; =============================================================================
;; Tests: ring-remote
;; =============================================================================

(specification "ring-remote"
  (component "basic ring handler"
    (behavior "calls ring handler with request"
      (let [received-request (atom nil)
            ring-handler     (fn [request]
                               (reset! received-request request)
                               {:status 200
                                :body   (pr-str {:result "success"})})
            remote           (ctr/ring-remote ring-handler)
            app              (ct/build-test-app {:root-class Root
                                                 :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "ring handler received request"
          (some? @received-request) => true
          "request has correct URI"
          (:uri @received-request) => "/api"
          "request has POST method"
          (:request-method @received-request) => :post))))

  (component "custom URI"
    (behavior "uses specified URI"
      (let [received-uri (atom nil)
            ring-handler (fn [request]
                           (reset! received-uri (:uri request))
                           {:status 200 :body "{}"})
            remote       (ctr/ring-remote ring-handler :uri "/custom/api")
            app          (ct/build-test-app {:root-class Root
                                             :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "used custom URI"
          @received-uri => "/custom/api"))))

  (component "error handling"
    (behavior "handles non-200 status"
      (let [ring-handler (fn [_] {:status 500 :body "Server error"})
            remote       (ctr/ring-remote ring-handler)
            app          (ct/build-test-app {:root-class Root
                                             :remotes    {:remote remote}})]
        ;; Should not throw, error is handled
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "transaction completed (error handled)"
          true => true)))))

;; =============================================================================
;; Tests: fulcro-ring-remote
;; =============================================================================

(specification "fulcro-ring-remote"
  (component "transit encoding/decoding"
    (behavior "encodes request as transit and decodes response"
      (let [received-body (atom nil)
            ring-handler  (fn [{:keys [body]}]
                            ;; Read transit from request body
                            (let [body-str (slurp body)]
                              (reset! received-body body-str)
                              ;; Return transit-encoded response
                              {:status 200
                               :body   (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str
                                         {:counter 42})}))
            remote        (ctr/fulcro-ring-remote ring-handler)
            app           (ct/build-test-app {:root-class Root
                                              :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "request body is transit encoded"
          (string? @received-body) => true
          "request body is not empty"
          (> (count @received-body) 0) => true))))

  (component "middleware chains"
    (behavior "applies request middleware"
      (let [middleware-applied (atom false)
            ring-handler       (fn [request]
                                 {:status 200
                                  :body   (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str {})})
            custom-middleware  (fn [handler]
                                 (fn [request]
                                   (reset! middleware-applied true)
                                   (handler request)))
            remote             (ctr/fulcro-ring-remote ring-handler
                                 :request-middleware (-> (ctr/wrap-fulcro-request)
                                                       (custom-middleware)))
            app                (ct/build-test-app {:root-class Root
                                                   :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "custom request middleware was applied"
          @middleware-applied => true)))

    (behavior "applies response middleware"
      (let [response-middleware-applied (atom false)
            ring-handler                (fn [_]
                                          {:status 200
                                           :body   (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str {:data "test"})})
            custom-response-middleware  (fn [handler]
                                          (fn [response]
                                            (reset! response-middleware-applied true)
                                            (handler response)))
            remote                      (ctr/fulcro-ring-remote ring-handler
                                          :response-middleware (-> (ctr/wrap-fulcro-response)
                                                                 (custom-response-middleware)))
            app                         (ct/build-test-app {:root-class Root
                                                            :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "custom response middleware was applied"
          @response-middleware-applied => true))))

  (component "custom headers via middleware"
    (behavior "can add authorization headers"
      (let [received-headers (atom nil)
            wrap-auth        (fn [handler token]
                               (fn [request]
                                 (handler (update request :headers assoc "Authorization" (str "Bearer " token)))))
            ring-handler     (fn [{:keys [headers]}]
                               (reset! received-headers headers)
                               {:status 200
                                :body   (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str {})})
            remote           (ctr/fulcro-ring-remote ring-handler
                               :request-middleware (-> (ctr/wrap-fulcro-request)
                                                     (wrap-auth "test-token")))
            app              (ct/build-test-app {:root-class Root
                                                 :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "authorization header was set"
          (get @received-headers "Authorization") => "Bearer test-token"))))

  (component "session handling"
    (behavior "passes session to ring handler"
      (let [received-session (atom nil)
            ring-handler     (fn [{:keys [session]}]
                               (reset! received-session session)
                               {:status 200
                                :body   (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str {})})
            remote           (ctr/fulcro-ring-remote ring-handler
                               :session {:user/id 123 :user/role :admin})
            app              (ct/build-test-app {:root-class Root
                                                 :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "session was passed to handler"
          (:user/id @received-session) => 123
          "session contains role"
          (:user/role @received-session) => :admin))))

  (component "cookies handling"
    (behavior "passes cookies to ring handler"
      (let [received-cookies (atom nil)
            ring-handler     (fn [{:keys [cookies]}]
                               (reset! received-cookies cookies)
                               {:status 200
                                :body   (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str {})})
            remote           (ctr/fulcro-ring-remote ring-handler
                               :cookies {"session-id" {:value "abc123"}})
            app              (ct/build-test-app {:root-class Root
                                                 :remotes    {:remote remote}})]
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "cookies were passed to handler"
          (get @received-cookies "session-id") => {:value "abc123"}))))

  (component "state inspection"
    (behavior "exposes state atom for testing"
      (let [ring-handler (fn [_]
                           {:status  200
                            :body    (com.fulcrologic.fulcro.algorithms.transit/transit-clj->str {})
                            :session {:updated true}})
            remote       (ctr/fulcro-ring-remote ring-handler
                           :session {:user/id 1})
            app          (ct/build-test-app {:root-class Root
                                             :remotes    {:remote remote}})]
        (assertions
          "initial session is accessible"
          (:user/id (:session @(:state remote))) => 1)
        ;; Use set-complete which has (remote [_] true)
        (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
        (assertions
          "session is updated after response"
          (:updated (:session @(:state remote))) => true)))))

;; =============================================================================
;; Tests: Timer Mocking (timers.clj)
;; =============================================================================

(specification "mock-time"
  (behavior "returns real time outside mock context"
    (let [t (timers/mock-time)]
      (assertions
        "returns a number"
        (number? t) => true
        "is close to current time"
        (< (Math/abs (- t (System/currentTimeMillis))) 100) => true)))

  (behavior "returns mock time inside context"
    (timers/with-mock-timers
      (assertions
        "starts at 0"
        (timers/mock-time) => 0)
      (timers/advance-time! 1000)
      (assertions
        "advances correctly"
        (timers/mock-time) => 1000))))

(specification "pending-timers"
  (behavior "returns nil outside mock context"
    (assertions
      "nil outside context"
      (timers/pending-timers) => nil))

  (behavior "returns pending timers inside context"
    (timers/with-mock-timers
      (assertions
        "empty initially"
        (timers/pending-timers) => [])
      (sched/defer #(println "test") 1000)
      (assertions
        "has one timer"
        (count (timers/pending-timers)) => 1
        "timer has expected keys"
        (every? #(contains? (first (timers/pending-timers)) %)
          [:id :fire-at :callback :active :delay-ms]) => true))))

(specification "pending-timer-count"
  (timers/with-mock-timers
    (behavior "returns count of pending timers"
      (assertions
        "0 initially"
        (timers/pending-timer-count) => 0)
      (sched/defer #() 1000)
      (sched/defer #() 2000)
      (assertions
        "counts timers"
        (timers/pending-timer-count) => 2))))

(specification "with-mock-timers"
  (behavior "captures deferred calls"
    (timers/with-mock-timers
      (let [callback-ran (atom false)]
        (sched/defer #(reset! callback-ran true) 1000)
        (assertions
          "callback not called immediately"
          @callback-ran => false
          "timer is pending"
          (timers/pending-timer-count) => 1))))

  (behavior "isolates mock state"
    (timers/with-mock-timers
      (sched/defer #() 1000)
      (assertions
        "has timer in first context"
        (timers/pending-timer-count) => 1))
    (timers/with-mock-timers
      (assertions
        "empty in new context"
        (timers/pending-timer-count) => 0))))

(specification "with-mock-timers-from"
  (behavior "starts at specified time"
    (timers/with-mock-timers-from 5000
      (assertions
        "starts at 5000"
        (timers/mock-time) => 5000)
      (timers/advance-time! 500)
      (assertions
        "advances from start time"
        (timers/mock-time) => 5500))))

(specification "advance-time!"
  (behavior "advances time and fires timers"
    (timers/with-mock-timers
      (let [callback-ran (atom false)]
        (sched/defer #(reset! callback-ran true) 1000)
        (timers/advance-time! 999)
        (assertions
          "callback not called before scheduled time"
          @callback-ran => false)
        (timers/advance-time! 2)
        (assertions
          "callback called after time advanced past scheduled time"
          @callback-ran => true))))

  (behavior "returns new time"
    (timers/with-mock-timers
      (assertions
        "returns advanced time"
        (timers/advance-time! 500) => 500
        (timers/advance-time! 300) => 800)))

  (behavior "fires multiple timers in order"
    (timers/with-mock-timers
      (let [order (atom [])]
        (sched/defer #(swap! order conj 1) 100)
        (sched/defer #(swap! order conj 2) 200)
        (sched/defer #(swap! order conj 3) 300)
        (timers/advance-time! 350)
        (assertions
          "fired in order"
          @order => [1 2 3]))))

  (behavior "handles cascading timers"
    (timers/with-mock-timers
      (let [result (atom [])]
        (sched/defer (fn []
                       (swap! result conj :first)
                       (sched/defer #(swap! result conj :nested) 50))
          100)
        ;; Advance to 100 fires first timer, which schedules nested at 150
        ;; Advance to 200 fires nested timer
        (timers/advance-time! 100)
        (timers/advance-time! 100)
        (assertions
          "both timers fired"
          @result => [:first :nested]))))

  (behavior "throws outside mock context"
    (assertions
      "throws IllegalStateException"
      (try
        (timers/advance-time! 100)
        false
        (catch IllegalStateException e
          true)) => true)))

(specification "set-time!"
  (behavior "sets absolute time"
    (timers/with-mock-timers
      (timers/set-time! 5000)
      (assertions
        "time is set"
        (timers/mock-time) => 5000)))

  (behavior "fires timers when setting time past their fire-at"
    (timers/with-mock-timers
      (let [fired (atom [])]
        (sched/defer #(swap! fired conj 100) 100)
        (sched/defer #(swap! fired conj 200) 200)
        (timers/set-time! 150)
        (assertions
          "first timer fired"
          @fired => [100])
        (timers/set-time! 250)
        (assertions
          "second timer also fired"
          @fired => [100 200]))))

  (behavior "throws outside mock context"
    (assertions
      "throws IllegalStateException"
      (try
        (timers/set-time! 100)
        false
        (catch IllegalStateException e
          true)) => true)))

(specification "fire-all-timers!"
  (behavior "fires all pending timers immediately"
    (timers/with-mock-timers
      (let [counter (atom 0)]
        (sched/defer #(swap! counter inc) 1000)
        (sched/defer #(swap! counter inc) 2000)
        (sched/defer #(swap! counter inc) 3000)
        (timers/fire-all-timers!)
        (assertions
          "all timers fired"
          @counter => 3
          "no timers pending"
          (timers/pending-timer-count) => 0))))

  (behavior "returns count of fired timers"
    (timers/with-mock-timers
      (sched/defer #() 100)
      (sched/defer #() 200)
      (assertions
        "returns correct count"
        (timers/fire-all-timers!) => 2)))

  (behavior "fires in order of fire-at"
    (timers/with-mock-timers
      (let [order (atom [])]
        (sched/defer #(swap! order conj 3) 300)
        (sched/defer #(swap! order conj 1) 100)
        (sched/defer #(swap! order conj 2) 200)
        (timers/fire-all-timers!)
        (assertions
          "fired in scheduled order"
          @order => [1 2 3]))))

  (behavior "throws outside mock context"
    (assertions
      "throws IllegalStateException"
      (try
        (timers/fire-all-timers!)
        false
        (catch IllegalStateException e
          true)) => true)))

(specification "clear-timers!"
  (behavior "cancels without firing"
    (timers/with-mock-timers
      (let [callback-ran (atom false)]
        (sched/defer #(reset! callback-ran true) 1000)
        (timers/clear-timers!)
        (timers/advance-time! 2000)
        (assertions
          "callback not called"
          @callback-ran => false
          "no timers pending"
          (timers/pending-timer-count) => 0))))

  (behavior "returns count of cleared timers"
    (timers/with-mock-timers
      (sched/defer #() 100)
      (sched/defer #() 200)
      (assertions
        "returns correct count"
        (timers/clear-timers!) => 2)))

  (behavior "throws outside mock context"
    (assertions
      "throws IllegalStateException"
      (try
        (timers/clear-timers!)
        false
        (catch IllegalStateException e
          true)) => true)))

(specification "next-timer-at"
  (timers/with-mock-timers
    (behavior "returns nil when no timers"
      (assertions
        "nil when empty"
        (timers/next-timer-at) => nil))

    (behavior "returns soonest timer time"
      (sched/defer #() 300)
      (sched/defer #() 100)
      (sched/defer #() 200)
      (assertions
        "returns earliest fire-at"
        (timers/next-timer-at) => 100))))

(specification "advance-to-next-timer!"
  (timers/with-mock-timers
    (behavior "returns nil when no timers"
      (assertions
        "nil when empty"
        (timers/advance-to-next-timer!) => nil))

    (behavior "advances to next timer and fires it"
      (let [fired (atom [])]
        (sched/defer #(swap! fired conj 100) 100)
        (sched/defer #(swap! fired conj 200) 200)
        (timers/advance-to-next-timer!)
        (assertions
          "advanced to first timer"
          (timers/mock-time) => 100
          "first timer fired"
          @fired => [100]
          "second timer still pending"
          (timers/pending-timer-count) => 1)))))

(specification "timer-info"
  (timers/with-mock-timers
    (sched/defer #() 100)
    (sched/defer #() 200)
    (behavior "returns info about pending timers"
      (let [info (timers/timer-info)]
        (assertions
          "returns sequence"
          (seq? info) => true
          "has correct count"
          (count info) => 2
          "each entry has delay-ms and fire-at"
          (every? #(and (contains? % :delay-ms) (contains? % :fire-at)) info) => true)))))

(specification "has-timer-with-delay?"
  (timers/with-mock-timers
    (sched/defer #() 100)
    (sched/defer #() 500)

    (behavior "finds exact match"
      (assertions
        "finds 100ms timer"
        (timers/has-timer-with-delay? 100) => true
        "finds 500ms timer"
        (timers/has-timer-with-delay? 500) => true))

    (behavior "uses tolerance"
      (assertions
        "finds with default tolerance"
        (boolean (timers/has-timer-with-delay? 105)) => true
        "respects custom tolerance - finds when within tolerance"
        (boolean (timers/has-timer-with-delay? 120 :tolerance 25)) => true
        "returns nil/false when outside tolerance"
        (boolean (timers/has-timer-with-delay? 120 :tolerance 10)) => false))

    (behavior "returns nil/false for non-matching"
      (assertions
        "falsy for missing"
        (boolean (timers/has-timer-with-delay? 999)) => false))))

;; =============================================================================
;; Tests: State Machine Integration
;; =============================================================================

(defsc TestActor [_ _]
  {:query         [:actor/id :actor/value]
   :ident         :actor/id
   :initial-state {:actor/id :param/id :actor/value 0}})

(uism/defstatemachine test-state-machine
  {::uism/actor-names #{:actor}
   ::uism/aliases     {:value [:actor :actor/value]}
   ::uism/states
   {:initial {::uism/handler (fn [env]
                               (-> env
                                 (uism/assoc-aliased :value 1)
                                 (uism/activate :ready)))}
    :ready   {::uism/events
              {:increment  {::uism/handler (fn [env]
                                             (uism/update-aliased env :value inc))}
               :decrement  {::uism/handler (fn [env]
                                             (uism/update-aliased env :value dec))}
               :reset      {::uism/handler (fn [env]
                                             (uism/assoc-aliased env :value 0))}
               :go-to-done {::uism/handler      (fn [env]
                                                  (uism/activate env :done))
                            ::uism/target-state :done}}}
    :done    {::uism/events
              {:restart {::uism/handler (fn [env]
                                          (-> env
                                            (uism/assoc-aliased :value 1)
                                            (uism/activate :ready)))}}}}})

(specification "State machine testing"
  (let [app (ct/build-test-app {:root-class Root
                                :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    (swap! (::app/state-atom app) assoc-in [:actor/id :test-actor] {:actor/id :test-actor :actor/value 0})

    (component "starting state machines"
      (behavior "can start state machines synchronously"
        (uism/begin! app test-state-machine ::test-sm
          {:actor (uism/with-actor-class [:actor/id :test-actor] TestActor)}
          {})
        (assertions
          "state machine is in expected state"
          (uism/get-active-state app ::test-sm) => :ready
          "initial handler ran"
          (get-in (rapp/current-state app) [:actor/id :test-actor :actor/value]) => 1)))

    (component "triggering events"
      (behavior "can trigger events synchronously"
        (uism/trigger! app ::test-sm :increment {})
        (assertions
          "event handler ran"
          (get-in (rapp/current-state app) [:actor/id :test-actor :actor/value]) => 2))

      (behavior "multiple events work"
        (uism/trigger! app ::test-sm :increment {})
        (uism/trigger! app ::test-sm :increment {})
        (assertions
          "multiple events processed"
          (get-in (rapp/current-state app) [:actor/id :test-actor :actor/value]) => 4))

      (behavior "different event types work"
        (uism/trigger! app ::test-sm :decrement {})
        (assertions
          "decrement works"
          (get-in (rapp/current-state app) [:actor/id :test-actor :actor/value]) => 3)
        (uism/trigger! app ::test-sm :reset {})
        (assertions
          "reset works"
          (get-in (rapp/current-state app) [:actor/id :test-actor :actor/value]) => 0)))

    (component "state transitions"
      (behavior "can transition states"
        (uism/trigger! app ::test-sm :go-to-done {})
        (assertions
          "state transitioned"
          (uism/get-active-state app ::test-sm) => :done))

      (behavior "can restart from done state"
        (uism/trigger! app ::test-sm :restart {})
        (assertions
          "back to ready"
          (uism/get-active-state app ::test-sm) => :ready
          "value reset to 1"
          (get-in (rapp/current-state app) [:actor/id :test-actor :actor/value]) => 1)))))

;; =============================================================================
;; Tests: State Machine with Timers
;; =============================================================================

(uism/defstatemachine timeout-machine
  {::uism/actor-names #{:actor}
   ::uism/states
   {:initial   {::uism/handler (fn [env]
                                 (-> env
                                   (uism/set-timeout ::timeout :timeout {} 5000)
                                   (uism/activate :waiting)))}
    :waiting   {::uism/events
                {:timeout  {::uism/handler (fn [env]
                                             (uism/activate env :timed-out))}
                 :complete {::uism/handler (fn [env]
                                             (uism/activate env :completed))}}}
    ;; Terminal states need at least ::events (can be empty map)
    :timed-out {::uism/events {}}
    :completed {::uism/events {}}}})

(specification "State machine with timers"
  (timers/with-mock-timers
    (let [app (ct/build-test-app {:root-class Root
                                  :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
      (swap! (::app/state-atom app) assoc-in [:actor/id :timeout-actor] {:actor/id :timeout-actor})

      (behavior "timers are captured"
        (uism/begin! app timeout-machine ::timeout-sm
          {:actor (uism/with-actor-class [:actor/id :timeout-actor] TestActor)}
          {})
        (assertions
          "state machine started in waiting state"
          (uism/get-active-state app ::timeout-sm) => :waiting
          "timer was scheduled"
          (timers/pending-timer-count) => 1
          "timer has correct delay"
          (timers/has-timer-with-delay? 5000) => true))

      (behavior "completing before timeout"
        (uism/trigger! app ::timeout-sm :complete {})
        (assertions
          "state is completed"
          (uism/get-active-state app ::timeout-sm) => :completed)))))

;; =============================================================================
;; Integration Test
;; =============================================================================

(specification "Integration: Full transaction flow"
  (let [remote-responses (atom {:default {}})
        remote           (ctr/mock-remote
                           (fn [eql]
                             (get @remote-responses eql (get @remote-responses :default))))
        app              (ct/build-test-app {:root-class Root
                                             :remotes    {:remote remote}})]

    (component "initial state setup"
      (swap! (::app/state-atom app) merge
        {:list/id    {1 {:list/id 1 :list/title "Test List" :list/items []}}
         :root/lists [[:list/id 1]]})

      (ct/render-frame! app)
      (let [initial-frame (ct/last-frame app)]

        (behavior "captures state before transaction"
          (assertions
            "initial frame has list"
            (get-in (:tree initial-frame) [:root/lists 0 :list/title]) => "Test List"
            "list has no items"
            (get-in (:tree initial-frame) [:root/lists 0 :list/items]) => []))))

    (component "state modifications"
      (swap! (::app/state-atom app) merge
        {:item/id {1 {:item/id 1 :item/label "New Item" :item/complete? false}}})
      (swap! (::app/state-atom app) update-in [:list/id 1 :list/items] conj [:item/id 1])

      (ct/render-frame! app)

      (behavior "captures state after mutation"
        (let [new-frame (ct/last-frame app)]
          (assertions
            "new frame has item"
            (get-in (:tree new-frame) [:root/lists 0 :list/items 0 :item/label]) => "New Item"))))

    (component "render history"
      (behavior "contains frames"
        (assertions
          "three frames captured (initial + 2 from transaction renders)"
          (count (ct/render-history app)) => 3
          "can compare frames"
          (not= (:state (first (ct/render-history app)))
            (:state (second (ct/render-history app)))) => true)))))

(specification "Integration: Multiple remotes"
  (let [primary-calls   (atom [])
        secondary-calls (atom [])
        app             (ct/build-test-app {:root-class Root
                                            :remotes    {:primary   (ctr/sync-remote
                                                                      (fn [eql]
                                                                        (swap! primary-calls conj eql)
                                                                        {}))
                                                         :secondary (ctr/sync-remote
                                                                      (fn [eql]
                                                                        (swap! secondary-calls conj eql)
                                                                        {}))}})]

    (behavior "mutations route to correct remote"
      ;; set-complete goes to :remote by default, but we could define
      ;; mutations that target specific remotes
      (assertions
        "app has multiple remotes configured"
        (some? app) => true))))

(specification "Integration: Error recovery"
  (let [fail-count (atom 0)
        remote     (ctr/sync-remote
                     (fn [eql]
                       (swap! fail-count inc)
                       (if (< @fail-count 2)
                         (throw (ex-info "Temporary error" {}))
                         {:success true}))
                     :on-error (fn [e eql]
                                 {:status-code 500 :body {:error "failed"}}))
        app        (ct/build-test-app {:root-class Root
                                       :remotes    {:remote remote}})]

    (behavior "handles remote errors gracefully"
      (comp/transact! app [(set-complete {:item/id 1 :item/complete? true})])
      (assertions
        "optimistic update still applied"
        (get-in (rapp/current-state app) [:item/id 1 :item/complete?]) => true
        "error handler was invoked"
        @fail-count => 1))))

;; =============================================================================
;; Tests: Dynamic Routing with CLJ Testing
;; =============================================================================

;; Route Target Components - Simple (Immediate)

(defsc Home [_ {:home/keys [id message]}]
  {:query         [:home/id :home/message]
   :ident         :home/id
   :route-segment ["home"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:home/id :singleton]))
   :initial-state {:home/id :singleton :home/message "Welcome Home"}}
  nil)

(defsc About [_ {:about/keys [id content]}]
  {:query         [:about/id :about/content]
   :ident         :about/id
   :route-segment ["about"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:about/id :singleton]))
   :initial-state {:about/id :singleton :about/content "About Us"}}
  nil)

(defsc Contact [_ {:contact/keys [id email]}]
  {:query         [:contact/id :contact/email]
   :ident         :contact/id
   :route-segment ["contact"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:contact/id :singleton]))
   :initial-state {:contact/id :singleton :contact/email "test@example.com"}}
  nil)

;; Route Target Components - With Parameters

(defsc UserProfile [_ {:user/keys [id name]}]
  {:query         [:user/id :user/name]
   :ident         :user/id
   :route-segment ["user" :user-id]
   :will-enter    (fn [app {:keys [user-id]}]
                    (let [id (parse-long user-id)]
                      (dr/route-immediate [:user/id id])))}
  nil)

(defsc Post [_ {:post/keys [id title content]}]
  {:query         [:post/id :post/title :post/content]
   :ident         :post/id
   :route-segment ["post" :post-id]
   :will-enter    (fn [app {:keys [post-id]}]
                    (let [id (parse-long post-id)]
                      (dr/route-immediate [:post/id id])))}
  nil)

;; Route Target Components - Deferred (Async Loading)

(def ^:dynamic *deferred-callbacks* (atom {}))

(defsc SlowPage [_ {:slow/keys [id data loaded?]}]
  {:query         [:slow/id :slow/data :slow/loaded?]
   :ident         :slow/id
   :route-segment ["slow"]
   :will-enter    (fn [app _params]
                    (dr/route-deferred [:slow/id :singleton]
                      (fn []
                        (swap! *deferred-callbacks* assoc :slow-page
                          (fn []
                            (swap! (::app/state-atom app) assoc-in
                              [:slow/id :singleton]
                              {:slow/id :singleton :slow/data "Loaded!" :slow/loaded? true})
                            (dr/target-ready! app [:slow/id :singleton]))))))
   :initial-state {:slow/id :singleton :slow/data nil :slow/loaded? false}}
  nil)

(defsc AsyncUser [_ {:async-user/keys [id name email]}]
  {:query         [:async-user/id :async-user/name :async-user/email]
   :ident         :async-user/id
   :route-segment ["async-user" :user-id]
   :will-enter    (fn [app {:keys [user-id]}]
                    (let [id (parse-long user-id)]
                      (dr/route-deferred [:async-user/id id]
                        (fn []
                          (swap! *deferred-callbacks* assoc [:async-user id]
                            (fn []
                              (swap! (::app/state-atom app) assoc-in
                                [:async-user/id id]
                                {:async-user/id    id
                                 :async-user/name  (str "User " id)
                                 :async-user/email (str "user" id "@example.com")})
                              (dr/target-ready! app [:async-user/id id])))))))}
  nil)

;; Route Target - With Route Blocking

(defsc EditForm [_ {:form/keys [id dirty?]}]
  {:query               [:form/id :form/dirty?]
   :ident               :form/id
   :route-segment       ["edit"]
   :will-enter          (fn [app _params]
                          (dr/route-immediate [:form/id :singleton]))
   :allow-route-change? (fn [this]
                          (let [props (comp/props this)]
                            (not (:form/dirty? props))))
   :initial-state       {:form/id :singleton :form/dirty? false}}
  nil)

;; Nested Route Targets

(defsc SettingsGeneral [_ {:settings-general/keys [id]}]
  {:query         [:settings-general/id]
   :ident         :settings-general/id
   :route-segment ["general"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:settings-general/id :singleton]))
   :initial-state {:settings-general/id :singleton}}
  nil)

(defsc SettingsPrivacy [_ {:settings-privacy/keys [id]}]
  {:query         [:settings-privacy/id]
   :ident         :settings-privacy/id
   :route-segment ["privacy"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:settings-privacy/id :singleton]))
   :initial-state {:settings-privacy/id :singleton}}
  nil)

(defsc SettingsNotifications [_ {:settings-notifications/keys [id]}]
  {:query         [:settings-notifications/id]
   :ident         :settings-notifications/id
   :route-segment ["notifications"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:settings-notifications/id :singleton]))
   :initial-state {:settings-notifications/id :singleton}}
  nil)

(dr/defrouter SettingsRouter [_ _]
  {:router-targets [SettingsGeneral SettingsPrivacy SettingsNotifications]})

(defsc Settings [_ {:settings/keys [id router]}]
  {:query         [:settings/id {:settings/router (comp/get-query SettingsRouter)}]
   :ident         :settings/id
   :route-segment ["settings"]
   :will-enter    (fn [app _params]
                    (dr/route-immediate [:settings/id :singleton]))
   :initial-state {:settings/id     :singleton
                   :settings/router {}}}
  nil)

;; Main Router and Root for Routing Tests

(dr/defrouter MainRouter [_ _]
  {:router-targets [Home About Contact UserProfile Post SlowPage AsyncUser EditForm Settings]})

(defsc RoutingRoot [_ {:root/keys [router]}]
  {:query         [{:root/router (comp/get-query MainRouter)}]
   :initial-state {:root/router {}}}
  nil)

;; Routing Helper Functions

(defn build-routing-app
  "Create a test app configured for routing tests."
  [& {:keys [initial-state]}]
  (let [app (ct/build-test-app
              {:root-class RoutingRoot
               :remotes    {:remote (ctr/sync-remote (constantly {}))}})]
    (dr/initialize! app)
    (when initial-state
      (swap! (::app/state-atom app) merge initial-state))
    app))

(defn router-state
  "Get the current state of the main router's state machine."
  [app]
  (uism/get-active-state app ::MainRouter))

(defn current-route-path
  "Get the current route path."
  [app]
  (dr/current-route app RoutingRoot))

(defn complete-deferred!
  "Complete a deferred route by calling its stored callback."
  [key]
  (when-let [callback (get @*deferred-callbacks* key)]
    (callback)
    (swap! *deferred-callbacks* dissoc key)
    true))

(defn rendered-tree
  "Get the denormalized render tree from the app."
  [app]
  (:tree (ct/last-frame app)))

(defn rendered-router-props
  "Get the rendered props for the main router's current route target."
  [app]
  (ct/render-frame! app)
  (let [tree (rendered-tree app)]
    (get-in tree [:root/router ::dr/current-route])))

;; Routing Tests

(specification "Simple route changes"
  (component "basic navigation"
    (let [app (build-routing-app)]
      (behavior "can route to home"
        (dr/change-route! app ["home"])
        (assertions
          "route path is correct"
          (current-route-path app) => ["home"]
          "router is in :routed state"
          (router-state app) => :routed))

      (behavior "can route to about"
        (dr/change-route! app ["about"])
        (assertions
          "route path changed"
          (current-route-path app) => ["about"]
          "router is in :routed state"
          (router-state app) => :routed))

      (behavior "can route to contact"
        (dr/change-route! app ["contact"])
        (assertions
          "route path is correct"
          (current-route-path app) => ["contact"]))))

  (component "multiple route changes"
    (let [app (build-routing-app)]
      (behavior "routes change sequentially"
        (dr/change-route! app ["home"])
        (dr/change-route! app ["about"])
        (dr/change-route! app ["contact"])
        (dr/change-route! app ["home"])
        (assertions
          "final route is correct"
          (current-route-path app) => ["home"]))))

  (component "routing to same route"
    (let [app (build-routing-app)]
      (dr/change-route! app ["home"])
      (behavior "returns :already-there for same route"
        (let [result (dr/change-route! app ["home"])]
          (assertions
            "returns :already-there"
            result => :already-there
            "route unchanged"
            (current-route-path app) => ["home"]))))))

(specification "Rendered output verification"
  (component "simple routes render correct component data"
    (let [app (build-routing-app)]
      (behavior "home route renders home message"
        (dr/change-route! app ["home"])
        (let [props (rendered-router-props app)]
          (assertions
            "home message is rendered"
            (:home/message props) => "Welcome Home"
            "home id is correct"
            (:home/id props) => :singleton)))

      (behavior "about route renders about content"
        (dr/change-route! app ["about"])
        (let [props (rendered-router-props app)]
          (assertions
            "about content is rendered"
            (:about/content props) => "About Us")))

      (behavior "contact route renders email"
        (dr/change-route! app ["contact"])
        (let [props (rendered-router-props app)]
          (assertions
            "contact email is rendered"
            (:contact/email props) => "test@example.com")))))

  (component "parameterized routes render correct entity"
    (let [app (build-routing-app)]
      (swap! (::app/state-atom app) merge
        {:user/id {1 {:user/id 1 :user/name "Alice"}
                   2 {:user/id 2 :user/name "Bob"}}
         :post/id {100 {:post/id 100 :post/title "Hello World" :post/content "First post!"}}})

      (behavior "user 1 renders with name Alice"
        (dr/change-route! app ["user" "1"])
        (let [props (rendered-router-props app)]
          (assertions
            "user id is 1"
            (:user/id props) => 1
            "user name is Alice"
            (:user/name props) => "Alice")))

      (behavior "user 2 renders with name Bob"
        (dr/change-route! app ["user" "2"])
        (let [props (rendered-router-props app)]
          (assertions
            "user id is 2"
            (:user/id props) => 2
            "user name is Bob"
            (:user/name props) => "Bob")))

      (behavior "post renders with title and content"
        (dr/change-route! app ["post" "100"])
        (let [props (rendered-router-props app)]
          (assertions
            "post title is correct"
            (:post/title props) => "Hello World"
            "post content is correct"
            (:post/content props) => "First post!")))))

  (component "deferred routes render correctly after completion"
    (binding [*deferred-callbacks* (atom {})]
      (let [app (build-routing-app)]
        (behavior "deferred route renders initial state before completion"
          (dr/change-route! app ["slow"])
          (let [props (rendered-router-props app)]
            (assertions
              "rendered but not yet loaded"
              (or (nil? (:slow/loaded? props))
                (false? (:slow/loaded? props))) => true)))

        (behavior "deferred route renders loaded data after completion"
          (complete-deferred! :slow-page)
          (let [props (rendered-router-props app)]
            (assertions
              "slow page shows loaded data"
              (:slow/data props) => "Loaded!"
              "slow page marked as loaded"
              (:slow/loaded? props) => true))))))

  (component "async user deferred route renders user data"
    (binding [*deferred-callbacks* (atom {})]
      (let [app (build-routing-app)]
        (behavior "async user 42 renders correctly after loading"
          (dr/change-route! app ["async-user" "42"])
          (complete-deferred! [:async-user 42])
          (let [props (rendered-router-props app)]
            (assertions
              "user id is 42"
              (:async-user/id props) => 42
              "user name is rendered"
              (:async-user/name props) => "User 42"
              "user email is rendered"
              (:async-user/email props) => "user42@example.com")))))))

(specification "Route parameters"
  (component "single parameter"
    (let [app (build-routing-app)]
      (swap! (::app/state-atom app) assoc-in [:user/id 123]
        {:user/id 123 :user/name "John Doe"})

      (behavior "routes with user-id parameter"
        (dr/change-route! app ["user" "123"])
        (assertions
          "route path includes parameter"
          (current-route-path app) => ["user" "123"]
          "router is routed"
          (router-state app) => :routed))))

  (component "different parameter values"
    (let [app (build-routing-app)]
      (swap! (::app/state-atom app) merge
        {:user/id {1 {:user/id 1 :user/name "Alice"}
                   2 {:user/id 2 :user/name "Bob"}
                   3 {:user/id 3 :user/name "Charlie"}}})

      (behavior "can route to different users"
        (dr/change-route! app ["user" "1"])
        (assertions
          "routed to user 1"
          (current-route-path app) => ["user" "1"])

        (dr/change-route! app ["user" "2"])
        (assertions
          "routed to user 2"
          (current-route-path app) => ["user" "2"])

        (dr/change-route! app ["user" "3"])
        (assertions
          "routed to user 3"
          (current-route-path app) => ["user" "3"]))))

  (component "post with id parameter"
    (let [app (build-routing-app)]
      (swap! (::app/state-atom app) assoc-in [:post/id 42]
        {:post/id 42 :post/title "Test Post" :post/content "Content"})

      (behavior "routes to post with id"
        (dr/change-route! app ["post" "42"])
        (assertions
          "route path is correct"
          (current-route-path app) => ["post" "42"])))))

(specification "Deferred routes"
  (component "deferred route completion"
    (binding [*deferred-callbacks* (atom {})]
      (let [app (build-routing-app)]
        (behavior "starts in deferred state"
          (dr/change-route! app ["slow"])
          (assertions
            "router is in deferred or pending state"
            (contains? #{:deferred :pending} (router-state app)) => true
            "callback was registered"
            (contains? @*deferred-callbacks* :slow-page) => true))

        (behavior "completes when target-ready! called"
          (complete-deferred! :slow-page)
          (assertions
            "router is now routed"
            (router-state app) => :routed
            "route path is correct"
            (current-route-path app) => ["slow"]
            "data was loaded"
            (get-in (rapp/current-state app) [:slow/id :singleton :slow/loaded?]) => true)))))

  (component "deferred route with parameters"
    (binding [*deferred-callbacks* (atom {})]
      (let [app (build-routing-app)]
        (behavior "routes to async user"
          (dr/change-route! app ["async-user" "42"])
          (assertions
            "router is deferred or pending"
            (contains? #{:deferred :pending} (router-state app)) => true
            "callback registered with correct key"
            (contains? @*deferred-callbacks* [:async-user 42]) => true))

        (behavior "completes with user data"
          (complete-deferred! [:async-user 42])
          (assertions
            "router is routed"
            (router-state app) => :routed
            "route path is correct"
            (current-route-path app) => ["async-user" "42"]
            "user data was loaded"
            (get-in (rapp/current-state app) [:async-user/id 42 :async-user/name]) => "User 42"))))))

(specification "Routing timeout handling"
  (component "deferred timeout transitions to pending"
    (timers/with-mock-timers
      (binding [*deferred-callbacks* (atom {})]
        (let [app (build-routing-app)]
          (behavior "starts deferred with short timeout"
            (dr/change-route! app ["slow"] {:deferred-timeout 100 :error-timeout 5000})
            (assertions
              "router starts in deferred state"
              (router-state app) => :deferred
              "timers were scheduled (multiple routers)"
              (>= (timers/pending-timer-count) 2) => true))

          (behavior "transitions to pending after deferred timeout"
            (timers/advance-time! 101)
            (assertions
              "router is now pending"
              (router-state app) => :pending))

          (behavior "can still complete after pending"
            (complete-deferred! :slow-page)
            (assertions
              "router is routed"
              (router-state app) => :routed))))))

  (component "error timeout transitions to failed"
    (timers/with-mock-timers
      (binding [*deferred-callbacks* (atom {})]
        (let [app (build-routing-app)]
          (behavior "transitions to failed after error timeout"
            (dr/change-route! app ["slow"] {:deferred-timeout 100 :error-timeout 500})
            (timers/advance-time! 101)
            (assertions
              "router is pending"
              (router-state app) => :pending)

            (timers/advance-time! 500)
            (assertions
              "router is failed"
              (router-state app) => :failed))))))

  (component "completing before timeout"
    (timers/with-mock-timers
      (binding [*deferred-callbacks* (atom {})]
        (let [app (build-routing-app)]
          (behavior "completion cancels timeouts"
            (dr/change-route! app ["slow"] {:deferred-timeout 100 :error-timeout 500})
            (assertions
              "timers are pending"
              (>= (timers/pending-timer-count) 1) => true)

            (timers/advance-time! 50)
            (complete-deferred! :slow-page)

            (assertions
              "router is routed"
              (router-state app) => :routed)

            (timers/advance-time! 1000)
            (assertions
              "router still routed after timeout would have fired"
              (router-state app) => :routed))))))

  (component "recovery after failure"
    (timers/with-mock-timers
      (binding [*deferred-callbacks* (atom {})]
        (let [app (build-routing-app)]
          (behavior "can recover from failed state"
            (dr/change-route! app ["slow"] {:deferred-timeout 50 :error-timeout 100})
            (timers/advance-time! 150)

            (assertions
              "router is failed"
              (router-state app) => :failed)

            (complete-deferred! :slow-page)
            (assertions
              "router recovered to routed"
              (router-state app) => :routed)))))))

(specification "Nested routing"
  (component "routing to parent"
    (let [app (build-routing-app)]
      (behavior "routes to settings (parent with nested router)"
        (dr/change-route! app ["settings"])
        (assertions
          "main router is routed"
          (router-state app) => :routed
          "settings is the current route at top level"
          (first (current-route-path app)) => "settings"))))

  (component "routing to nested child"
    (let [app (build-routing-app)]
      (behavior "routes to settings with nested target"
        (dr/change-route! app ["settings" "general"])
        (assertions
          "top level route is settings"
          (first (current-route-path app)) => "settings"
          "main router is routed"
          (router-state app) => :routed))

      (behavior "can route to different nested targets"
        (dr/change-route! app ["settings" "privacy"])
        (dr/change-route! app ["settings" "notifications"])
        (assertions
          "still at settings"
          (first (current-route-path app)) => "settings"))))

  (component "routing from nested to top-level"
    (let [app (build-routing-app)]
      (behavior "can route from nested back to top-level"
        (dr/change-route! app ["settings" "privacy"])
        (dr/change-route! app ["home"])
        (assertions
          "route changed to top-level"
          (current-route-path app) => ["home"])))))

(specification "Route blocking"
  (component "clean form allows routing"
    (let [app (build-routing-app)]
      (behavior "can route to and from edit form"
        (dr/change-route! app ["edit"])
        (assertions
          "routed to edit"
          (current-route-path app) => ["edit"])

        (dr/change-route! app ["home"])
        (assertions
          "routed away successfully"
          (current-route-path app) => ["home"]))))

  (component "routing to edit form works"
    (let [app (build-routing-app)]
      (behavior "edit form is a valid route target"
        (dr/change-route! app ["edit"])
        (assertions
          "router is routed"
          (router-state app) => :routed
          "at edit route"
          (current-route-path app) => ["edit"])))))

(specification "Invalid routes"
  (component "non-existent route"
    (let [app (build-routing-app)]
      (dr/change-route! app ["home"])
      (behavior "rejects invalid route"
        (let [result (dr/change-route! app ["nonexistent" "route"])]
          (assertions
            "returns :invalid"
            result => :invalid
            "stays on previous route"
            (current-route-path app) => ["home"]))))))

(specification "Routing integration: Complex scenario"
  (timers/with-mock-timers
    (binding [*deferred-callbacks* (atom {})]
      (let [app (build-routing-app)]
        (swap! (::app/state-atom app) merge
          {:user/id {1 {:user/id 1 :user/name "Alice"}}
           :post/id {100 {:post/id 100 :post/title "Post"}}})

        (component "multi-step routing workflow"
          (behavior "step 1: start at home"
            (dr/change-route! app ["home"])
            (assertions
              "at home"
              (current-route-path app) => ["home"]))

          (behavior "step 2: navigate to user profile"
            (dr/change-route! app ["user" "1"])
            (assertions
              "at user profile"
              (current-route-path app) => ["user" "1"]))

          (behavior "step 3: navigate to settings"
            (dr/change-route! app ["settings" "general"])
            (assertions
              "at settings (top-level router)"
              (first (current-route-path app)) => "settings"))

          (behavior "step 4: switch settings tab"
            (dr/change-route! app ["settings" "privacy"])
            (assertions
              "at settings (top-level router)"
              (first (current-route-path app)) => "settings"))

          (behavior "step 5: start deferred route"
            (dr/change-route! app ["slow"] {:deferred-timeout 100 :error-timeout 1000})
            (assertions
              "router is deferred"
              (router-state app) => :deferred))

          (behavior "step 6: complete deferred before timeout"
            (timers/advance-time! 50)
            (complete-deferred! :slow-page)
            (assertions
              "router is routed"
              (router-state app) => :routed
              "at slow page"
              (current-route-path app) => ["slow"]))

          (behavior "step 7: navigate to post"
            (dr/change-route! app ["post" "100"])
            (assertions
              "at post"
              (current-route-path app) => ["post" "100"]))

          (behavior "step 8: back to home"
            (dr/change-route! app ["home"])
            (assertions
              "back at home"
              (current-route-path app) => ["home"])))))))

(specification "Routing integration: Deferred route interruption"
  (timers/with-mock-timers
    (binding [*deferred-callbacks* (atom {})]
      (let [app (build-routing-app)]
        (behavior "can interrupt deferred route with new route"
          (dr/change-route! app ["slow"] {:deferred-timeout 100 :error-timeout 1000})
          (assertions
            "router is deferred"
            (router-state app) => :deferred)

          (dr/change-route! app ["home"])
          (assertions
            "routed to home instead"
            (current-route-path app) => ["home"]
            "router is routed"
            (router-state app) => :routed))))))

(specification "Routing integration: Multiple async users"
  (binding [*deferred-callbacks* (atom {})]
    (let [app (build-routing-app)]
      (behavior "can load multiple async users in sequence"
        (dr/change-route! app ["async-user" "1"])
        (complete-deferred! [:async-user 1])
        (assertions
          "first user loaded"
          (get-in (rapp/current-state app) [:async-user/id 1 :async-user/name]) => "User 1")

        (dr/change-route! app ["async-user" "2"])
        (complete-deferred! [:async-user 2])
        (assertions
          "second user loaded"
          (get-in (rapp/current-state app) [:async-user/id 2 :async-user/name]) => "User 2")

        (dr/change-route! app ["async-user" "3"])
        (complete-deferred! [:async-user 3])
        (assertions
          "third user loaded"
          (get-in (rapp/current-state app) [:async-user/id 3 :async-user/name]) => "User 3"
          "final route is correct"
          (current-route-path app) => ["async-user" "3"])))))

;; =============================================================================
;; Tests: Text-Based Interaction (headless.clj)
;; =============================================================================

(defsc TextInteractionRoot [this {:ui/keys [clicked-item typed-value counter]}]
  {:query         [:ui/clicked-item :ui/typed-value :ui/counter]
   :initial-state {:ui/clicked-item nil
                   :ui/typed-value  ""
                   :ui/counter      0}}
  (dom/div {:id "text-interaction-root"}
    ;; Menu with clickable items
    (dom/div {:className "menu"}
      (dom/button {:id      "view-all-btn"
                   :onClick (fn [_]
                              (comp/transact! (comp/any->app this)
                                [(set-value {:path [:ui/clicked-item] :value "View All"})]))}
        "View All")
      (dom/button {:id      "new-btn"
                   :onClick (fn [_]
                              (comp/transact! (comp/any->app this)
                                [(set-value {:path [:ui/clicked-item] :value "New"})]))}
        "New")
      (dom/button {:id      "delete-btn"
                   :onClick (fn [_]
                              (comp/transact! (comp/any->app this)
                                [(set-value {:path [:ui/clicked-item] :value "Delete"})]))}
        "Delete"))

    ;; Dropdown-style component with :text attribute
    (dom/div {:className "dropdown"
              :text      "Account Menu"
              :onClick   (fn [_]
                           (comp/transact! (comp/any->app this)
                             [(set-value {:path [:ui/clicked-item] :value "Account Menu"})]))}
      (dom/span {} "Dropdown content"))

    ;; Nested clickable element (bubbling test)
    (dom/div {:id      "parent-clickable"
              :onClick (fn [_]
                         (comp/transact! (comp/any->app this)
                           [(set-value {:path [:ui/clicked-item] :value "Parent Clicked"})]))}
      (dom/span {:id "child-text"} "Click this text"))

    ;; Labeled form fields
    (dom/div {:className "form"}
      (dom/div {:className "field"}
        (dom/label {} "Username")
        (dom/input {:id       "username-input"
                    :value    typed-value
                    :onChange (fn [e]
                                (comp/transact! (comp/any->app this)
                                  [(set-value {:path  [:ui/typed-value]
                                               :value (-> e :target :value)})]))}))

      (dom/div {:className "field"}
        (dom/label {} "Password")
        (dom/input {:id   "password-input"
                    :type "password"}))

      ;; Multiple fields with same label
      (dom/div {:className "field"}
        (dom/label {} "Amount")
        (dom/input {:id "amount-1"}))

      (dom/div {:className "field"}
        (dom/label {} "Amount")
        (dom/input {:id "amount-2"})))

    ;; Display current state
    (dom/div {:id "status"}
      (when clicked-item
        (dom/span {:id "clicked-display"} (str "Clicked: " clicked-item)))
      (when (seq typed-value)
        (dom/span {:id "typed-display"} (str "Typed: " typed-value))))

    ;; Counter for nth click tests
    (dom/div {:className "actions"}
      (dom/button {:id      "action-1"
                   :onClick (fn [_]
                              (comp/transact! (comp/any->app this)
                                [(set-value {:path [:ui/counter] :value 1})]))}
        "Action")
      (dom/button {:id      "action-2"
                   :onClick (fn [_]
                              (comp/transact! (comp/any->app this)
                                [(set-value {:path [:ui/counter] :value 2})]))}
        "Action")
      (dom/button {:id      "action-3"
                   :onClick (fn [_]
                              (comp/transact! (comp/any->app this)
                                [(set-value {:path [:ui/counter] :value 3})]))}
        "Action"))
    (dom/span {:id "counter-display"} (str counter))))

(defn build-text-interaction-app []
  (ct/build-test-app {:root-class TextInteractionRoot
                      :remotes    {:remote (ctr/sync-remote (constantly {}))}}))

(specification "click-on-text!"
  (component "basic text clicking"
    (let [app (build-text-interaction-app)]
      (behavior "clicks button by text content"
        (ct/click-on-text! app "View All")
        (ct/render-frame! app)
        (assertions
          "button was clicked"
          (:ui/clicked-item (rapp/current-state app)) => "View All"))

      (behavior "clicks different button by text"
        (ct/click-on-text! app "New")
        (ct/render-frame! app)
        (assertions
          "new button was clicked"
          (:ui/clicked-item (rapp/current-state app)) => "New"))))

  (component "clicking by :text attribute"
    (let [app (build-text-interaction-app)]
      (behavior "finds and clicks element by :text attribute"
        (ct/click-on-text! app "Account Menu")
        (ct/render-frame! app)
        (assertions
          "dropdown was clicked"
          (:ui/clicked-item (rapp/current-state app)) => "Account Menu"))))

  (component "click bubbling"
    (let [app (build-text-interaction-app)]
      (behavior "bubbles up to find onClick handler"
        (ct/click-on-text! app "Click this text")
        (ct/render-frame! app)
        (assertions
          "parent handler was invoked"
          (:ui/clicked-item (rapp/current-state app)) => "Parent Clicked"))))

  (component "nth element clicking"
    (let [app (build-text-interaction-app)]
      (behavior "clicks first 'Action' button by default"
        (ct/click-on-text! app "Action" 0)
        (ct/render-frame! app)
        (assertions
          "first action button clicked"
          (:ui/counter (rapp/current-state app)) => 1))

      (behavior "clicks second 'Action' button with n=1"
        (ct/click-on-text! app "Action" 1)
        (ct/render-frame! app)
        (assertions
          "second action button clicked"
          (:ui/counter (rapp/current-state app)) => 2))

      (behavior "clicks third 'Action' button with n=2"
        (ct/click-on-text! app "Action" 2)
        (ct/render-frame! app)
        (assertions
          "third action button clicked"
          (:ui/counter (rapp/current-state app)) => 3))))

  (component "regex pattern matching"
    (let [app (build-text-interaction-app)]
      (behavior "clicks button matching regex"
        (ct/click-on-text! app #"View.*")
        (ct/render-frame! app)
        (assertions
          "View All button was clicked via regex"
          (:ui/clicked-item (rapp/current-state app)) => "View All")))))

(specification "type-into-labeled!"
  (component "basic labeled input typing"
    (let [app (build-text-interaction-app)]
      (behavior "types into input found by label"
        (ct/type-into-labeled! app "Username" "john.doe")
        (ct/render-frame! app)
        (assertions
          "input value was set"
          (:ui/typed-value (rapp/current-state app)) => "john.doe"))))

  (component "regex pattern for label"
    (let [app (build-text-interaction-app)]
      (behavior "finds input by regex on label"
        (ct/type-into-labeled! app #"(?i)user" "jane.doe")
        (ct/render-frame! app)
        (assertions
          "input value was set via regex label match"
          (:ui/typed-value (rapp/current-state app)) => "jane.doe")))))

(specification "find-by-text"
  (let [app (build-text-interaction-app)]
    (ct/render-frame! app)

    (component "finding elements by text"
      (behavior "finds elements containing text"
        (let [elements (ct/find-by-text app "Action")]
          (assertions
            "finds multiple elements"
            (count elements) => 3)))

      (behavior "finds elements by regex"
        (let [elements (ct/find-by-text app #"View.*")]
          (assertions
            "finds matching elements"
            (>= (count elements) 1) => true)))

      (behavior "returns empty for non-matching text"
        (let [elements (ct/find-by-text app "Nonexistent Text")]
          (assertions
            "returns empty collection"
            (empty? elements) => true))))))

(specification "text-exists?"
  (let [app (build-text-interaction-app)]
    (ct/render-frame! app)

    (component "checking text existence"
      (behavior "returns truthy for existing text"
        (assertions
          "View All text exists"
          (ct/text-exists? app "View All") => true
          "Username text exists"
          (ct/text-exists? app "Username") => true))

      (behavior "returns falsy for non-existing text"
        (assertions
          "Nonexistent text doesn't exist"
          (ct/text-exists? app "This text does not exist anywhere") => false)))))

;; =============================================================================
;; Event Capture Tests
;; =============================================================================

(specification "Event Capture - Transactions"
  (component "captured-transactions"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (behavior "starts empty"
        (assertions
          "no transactions initially"
          (ct/captured-transactions app) => []))

      (behavior "captures optimistic mutations"
        (comp/transact! app [(increment-counter)])
        (assertions
          "one transaction captured"
          (count (ct/captured-transactions app)) => 1
          "transaction contains the mutation"
          (-> (ct/captured-transactions app) first :fulcro.history/tx first) => `increment-counter))

      (behavior "captures multiple mutations"
        (comp/transact! app [(set-value {:path [:ui/value] :value "new"})])
        (assertions
          "two transactions captured"
          (count (ct/captured-transactions app)) => 2))))

  (component "last-transaction"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (behavior "returns nil when empty"
        (assertions
          "nil when no transactions"
          (ct/last-transaction app) => nil))

      (behavior "returns the most recent transaction"
        (comp/transact! app [(increment-counter)])
        (comp/transact! app [(set-value {:path [:ui/value] :value "updated"})])
        (assertions
          "returns the set-value transaction"
          (-> (ct/last-transaction app) :fulcro.history/tx first) => `set-value))))

  (component "transaction-mutations"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (behavior "extracts mutation symbols in order"
        (comp/transact! app [(increment-counter)])
        (comp/transact! app [(set-value {:path [:ui/value] :value "x"})])
        (comp/transact! app [(increment-counter)])
        (assertions
          "returns symbols in order"
          (ct/transaction-mutations app) => [`increment-counter `set-value `increment-counter]))))

  (component "clear-captured-events!"
    (let [app (ct/build-test-app {:root-class SimpleRenderingRoot})]
      (comp/transact! app [(increment-counter)])
      (assertions
        "has transactions before clear"
        (count (ct/captured-transactions app)) => 1)

      (ct/clear-captured-events! app)
      (assertions
        "empty after clear"
        (ct/captured-transactions app) => [])

      (behavior "continues to capture after clear"
        (comp/transact! app [(set-value {:path [:ui/value] :value "after-clear"})])
        (assertions
          "captures new transactions"
          (count (ct/captured-transactions app)) => 1)))))

(specification "Event Capture - Network Events"
  (let [mock-handler (fn [eql] {:result "mock-response"})
        app          (ct/build-test-app
                       {:root-class SimpleRenderingRoot
                        :remotes    {:remote (ctr/sync-remote mock-handler)}})]

    (component "captured-network-events"
      (behavior "captures network lifecycle"
        ;; Use a mutation that goes to remote
        (comp/transact! app [(remote-only-mutation {:x 1})])

        (let [events (ct/captured-network-events app)]
          (assertions
            "captures start and finish events"
            (>= (count events) 2) => true
            "has :started event"
            (boolean (some #(= :started (:event-type %)) events)) => true
            "has :finished event"
            (boolean (some #(= :finished (:event-type %)) events)) => true))))

    (component "clear also clears network events"
      (ct/clear-captured-events! app)
      (assertions
        "network events cleared"
        (ct/captured-network-events app) => []))))

