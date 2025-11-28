We want to be able to run/test Fulcro applications in CLJ. There is already extensive support in Fulcro for running in CLJ, but the
async operation causes problem, and we also need to make sure that the rendering system has the properly-bound dynamic variables.

we can run from CLJ (with the html hiccip deps), and that is fun for demo purposes, but for testing it has a few limitations:

* There's no clean way to write tests where the full-stack async behavior is controlled. 
  * Synchronous control is needed. For example: have a short-term history of render frames, where CLJ can explicitly control that a render happens after optimistic action, remote happens (possibly async, with blocking in tests), then another render. Probably just a plug-in for `submit-tx!` (transaction processing).
  * Pathom could be sync or async on the server. 
* Remotes in CLJ are not remote, but there needs to be a clearer interface that leverages the proper ring stack of the API so that things like authentication, env creation, etc can be managed in tests.

Here's an example application that can run from CLJ (with the html hiccip deps) and has these limitations:

First, the application support:

```clojure
(ns app.application
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [clojure.string :as str]
    [clojure.pprint :refer [pprint]]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.rpl.specter :as sp]
    [taoensso.timbre :as log]
    [taipei-404.html :refer [html->hiccup]]))

(defn hiccup-element-by-id [hiccup id]
  (sp/select-first (sp/walker (fn [n]
                                (and
                                  (vector? n)
                                  (map? (second n))
                                  (= id (:id (second n))))))
    hiccup))

(defn element->hiccup [element]
  #?(:clj
     (sp/transform
       (sp/walker map?)
       (fn [m] (dissoc m :data-reactroot :data-reactid :data-react-checksum))
       (first (html->hiccup (dom/render-to-str element))))))

(defn render-element [element]
  (pprint (element->hiccup element)))

(defn render-app-element [{::app/keys [state-atom runtime-atom]} id]
  (let [state-map @state-atom
        {::app/keys [root-class root-factory]} @runtime-atom
        query     (comp/get-query root-class state-map)
        tree      (fdn/db->tree query state-map state-map)]
    (-> (root-factory tree)
      (element->hiccup)
      (hiccup-element-by-id id))))

(defn click-on! [app id]
  #?(:clj
     (let [[_ {:keys [onClick]}] (render-app-element app id)
           onClick (str/replace onClick "&quot;" "\"")
           txn     (when (string? onClick) (some-> onClick read-string))]
       (when txn
         (comp/transact! app txn)))))

(defn txn-handler
  "A CLJC function for running transactions as handlers. In CLJ emits the transaction as a string. In CLJS it
   emits a fn."
  [app-ish txn]
  #?(:clj  (pr-str txn)
     :cljs (fn [] (comp/transact! app-ish txn))))

(defn build-app
  ([] (build-app (volatile! true)))
  ([render-data-atom?]
   (let [last-state (volatile! {})]
     #?(:cljs
        (app/fulcro-app {})
        :clj
        (letfn [(render [{::app/keys [runtime-atom state-atom] :as app} {:keys [force-root?]}]
                  (let [state-map @state-atom]
                    (when (or force-root? (not= state-map @last-state))
                      (let [{::app/keys [root-class root-factory]} @runtime-atom
                            query (comp/get-query root-class state-map)
                            tree  (fdn/db->tree query state-map state-map)]
                        (vreset! last-state state-map)
                        (if @render-data-atom?
                          (pprint tree)
                          (binding [comp/*app*    app
                                    comp/*parent* nil
                                    comp/*shared* (comp/shared app)]
                            (render-element (root-factory tree))))))))]
          (app/fulcro-app
            {:optimized-render! render}))))))
```

and a TODO app that uses it:

```clojure
(ns app.todo
  (:require
    #?(:cljs [com.fulcrologic.fulcro.dom :as dom]
       :clj  [com.fulcrologic.fulcro.dom-server :as dom])
    [app.apis.todo :as todo]
    [app.sample-servers.registry]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as target]
    [com.fulcrologic.rad.type-support.date-time :as dt]
    [app.application :refer [build-app render-app-element click-on! txn-handler]]))

(defonce render-data? (atom false))
(defonce SPA (build-app render-data?))

(defsc Item [this {:item/keys [id label complete?]}]
  {:query [:item/id :item/label :item/complete?]
   :ident :item/id}
  (dom/div {:id (str "item" id)}
    (dom/input {:type    "checkbox"
                :id      (str "item" id "-checkbox")
                :onClick (txn-handler this [(todo/set-complete {:item/id id :item/complete? (not complete?)})])
                :checked (boolean complete?)})
    (dom/span (str label))))

(def ui-item (comp/factory Item {:keyfn :item/id}))

(defsc TodoList [this {:list/keys [id title items]}]
  {:query [:list/id :list/title {:list/items (comp/get-query Item)}]
   :ident :list/id}
  (dom/div {:id (str "list" id)}
    (dom/h4 {} (str title))
    (dom/ul {}
      (mapv ui-item items))))

(def ui-list (comp/factory TodoList {:keyfn :list/id}))

(defsc Root [this {:todo/keys [lists]}]
  {:query         [{:todo/lists (comp/get-query TodoList)}]
   :initial-state {:todo/lists []}}
  (dom/div
    (mapv ui-list lists)))

(defn numbered-name
  "Generates a string based on nm that has a number appended which
   will likely be different each time you call it."
  [nm]
  (str nm "-" (mod (long (/ (dt/now-ms) 100)) 10000)))
```

Use fulcro-in-clj.md (this file) to build up the plan and analysis that covers what work we need to do, and let's add fully-tested support to the CLJ-side of this library, including full tests with an application that uses dynamic routing (which in turn uses UI state machines) and forms. We MUST NOT modify anything that would change how the CLJS implementation works at all. Fulcro is highly-pluggable, and we should not need to modify any existing implementation namespace. If some kind of tweak is needed then make sure reader conditionals isolate that change to CLJ only.

I'm thinking the transaction processing (see tx_processing.cljc) can be dramatically simplified for CLJ mode. A CLJ remote accepts a function that can take EDN and either synchronously returns the result, or returns an async channel where the result will appear. Thus the remote implementation can control blocking (on the channel) using `<!!` to block. If integrated with the transaction processing properly (a customized version, perhaps) then we should be able to run any number of Fulcro transactions (via transact! or load!), render a frame before we do the "I/O", simulate the network round trip, then render a frame. Since this can then be controlled in a synchronous way we can use this for both development experimentation (look at the before/after render, which could be stored in a length-limited queue in the runtime atom of the app), and for syncrhonous testing cases. Of course all of this should be in new namespaces for now.

---

# Analysis and Implementation Plan

## 1. Existing Fulcro Infrastructure Analysis

### 1.1 Transaction Processing

The existing transaction processing system (`tx_processing.cljc`) already has significant CLJ support:

**Default Transaction Processing** (`default-tx!`):
- Uses `scheduling.cljc` for async deferral
- CLJ mode uses `core.async` channels for scheduling (`async/timeout`, `async/go`)
- Queues are stored in the runtime-atom: `::submission-queue`, `::active-queue`, `::send-queues`

**Synchronous Transaction Processing** (`synchronous_tx_processing.cljc`):
- Already exists! Uses atoms instead of async scheduling
- `with-synchronous-transactions` installs sync tx processor on an app
- `run-queue!` processes all work in a loop until complete
- CLJ-specific thread tracking via `in-transaction` macro
- Blocks calling thread with `Thread/sleep` polling until complete

**Key Functions**:
- `submit-sync-tx!` - Synchronous transaction submission
- `run-all-immediate-work!` - Process one step of all queues
- `available-work?` - Check if more work exists

### 1.2 Remote System

Remotes are simple maps with `:transmit!` function:

```clojure
{:transmit! (fn [remote send-node] ...)
 :abort!    (fn [remote abort-id] ...)}
```

The `send-node` contains:
- `::txn/ast` - EQL AST to send
- `::txn/result-handler` - Function to call with `{:status-code :body ...}`
- `::txn/update-handler` - Progress callback

**Critical Insight**: The remote is responsible for calling `result-handler`. It can do so synchronously OR asynchronously. For CLJ testing, we create a synchronous remote.

### 1.3 Rendering System

**Algorithm Override Points**:
- `:optimized-render!` - Main render function, receives `(app options)`
- `:core-render!` - Coordinator that calls optimized-render!
- `:before-render` - Hook called before rendering

**Dynamic Vars** (must be bound for component code):
- `comp/*app*` - Current Fulcro app
- `comp/*parent*` - Parent component (nil for root)
- `comp/*shared*` - Shared props

**Existing CLJ Rendering** (`dom-server`):
- `render-to-str` produces HTML string
- Works with the example code's hiccup conversion

### 1.4 UI State Machines

**Core Functions**:
- `begin!` - Start a state machine (wraps as mutation)
- `trigger!` - Send events to state machine (wraps as mutation)
- Handlers receive `env` and return modified `env`

**Async Considerations**:
- Timeouts use `sched/defer` which uses `core.async` on CLJ
- Timeout events queue through `trigger-state-machine-event!` mutation

**Testing Strategy**:
- Sync tx processing handles async naturally via thread blocking
- Can mock `sched/defer` for deterministic timeout testing
- Can manually trigger timeout events

### 1.5 Dynamic Routing

**Core Components**:
- `RouterStateMachine` - Manages route transitions
- States: `:initial`, `:deferred`, `:pending`, `:failed`, `:routed`
- `will-enter` returns: `route-immediate`, `route-deferred`, or `route-with-path-ordered-transaction`

**Route Change Flow**:
1. `change-route!` validates and starts routing
2. For each router, calls `will-enter` on target
3. If immediate: applies route directly
4. If deferred: waits for `target-ready!` mutation

**Timeout Handling**:
- `:delay-timer` fires after ~20ms to show pending UI
- `:error-timer` fires after ~5000ms for failed state
- For testing: can mock timers or manually trigger events

### 1.6 Form State

All form state operations are pure functions on EDN:
- `add-form-config*` - Initialize form tracking
- `dirty-fields` - Get changed fields
- `mark-complete*` - Mark fields as validated
- `entity->pristine*` - Commit changes
- `pristine->entity*` - Revert changes

Works identically in CLJ and CLJS.

---

## 2. Design for CLJ Testing Framework

### 2.1 Core Principles

1. **No CLJS Changes**: All new code in new namespaces or behind `#?(:clj ...)` conditionals
2. **Synchronous Control**: Tests can step through transaction phases explicitly
3. **Render Frame Capture**: Store render history for assertions
4. **Ring Integration**: Remotes can use actual ring handler stack
5. **Pathom Support**: Both sync and async Pathom with blocking

### 2.2 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     CLJ Testing Framework                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  Test App       │    │  Render History │                    │
│  │  Builder        │    │  (in runtime)   │                    │
│  └────────┬────────┘    └────────┬────────┘                    │
│           │                      │                              │
│  ┌────────▼──────────────────────▼────────┐                    │
│  │         CLJ Test Application           │                    │
│  │  - Sync TX Processing                  │                    │
│  │  - Frame-capturing render              │                    │
│  │  - Controllable execution              │                    │
│  └────────┬───────────────────────────────┘                    │
│           │                                                     │
│  ┌────────▼────────┐    ┌─────────────────┐                    │
│  │   CLJ Remote    │    │  Timer Control  │                    │
│  │  (sync/async)   │    │  (mock/advance) │                    │
│  └────────┬────────┘    └─────────────────┘                    │
│           │                                                     │
│  ┌────────▼────────────────────────────────────────────┐       │
│  │            Backend Integration Layer                 │       │
│  │  - Ring handler invocation                          │       │
│  │  - Pathom parser (sync or async via <!! blocking)   │       │
│  │  - Mock response injection                          │       │
│  └─────────────────────────────────────────────────────┘       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 New Namespace: `com.fulcrologic.fulcro.clj-testing`

Main entry point for CLJ testing support.

```clojure
(ns com.fulcrologic.fulcro.clj-testing
  "CLJ-only testing support for Fulcro applications."
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [clojure.core.async :as async]))
```

**Key Functions**:

#### `build-test-app`
```clojure
(defn build-test-app
  "Create a test application configured for synchronous testing.

   Options:
   - :root-class - Root component class (optional, can mount later)
   - :remote - Remote handler function or map
   - :initial-state - Initial state map
   - :render-history-size - Number of frames to keep (default 10)
   - :shared - Static shared props"
  [{:keys [root-class remote initial-state render-history-size shared]
    :or {render-history-size 10}}]
  ...)
```

#### `render-frame!`
```clojure
(defn render-frame!
  "Force a render and capture the frame in history.
   Returns the rendered tree (denormalized props)."
  [app]
  ...)
```

#### `render-history`
```clojure
(defn render-history
  "Get the render history (newest first).
   Each entry: {:state state-map :tree denormalized-tree :timestamp ms}"
  [app]
  ...)
```

#### `last-frame`
```clojure
(defn last-frame
  "Get the most recent render frame."
  [app]
  ...)
```

#### `with-controlled-execution`
```clojure
(defmacro with-controlled-execution
  "Execute body with fine-grained control over transaction phases.

   Yields control object with:
   - :process-optimistic! - Run optimistic actions only
   - :process-remotes! - Execute remote calls (blocking)
   - :render! - Force a render frame
   - :wait-idle! - Block until all work complete"
  [app & body]
  ...)
```

### 2.4 New Namespace: `com.fulcrologic.fulcro.clj-testing.remote`

CLJ remote implementations.

```clojure
(ns com.fulcrologic.fulcro.clj-testing.remote
  "Remote implementations for CLJ testing.")
```

#### `sync-remote`
```clojure
(defn sync-remote
  "Create a synchronous remote that calls handler-fn directly.

   handler-fn: (fn [eql-request] response-body) or async channel

   Options:
   - :simulate-latency-ms - Add artificial delay
   - :transform-request - (fn [request] transformed-request)
   - :transform-response - (fn [response] transformed-response)"
  [handler-fn & {:keys [simulate-latency-ms transform-request transform-response]}]
  {:transmit! (fn [this {:keys [::txn/ast ::txn/result-handler] :as send-node}]
                (let [eql (eql/ast->query ast)
                      result (if (satisfies? async/ReadPort handler-fn)
                               (async/<!! (handler-fn eql))
                               (handler-fn eql))]
                  (result-handler {:status-code 200
                                   :body result})))
   :abort! (fn [_ _] nil)})
```

#### `ring-remote`
```clojure
(defn ring-remote
  "Create a remote that invokes a Ring handler.

   Simulates full HTTP round-trip through Ring middleware stack.

   Options:
   - :uri - API endpoint URI (default \"/api\")
   - :method - HTTP method (default :post)
   - :content-type - Request content type (default \"application/transit+json\")
   - :headers - Additional headers
   - :session - Session data to include"
  [ring-handler & {:keys [uri method content-type headers session]
                   :or {uri "/api" method :post content-type "application/transit+json"}}]
  ...)
```

#### `pathom-remote`
```clojure
(defn pathom-remote
  "Create a remote backed by a Pathom parser.

   parser: Pathom parser function (fn [env tx] result)

   Options:
   - :env-fn - (fn [] env) to create parser environment per request
   - :async? - If true, parser returns channel; blocks via <!!"
  [parser & {:keys [env-fn async?]}]
  ...)
```

### 2.5 New Namespace: `com.fulcrologic.fulcro.clj-testing.timers`

Timer control for testing timeouts.

```clojure
(ns com.fulcrologic.fulcro.clj-testing.timers
  "Timer control for deterministic timeout testing.")
```

#### `with-mock-timers`
```clojure
(defmacro with-mock-timers
  "Execute body with mocked timers.

   Yields timer-control with:
   - :advance! - (fn [ms]) advance time by ms
   - :pending - (fn []) get pending timers
   - :fire-all! - (fn []) fire all pending timers"
  [& body]
  ...)
```

### 2.6 Render Frame Capture

Store render history in runtime-atom:

```clojure
::render-frames     ; Vector of {:state :tree :timestamp}
::render-frame-max  ; Max frames to keep (ring buffer behavior)
```

Custom `optimized-render!` that:
1. Denormalizes state via `fdn/db->tree`
2. Stores frame in history
3. Optionally renders to string (for inspection)

---

## 3. Implementation Plan

### Phase 1: Core Testing Infrastructure

**New File**: `src/main/com/fulcrologic/fulcro/clj_testing.clj`

1. **Test App Builder**
   - Wrap `with-synchronous-transactions`
   - Install frame-capturing render
   - Configure remotes

2. **Render Frame Capture**
   - Custom `optimized-render!` that stores frames
   - Frame history management (ring buffer)
   - State snapshot per frame

3. **Controlled Execution**
   - Break transaction processing into phases
   - Expose phase-specific execution functions
   - Blocking wait for completion

### Phase 2: Remote Implementations

**New File**: `src/main/com/fulcrologic/fulcro/clj_testing/remote.clj`

1. **Synchronous Remote**
   - Direct function call
   - Channel blocking via `<!!`
   - Error simulation

2. **Ring Remote**
   - Full HTTP simulation
   - Transit encoding/decoding
   - Session/auth support

3. **Pathom Remote**
   - Parser invocation
   - Environment injection
   - Async support with blocking

### Phase 3: Timer Control

**New File**: `src/main/com/fulcrologic/fulcro/clj_testing/timers.clj`

1. **Mock Timer System**
   - Intercept `sched/defer`
   - Queue timer events
   - Manual time advancement

2. **Integration with UISM**
   - Timeout event firing
   - Routing timer control

### Phase 4: Test Application

**New Files** in `src/test/com/fulcrologic/fulcro/clj_testing/`:

1. **test_app.clj** - Components with routing, forms, state machines
2. **test_mutations.clj** - Mutations for testing
3. **clj_testing_spec.clj** - Comprehensive tests

**Test Application Features**:
- Multi-level dynamic routing (nested routers)
- Deferred routes with loading states
- Forms with validation
- UI state machines (login flow, wizards)
- Remote data loading

---

## 4. Test Application Design

### 4.1 Component Hierarchy

```
Root
├── Header (static)
├── MainRouter (dynamic router)
│   ├── Dashboard (route target)
│   │   └── DashboardStats (loads data)
│   ├── UserSection (route target + nested router)
│   │   ├── UserList (route target, deferred load)
│   │   ├── UserDetail (route target, deferred load)
│   │   └── UserForm (route target, form with validation)
│   └── Settings (route target)
│       └── SettingsForm (form)
└── LoginDialog (UISM-controlled modal)
```

### 4.2 State Machines

**LoginStateMachine**:
- States: `:idle`, `:entering-credentials`, `:authenticating`, `:authenticated`, `:error`
- Events: `:show`, `:submit`, `:success`, `:failure`, `:logout`
- Tests: Full auth flow, error handling, timeouts

**FormWizardStateMachine**:
- States: `:step1`, `:step2`, `:step3`, `:submitting`, `:complete`
- Events: `:next`, `:back`, `:submit`, `:success`, `:failure`
- Tests: Step navigation, validation per step, submission

### 4.3 Forms

**UserForm**:
- Fields: `:user/name`, `:user/email`, `:user/role`
- Validation: Required fields, email format
- Tests: Dirty detection, validation, submit/reset

**SettingsForm**:
- Fields: `:settings/theme`, `:settings/notifications`
- Nested subform: NotificationPreferences
- Tests: Nested form handling, partial saves

### 4.4 Routes

```
/                       -> Dashboard
/users                  -> UserList (deferred)
/users/:user-id         -> UserDetail (deferred)
/users/:user-id/edit    -> UserForm
/settings               -> Settings
/settings/edit          -> SettingsForm
```

### 4.5 Test Scenarios

1. **Basic Transaction**
   - Submit mutation, verify state change
   - Check render frame captured change

2. **Load with Deferred Route**
   - Navigate to UserList
   - Verify pending state
   - Simulate load complete
   - Verify routed state

3. **Form Submission**
   - Edit form fields
   - Verify dirty detection
   - Submit (optimistic)
   - Verify remote called
   - Verify pristine after success

4. **State Machine Flow**
   - Start login SM
   - Submit credentials
   - Mock auth response
   - Verify authenticated state
   - Test timeout behavior

5. **Nested Routing**
   - Navigate through nested routes
   - Verify all routers update
   - Test back navigation

6. **Error Handling**
   - Remote failure
   - Validation errors
   - Timeout errors

---

## 5. API Summary

### Test App Creation

```clojure
(require '[com.fulcrologic.fulcro.clj-testing :as ct])
(require '[com.fulcrologic.fulcro.clj-testing.remote :as ctr])

;; Simple test app with mock remote
(def app (ct/build-test-app
           {:root-class Root
            :remotes {:remote (ctr/sync-remote mock-handler)}}))

;; With Pathom backend
(def app (ct/build-test-app
           {:root-class Root
            :remotes {:remote (ctr/pathom-remote my-parser :env-fn make-env)}}))

;; With simple Ring handler (EDN encoding)
(def app (ct/build-test-app
           {:root-class Root
            :remotes {:remote (ctr/ring-remote my-ring-handler :uri "/api")}}))

;; With full Fulcro Ring middleware (Transit encoding, like CLJS http-remote)
(def app (ct/build-test-app
           {:root-class Root
            :remotes {:remote (ctr/fulcro-ring-remote my-ring-handler
                                :uri "/api"
                                :session {:user/id 1})}}))
```

### Transaction Execution

```clojure
;; Simple (fully synchronous)
(comp/transact! app [(my-mutation {:x 1})])
;; State is already updated, remote already called

;; Controlled execution
(ct/with-controlled-execution app
  (fn [{:keys [process-optimistic! process-remotes! render!]}]
    ;; Submit transaction (queued)
    (comp/transact! app [(my-mutation {:x 1})] {:synchronous? false})

    ;; Process only optimistic actions
    (process-optimistic!)
    (is (= 1 (:x (ct/current-state app))))

    ;; Capture pre-remote frame
    (render!)

    ;; Process remotes (blocking)
    (process-remotes!)

    ;; Capture post-remote frame
    (render!)

    ;; Compare frames
    (let [[pre post] (ct/render-history app)]
      (is (not= (:tree pre) (:tree post))))))
```

### Render Inspection

```clojure
;; Force render and get tree
(let [tree (ct/render-frame! app)]
  (is (= "Expected Title" (get-in tree [:page :title]))))

;; Get history
(let [frames (ct/render-history app)
      latest (first frames)]
  (is (= 3 (count frames)))
  (is (some? (:timestamp latest))))

;; Get specific element (via query)
(let [user (ct/query-tree app [:user/id 1])]
  (is (= "John" (:user/name user))))
```

### State Machine Testing

```clojure
(require '[com.fulcrologic.fulcro.ui-state-machines :as uism])

;; Start state machine
(uism/begin! app LoginMachine ::login {:actor/form LoginForm} {})

;; Verify initial state
(is (= :idle (uism/get-active-state app ::login)))

;; Trigger event
(uism/trigger! app ::login :show {})
(is (= :entering-credentials (uism/get-active-state app ::login)))

;; With mock timers for timeout testing
(require '[com.fulcrologic.fulcro.clj-testing.timers :as timers])

(timers/with-mock-timers
  (uism/trigger! app ::login :submit {:username "test" :password "wrong"})

  ;; Check that timer was scheduled
  (is (= 1 (timers/pending-timer-count)))

  ;; Advance time to trigger timeout
  (timers/advance-time! 5001)

  (is (= :error (uism/get-active-state app ::login))))
```

### Routing Testing

```clojure
(require '[com.fulcrologic.fulcro.routing.dynamic-routing :as dr])

;; Change route
(let [result (dr/change-route! app ["users"])]
  (is (= :routing result)))

;; For deferred routes - complete the loading
(dr/target-ready! app [:user-list/id :singleton])

;; Verify route
(is (= [:user-list/id :singleton]
       (dr/current-route app Root)))
```

### Form Testing

```clojure
(require '[com.fulcrologic.fulcro.algorithms.form-state :as fs])

;; Initialize form
(swap! (::app/state-atom app)
       fs/add-form-config* UserForm [:user/id 1])

;; Modify field
(swap! (::app/state-atom app)
       assoc-in [:user/id 1 :user/name] "New Name")

;; Check dirty
(let [form-props (ct/get-props app UserForm [:user/id 1])]
  (is (fs/dirty? form-props :user/name))
  (is (not (fs/dirty? form-props :user/email))))

;; Submit form (mutation)
(comp/transact! app [(submit-user-form {:user/id 1})])

;; Verify clean after submit
(let [form-props (ct/get-props app UserForm [:user/id 1])]
  (is (not (fs/dirty? form-props))))
```

---

## 6. Implementation Notes

### 6.1 Leveraging Existing Infrastructure

The key insight is that `synchronous_tx_processing.cljc` already provides most of what we need:
- Synchronous transaction execution
- Thread-safe queue management
- Blocking wait for completion

Our additions layer on top:
- Render frame capture
- Phase-separated execution control
- Testing-friendly remote implementations
- Timer mocking

### 6.2 No Changes to Existing Code

All functionality achieved via:
- Algorithm overrides (`:optimized-render!`, `:tx!`, etc.)
- Remote implementations (map with `:transmit!`)
- New namespaces (CLJ-only)

The only potential change: If we need reader conditionals for optimization, they go in new branches that don't affect CLJS execution.

### 6.3 Thread Safety

- Use atoms for shared state
- Synchronous TX processing already handles thread coordination
- Timer mocking uses thread-local bindings

### 6.4 Performance Considerations

For testing:
- Render history is bounded (ring buffer)
- Remote calls are synchronous (no scheduling overhead)
- State snapshots are references (not deep copies unless needed)

---

## 7. File Structure

```
src/main/com/fulcrologic/fulcro/
├── clj_testing.clj              ; Main testing entry point
├── clj_testing/
│   ├── remote.clj               ; Remote implementations
│   ├── timers.clj               ; Timer control
│   └── internal.clj             ; Internal utilities

src/test/com/fulcrologic/fulcro/
├── clj_testing_spec.clj         ; Tests for testing framework
├── clj_testing/
│   ├── test_app.clj             ; Test application components
│   ├── test_mutations.clj       ; Test mutations
│   ├── test_state_machines.clj  ; Test state machine definitions
│   └── integration_spec.clj     ; Integration tests with full app
```

---

## 8. Dependencies

No new dependencies required. Uses:
- `clojure.core.async` (already in deps)
- `com.fulcrologic.fulcro.*` (internal)

Optional (for ring testing):
- Ring (test dependency)
- Transit (already in deps)

---

## 9. Success Criteria

1. **All tests run synchronously** - No flaky async timing issues
2. **Render frame inspection** - Can assert on before/after render state
3. **Full routing support** - Deferred routes work with controlled timing
4. **Form validation** - All form state operations testable
5. **State machine testing** - Can drive SMs through states, test timeouts
6. **No CLJS impact** - All existing CLJS functionality unchanged
7. **Ring integration** - Can test with actual Ring handlers
8. **Pathom integration** - Can test with actual Pathom parsers

---

## 10. Implementation Progress

### Completed

1. ✅ **Core Testing Infrastructure** (`clj_testing.clj`)
   - `build-test-app` - Creates test apps with sync transaction processing
   - `render-frame!` - Forces render and captures frame
   - `last-frame` / `render-history` - Access render history
   - `current-state` / `get-props` - State inspection utilities
   - `reset-app!` - Reset to initial state
   - `wait-for-idle!` / `has-pending-work?` - Work synchronization

2. ✅ **Remote Implementations** (`clj_testing/remote.clj`)
   - `sync-remote` - Direct function call remote
   - `pathom-remote` - Pathom parser integration
   - `ring-remote` - Simple Ring handler remote
   - `fulcro-ring-remote` - Full middleware pipeline (see below)
   - `mock-remote` - Canned response testing
   - `recording-remote` - Request recording wrapper
   - `failing-remote` - Error simulation
   - `delayed-remote` - Latency simulation

3. ✅ **Timer Control** (`clj_testing/timers.clj`)
   - `with-mock-timers` - Mock timer context
   - `advance-time!` / `set-time!` - Time manipulation
   - `fire-all-timers!` / `clear-timers!` - Timer control
   - `pending-timers` / `pending-timer-count` - Timer inspection

4. ✅ **Comprehensive Tests**
   - 48 tests, 214 assertions
   - Core testing framework tests
   - Remote implementation tests
   - Timer mocking tests
   - Dynamic routing tests with nested routers
   - State machine tests with timeouts
   - Render output verification tests
   - Ring session demo with full authentication flow

### New: `fulcro-ring-remote` - Full Middleware Integration

The `fulcro-ring-remote` provides the same middleware pipeline as the CLJS
`fulcro-http-remote`, but calls a Ring handler directly:

```clojure
(require '[com.fulcrologic.fulcro.clj-testing.remote :as ctr])

;; Basic usage
(def remote (ctr/fulcro-ring-remote my-ring-handler))

;; With custom middleware chains (like CLJS http-remote)
(defn wrap-auth-header [handler token]
  (fn [request]
    (handler (update request :headers assoc "Authorization" (str "Bearer " token)))))

(def remote
  (ctr/fulcro-ring-remote my-ring-handler
    :uri "/api"
    :request-middleware (-> (ctr/wrap-fulcro-request)
                            (wrap-auth-header "my-token"))
    :response-middleware (-> (ctr/wrap-fulcro-response)
                             (my-custom-error-handler))))

;; With session/cookies simulation
(def remote
  (ctr/fulcro-ring-remote my-ring-handler
    :session {:user/id 1 :user/role :admin}
    :cookies {"session-id" {:value "abc123"}}))

;; State is accessible for test verification
(let [state @(:state remote)]
  (is (= 1 (:user/id (:session state)))))
```

**Available Middleware:**

- `wrap-fulcro-request` - CLJ port of CLJS middleware, encodes body as transit+json
- `wrap-fulcro-response` - CLJ port of CLJS middleware, decodes transit+json response

### Ring Session Support

The `fulcro-ring-remote` fully supports Ring's session middleware (`wrap-session`).
Session cookies are automatically:

1. **Parsed from Set-Cookie headers** in responses
2. **Stored in the remote's state** for subsequent requests
3. **Sent as Cookie headers** in subsequent requests

This enables testing of authentication flows, session-based state, and other
session-dependent features with full Ring middleware integration.

**Example: Testing Login/Logout Flow with Ring Sessions**

```clojure
(require '[ring.middleware.session :refer [wrap-session]]
         '[ring.middleware.session.memory :refer [memory-store]])

;; Create Ring app with session middleware
(def ring-app
  (-> my-api-handler
      (wrap-session {:store (memory-store)
                     :cookie-name "ring-session"})))

;; Create remote - sessions persist automatically via cookies
(def remote (ctr/fulcro-ring-remote ring-app :uri "/api"))
(def app (ct/build-test-app {:root-class Root :remotes {:remote remote}}))

;; Login - server sets session, cookie is captured automatically
(comp/transact! app [(login {:username "admin" :password "secret"})])

;; Subsequent requests include the session cookie
(comp/transact! app [(get-current-user)])
;; => Session is preserved, user is retrieved

;; Check that cookies are being tracked
(let [state @(:state remote)]
  (println "Session cookie:" (:cookies state)))
```

**Session Isolation Between Tests**

Each `fulcro-ring-remote` instance maintains its own cookie state, so different
test apps have isolated sessions:

```clojure
;; Two different sessions (isolated)
(def admin-remote (ctr/fulcro-ring-remote ring-app :uri "/api"))
(def guest-remote (ctr/fulcro-ring-remote ring-app :uri "/api"))

(def admin-app (ct/build-test-app {:remotes {:remote admin-remote}}))
(def guest-app (ct/build-test-app {:remotes {:remote guest-remote}}))

;; Admin logs in - only affects admin-app's session
(comp/transact! admin-app [(login {:username "admin" :password "secret"})])

;; Guest is still not logged in (different session)
(comp/transact! guest-app [(protected-action)])
;; => Fails with "Unauthorized"
```

See `com.fulcrologic.fulcro.clj-testing.ring-session-demo` for a complete
working example with tests.

### Bug Fixes

- Fixed `reset!` used on `volatile!` in `scheduling.cljc:29` (changed to `vreset!`)
- Fixed terminal states in state machines needing `::uism/events {}` for spec compliance

---

## 11. Hiccup DOM Rendering (`dom-hiccup`)

### 11.1 Motivation

The current frame capture in `clj_testing.clj` only captures the denormalized data tree,
not the actual rendered DOM structure. This means:

- Can't inspect what elements would be rendered
- Can't find elements by ID to test click handlers
- Can't verify that onClick handlers are wired correctly
- Can't simulate user interactions like clicking buttons

We need a `dom-hiccup` namespace that:
1. Renders Fulcro components to hiccup data structures
2. Preserves real lambdas/functions on elements (not stringify them like `dom-server`)
3. Enables helpers like `find-element-by-id` and `click-on!`

### 11.2 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      dom-hiccup.clj                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐    ┌─────────────────┐                    │
│  │  Tag Functions  │    │  Render Engine  │                    │
│  │  (div, span..)  │───▶│  (to-hiccup)    │                    │
│  └─────────────────┘    └────────┬────────┘                    │
│                                  │                              │
│                         ┌────────▼────────┐                    │
│                         │  Hiccup Output  │                    │
│                         │  [:div {:id ..  │                    │
│                         │    :onClick fn} │                    │
│                         │    children...] │                    │
│                         └─────────────────┘                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                   Hiccup Helpers (in clj_testing.clj)           │
├─────────────────────────────────────────────────────────────────┤
│  find-element-by-id   - Find element in hiccup tree by :id     │
│  find-elements-by-*   - Find elements by class, tag, etc.      │
│  click-on!            - Find element by ID and invoke onClick  │
│  element-text         - Extract text content from element      │
│  element-attr         - Get attribute from element             │
└─────────────────────────────────────────────────────────────────┘
```

### 11.3 API Design

#### `dom-hiccup.clj` - Tag Functions

```clojure
(ns com.fulcrologic.fulcro.dom-hiccup
  "Server-side DOM rendering to hiccup data structures.

   Unlike dom-server which renders to HTML strings, this namespace
   produces hiccup vectors with real function values preserved.
   This enables testing of event handlers and DOM inspection.")

;; Each tag function returns hiccup
(defn div [& args]
  ;; Returns: [:div {:id "x" :onClick #function} child1 child2 ...]
  )

;; Component rendering
(defn render-to-hiccup [element]
  "Render a Fulcro component/element to hiccup.
   Returns nested vectors with preserved function values.")
```

#### Hiccup Helpers (in `clj_testing.clj`)

```clojure
;; Find element by ID
(find-element-by-id hiccup "my-button")
;; => [:button {:id "my-button" :onClick #fn} "Click me"]

;; Find all elements matching predicate
(find-elements hiccup (fn [[tag attrs]] (= tag :button)))
;; => [[:button {...}] [:button {...}]]

;; Click on an element (invoke its onClick)
(click-on! app "my-button")
;; Finds element, extracts onClick, invokes it

;; Get text content
(element-text [:div {} "Hello " [:span {} "World"]])
;; => "Hello World"

;; Get attribute
(element-attr [:input {:type "text" :value "foo"}] :value)
;; => "foo"
```

### 11.4 Frame Capture Enhancement

The frame capture will be enhanced to include hiccup:

```clojure
{:state     state-map           ; The normalized state
 :tree      denormalized-tree   ; The props tree
 :hiccup    rendered-hiccup     ; NEW: The hiccup DOM structure
 :timestamp (System/currentTimeMillis)}
```

### 11.5 Implementation Status: COMPLETE

1. **Create `dom_hiccup.clj`** ✅
   - `HiccupElement`, `HiccupText`, `HiccupFragment` records with `IHiccupElement` protocol
   - All HTML/SVG tag functions (div, span, button, etc.) generated via macros
   - Full support for CSS shorthand (`:.class#id`), `:classes` attribute
   - `render-to-hiccup` for component rendering
   - Hiccup tree helpers: `find-element-by-id`, `find-elements`, `find-elements-by-tag`,
     `find-elements-by-class`, `element-text`, `element-attr`, `hiccup-attrs`, `hiccup-children`

2. **Add Hiccup Helpers to `clj_testing.clj`** ✅
   - `last-hiccup` / `hiccup-at-render` - Access hiccup from frames
   - `find-in-hiccup` / `find-all-in-hiccup` - Find elements in rendered hiccup
   - `find-by-tag` / `find-by-class` - Convenience finders
   - `click-on!` - Locate and invoke onClick handler
   - `invoke-handler!` - Generic handler invocation
   - `type-into!` - Simulate typing (invokes onChange)
   - `submit-form!` - Simulate form submission (invokes onSubmit)
   - `assert-element-exists` / `assert-element-text` / `assert-element-attr` - Test assertions

3. **Update Frame Capture** ✅
   - Frames now include `:hiccup` key with rendered DOM structure
   - Real function handlers preserved (not stringified)

4. **Tests** ✅
   - 8 test specifications with 75 assertions
   - Basic tag function tests
   - CSS shorthand and :classes tests
   - Component rendering tests
   - click-on!, type-into!, submit-form! integration tests
   - Assertion helper tests

---

## 12. Remaining Work

1. **Form State Testing** - Add form validation test examples
2. **Documentation Generation** - Generate API docs from docstrings
3. **Performance Testing** - Benchmark sync vs async performance
