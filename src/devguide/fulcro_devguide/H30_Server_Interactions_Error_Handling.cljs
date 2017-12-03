(ns fulcro-devguide.H30-Server-Interactions-Error-Handling
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer [defui defsc]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro-devguide.A-Quick-Tour :as qt]
            [fulcro.client :as fc]
            [fulcro.client.mutations :as m :refer [defmutation]]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.network :as fcn]
            [fulcro.client.logging :as log]))

(defmulti server-read prim/dispatch)
(defmulti server-mutate prim/dispatch)
(def parser (prim/parser {:read server-read :mutate server-mutate}))
(defmethod server-read :default [env k p] (log/info "Unknown server read of " k))
(defmethod server-mutate :default [env k p] (log/info "Unknown server mutate of " k))

(defmethod server-mutate `submit-form [env k params]
  {:action (fn []
             (if (> 0.5 (rand))
               {:message "Everything went swell!"
                :result  0}
               {:message "There was an error!"
                :result  1}))})

;; from fulcro.server namespace...this is how a real fulcro server behaves, it raises the results for you from mutations
(defn raise-response
  "For om mutations, converts {'my/mutation {:result {...}}} to {'my/mutation {...}}"
  [resp]
  (reduce (fn [acc [k v]]
            (if (and (symbol? k) (not (nil? (:result v))))
              (assoc acc k (:result v))
              (assoc acc k v)))
    {} resp))

(defrecord MockNetwork []
  fcn/FulcroNetwork
  (send [this edn ok err]
    (let [resp (raise-response (parser {} edn))]
      ; simulates a network delay:
      (js/setTimeout (fn []
                       (log/info "Sending response to client")
                       (ok resp)) 2000)))
  (start [this] this))

(defsc BlockingOverlay [this {:keys [ui/active? ui/message]} _ _]
  {:query         [:ui/active? :ui/message]
   :initial-state {:ui/active? false :ui/message "Please wait..."}}
  (dom/div (clj->js {:style {:position        :absolute
                             :display         (if active? "block" "none")
                             :zIndex          65000
                             :width           "400px"
                             :height          "100px"
                             :backgroundColor "rgba(0,0,0,0.5)"}})
    (dom/div (clj->js {:style {:position  :relative
                               :top       "40px"
                               :color     "white"
                               :textAlign "center"}}) message)))

(def ui-overlay (prim/factory BlockingOverlay))

(defn set-overlay-visible* [state tf] (assoc-in state [:overlay :ui/active?] tf))
(defn set-overlay-message* [state message] (assoc-in state [:overlay :ui/message] message))

(defui MutationStatus
  static prim/Ident
  (ident [t p] [:remote-mutation :status])
  static prim/IQuery
  (query [this] [:message :result]))

(defmutation submit-form [params]
  (action [{:keys [state]}] (swap! state set-overlay-visible* true))
  (remote [{:keys [state ast] :as env}]
    (m/returning ast state MutationStatus)))

(defn submit-ok? [env] (= 0 (some-> env :state deref :remote-mutation :status :result)))

(defmutation retry-or-hide-overlay [params]
  (action [{:keys [reconciler state] :as env}]
    (if (submit-ok? env)
      (swap! state (fn [s]
                     (-> s
                       (set-overlay-message* "Please wait...") ; reset the overlay message for the next appearance
                       (set-overlay-visible* false))))
      (do
        (swap! state set-overlay-message* (str (-> state deref :remote-mutation :status :message) " (Retrying...)"))
        (prim/ptransact! reconciler `[(submit-form {}) (retry-or-hide-overlay {})])))))

(defsc Root [this {:keys [ui/name ui/react-key overlay]} _ _]
  {:query         [:ui/react-key :ui/name {:overlay (prim/get-query BlockingOverlay)}]
   :initial-state {:overlay {} :ui/name "Alicia"}}
  (dom/div #js {:key react-key :style (clj->js {:width "400px" :height "100px"})}
    (ui-overlay overlay)
    (dom/p nil "Name: " (dom/input #js {:value name}))
    (dom/button #js {:onClick #(prim/ptransact! this `[(submit-form) (retry-or-hide-overlay)])}
      "Submit")))



(defcard-doc
  "
  # Full-Stack Error Handling

  First, let's talk about where we're coming from. In the world of mutable state, asynchrony, and plain JavaScript
  we've had to adapt to deal with errors in a pretty specific way. Essentially, we make our network call and hook
  up the error as a callback right where it matters. In other words, the context of the error handling is
  available at the same time as our request to do the operation.

  In order to make things \"make sense\", we often block the UI so that the user cannot get ahead of things (like
  submit a form and move on before the server has confirmed the submission).

  Over the years we've gotten a little more clever with our error handling, but largely our users (and our ability
  to reason about our programs) has kept us firmly rooted to the block-until-we-know method of error handling.

  So, let's talk about how to do that first, and then talk about how we can do better.

  ## Old-school UI Blocking

  Fulcro defaults to optimistic updates, which in turn encourages you to write a UI that is very responsive. However,
  as soon as you start writing remote mutations you start worrying about the fact that your user submitted some data
  and Fulcro encourages you to let them go off and do other things (like leave the screen they're on) before the server has responded.
  In effect, we've told the user \"success\", but we know we're kind of lying to them.

  Another way of looking at it is: we're letting them leave the *visual context* of the information, but we know that if a
  server error happens then we need to inform them about that error. We'd like to be sure they understand the error
  by still seeing that context when it arrives.

  This is a rather complicated way of saying \"if their email change didn't work, then we'd like to show the error next to the
  email input box\".

  There is nothing in Fulcro that prevents you from writing a blocking UI. You just have to remember that the UI is a
  pure rendering of application state: meaning that if you want to block the UI, then you need a way to put a block-the-ui
  marker in state (that renders in a way that prevents navigation), and remove that marker when the operation is complete.

  Fulcro has a number of ways that you can accomplish this, but we'll cover the simplest and most obvious.

  ### Blocking on Remote Mutations

  This technique uses the following pattern:

  1. We use the `prim/ptransact!` to submit a transaction, which will run each mutation in pessimistic mode (each element
  runs only after the prior element has completed a round-trip to the server).
  2. The first call in the tx will block the UI, and do the remote operation. We'll also leverage mutation return values
  so the server can indicate success to us.
  3. Once the first call finishes, the second call in the tx can choose to unblock the UI, or handle any problem it
  sees. The mutation return value is merged (and visible) in app state.

  Unlike normal mode, pessimistic transactions expect that you might have to nest another one within a mutation in order to retry a
  prior call. This is a supported use, and you will find the reconciler in the mutation's `env` parameter to facilitate it as
  shown in the example below.

  To show how this all works we'll use an in-browser server emulation and show you a working example.

  First, we need something to block our UI (which in the card measures 400x100 px). It is a simple div with some style
  that will overlay the main UI and prevent further interactions while also showing some kind of feedback message.

  We define it, along with some helper functions that can manipulate its state. It does not have an ident, and we
  plan to just place it in root at `:overlay`:

  "
  (dc/mkdn-pprint-source set-overlay-visible*)
  (dc/mkdn-pprint-source set-overlay-message*)
  (dc/mkdn-pprint-source BlockingOverlay)
  "The main UI is just a simple one-field form and submission button. Note, however, that it submits the form
  with `ptransact!`, which will force each call to complete before the next one can start. Thus the second call can
  check the result and run whatever in response to it.
  "
  (dc/mkdn-pprint-source Root)
  "Now a bit of information about our \"server\". It has the following definition of the remote mutation:

  ```
  (defmethod server-mutate `submit-form [env k params]
    {:action (fn []
               (if (> 0.5 (rand))
                 {:message \"Everything went swell!\"
                  :result  0}
                 {:message \"There was an error!\"
                  :result  1}))})
  ```

  As you can see it's just a stub that randomly responds with success or error. The client mutation looks like this:

  ```
  (defmutation submit-form [params]
    (action [{:keys [state]}] (swap! state set-overlay-visible* true))
    (remote [{:keys [state ast] :as env}]
      (m/returning ast state MutationStatus)))
  ```

  It just shows the overlay, and goes remote. Notice the remote part is  using `returning` from the mutations namespace
  to indicate a merge of the result value of the mutation. For that we've defined a singleton component (for its query only):
  "
  (dc/mkdn-pprint-source MutationStatus)
  "This means that when this remote mutation is done, we should see a the return value of the server mutation
  at `[:remote-mutation :status]` (the ident (table/id) of `MutationStatus`).

  Now for our second client mutation:

  "
  (dc/mkdn-pprint-source submit-ok?)
  "

  ```
  (defmutation retry-or-hide-overlay [params]
    (action [{:keys [reconciler state] :as env}]
      (if (submit-ok? env)
        (swap! state (fn [s]
                       (-> s
                         (set-overlay-message* \"Please wait...\") ; reset the overlay message for the next appearance
                         (set-overlay-visible* false))))
        (do
          (swap! state set-overlay-message* (str (-> state deref :remote-mutation :status :message) \" (Retrying...)\"))
          (prim/ptransact! reconciler `[(submit-form {}) (retry-or-hide-overlay {})])))))
  ```

  It's the real work-horse. The optimistic side can assume the result is updated, so it looks for the result code via
  `submit-ok?`. If things are OK, then it resets the overlay message and hides it.

  If the submission had an error, then it

  - Adds \"retrying\" to the server message and puts that on the overlay
  - Does a new call to `ptransact!`.

  You can try out the finished product in the card below. Try it a few times so you can see the error-handling in action.
  ")

(defcard-fulcro pessimistic-ui-card
  Root
  {}
  {:inspect-data true
   :fulcro       {:networking (map->MockNetwork {})}})

(defcard-doc
  "







  If you're running a mutation that has likely server errors, then you can explicitly encode a fallback with the mutation.
  Fallbacks are triggered *if and only if* the mutation on the server throws an error that is detectable as a non-network
  error.

  Fallbacks are about scenarios where you'd like the option of showing the user an error if the server can be reasonably
  expected to issue such an error.

  ```
  (prim/transact! this `[(some/mutation) (fulcro.client.data-fetch/fallback {:action handle-failure})])
  ```

  ```
  (require [fulcro.client.mutations :refer [mutate]])

  (defmethod mutate 'some/mutation [{:keys [state] :as env} k params]
    {:remote true
     :action (fn [] (swap! state do-stuff)})

  (defmethod mutate 'handle-failure [{:keys [state] :as env} k {:keys [error] :as params}]
    ;; fallback mutations are designed to recover the client-side app state from server failures
    ;; so, no need to send to the server
    {:action (fn [] (swap! state undo-stuff error)))
  ```

  Assuming that `some/mutation` returns `{:remote true}` (or `{:remote AST}`)  this sends `some/mutation` to the server.
  If the server throws an error then the fallback action's mutation symbol (a dispatch key for mutate) is invoked on the
  client with params that include an `:error` key that includes the details of the server exception (error type, message,
  and ex-info's data). Be sure to only include serializable data in the server exception!

  You can have any number of fallbacks in a tx, and they will run in order if the transaction fails.

  As a result it is not recommended that you rely on fallbacks for very much. They are provided for cases where you'd
  like to code instance-targeted recovery, but we believe this to be a rarely useful feature.

  You're much better off preventing errors by coding your UI to validate,
  authorize, and error check things on the client before sending them to the server. The server should still verify
  sanity for security reasons, but optimistic systems like Fulcro put more burden on the client code in order to
  provide a better experience under normal operation.

  In general it can be difficult to recover from real hard failures, and this is true for any application. The difference
  for optimistic systems is that the user can be several steps ahead in the UI of the operation that is failing.
  Fortunately, the overall user experience is better for the happy cases (which hopefully are 99.9999% of them), but
  it is true that if you're user is 10 steps ahead of the server and the server barfs, your easiest route to recovery
  is to throw up an error dialog and reload all questionable state.

  You probably also need to clear the network queue so that additional queued operations don't continue to fail.

  #### Clearing Network Queue

  If the server sends back a failure it may be desirable to clear any pending network requests from the client
  network queue. For example, if you're adding an item to a list and get a server error you might have a mutation waiting
  in your network queue that was some kind of modification to that (now failed) item. Continuing the network processing
  might just cause more errors.

  The FulcroApplication protocol (implemented by your client app) includes the protocol method
  `clear-pending-remote-requests!` which will drain all pending network requests.

  ```
  (fulcro.client/clear-pending-remote-requests! my-app)
  ```

  A common recovery strategy from errors could be to clean the network queue and run a mutation that resets your application
  to a known state, possibly loading sane state from the server.



  #### Technique 1: Read status via a separate load

  The steps are rather simple:

  1. During the optimistic update of the full-stack mutation: make your local change, and include a state change that
  will prevent UI progress (e.g. a property like `:ui/disabled?` that disables next/close/progress buttons, or causes
  an overlay div to pop up that blocks all events from the DOM).
  2. Issue a load (from within the mutation is fine) and include a post-mutation that examines state for errors and unblocks the UI.

  Fulcro guarantees that loads will be issued after mutations. So, use the load to read some kind of confirmation. The load
  itself doesn't even have to read anything useful (in fact you could make a custom networking handler that doesn't even
  issue it over the network).



  ## A New Approach to Errors

  The first thing I want to challenge you to think about is this: why do errors happen, and what can we do about them?

  In the early days of web apps, our UI was completely dumb: the server did all of the logic. The answer to these questions
  were clear, because it wasn't even a distributed app...it was a *remote display* of an app running on a remote machine.

  As more and more code has moved back to the UI, more and more of the real logic lives on the client machine.

  Unfortunately, we still have security concerns at the server, so we get confused by the following fact: the server *has* to be able to
  validate a request for security reasons. There is no getting around this. You cannot trust a client.

  However, I think many of us take this too far: security concerns are often a lot easier to enforce than the full client-level
  interaction with these concerns. For example, we can say on a server that a field must be a number. This is one line of
  code, that can be done with an assertion.

  The UI logic for this is much larger: we have to tell the user what we expected, why we expected it, constrain the UI
  to keep them from typing letters, etc. In other words, almost all of the real logic is already on the client, and unless
  there is a bug, *our* UI *won't* cause a server error, because it is pre-checking everything before sending it out.

  So, in a modern UI, here are the scenarios for errors *from the server*:

  1. You have a bug. Is there anything you can really do? No, because it is a bug. If you could predict it going wrong, you would
  have already fixed it. Testing and user bug reports are your only recourse.
  2. There is a security violation. There is nothing for your UI to do, because your UI didn't do it! This is an attack.
  Throw an exception on the server, and never expect it in the UI. If you get it, it is a bug. See (1).
  3. There is a user perspective outage (LAN/WiFi/phone). These are possibly recoverable. You can block the UI, and allow the
  user to continue once networking is re-established.
  4. There is an infrastructure outage. You're screwed. Things are just down. If you're lucky, it is networking and your
  user is just blocked. If you're not lucky, your database crashed and you have no idea if your data is even consistent.

  So, I would assert that the only full-stack error handling *worth* doing in *any detail* is for case (3). If communications
  are down, you can retry. But in a distributed system this can be a little nuanced.

  The good news is that Fulcro has a story that we believe is, as of 2017, a novel and quite compelling error handling story!

  ## What's Next?

  Now that you know what to do about error handling, you might also be interested in
  [remotes as an abstraction](#!/fulcro_devguide.H40_Server_Interactions_As_An_Abstraction).

  ")
