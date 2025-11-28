(ns com.fulcrologic.fulcro.headless.loopback-remotes-spec
  "Tests for the headless loopback remotes namespace.

   This namespace tests Ring session handling with Fulcro headless loopback remotes:
   1. Create a Ring handler with session middleware
   2. Use fulcro-ring-remote to interact with it
   3. Verify that session state persists across requests

   The tests simulate a login/logout flow where:
   - Login stores user info in session
   - Subsequent requests see the logged-in user
   - Logout clears the session"
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as transit]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.headless :as ct]
    [com.fulcrologic.fulcro.headless.loopback-remotes :as ctr]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [fulcro-spec.core :refer [assertions behavior component specification]]
    [ring.middleware.session :refer [wrap-session]]
    [ring.middleware.session.memory :refer [memory-store]]))

(declare =>)

;; =============================================================================
;; Session-Aware Ring Handler
;; =============================================================================

(defn make-api-handler
  "Create an API handler that processes Fulcro requests.
   Uses the session to track logged-in user."
  []
  (fn [{:keys [body session]}]
    (let [;; Parse transit request body
          body-str        (if (string? body) body (slurp body))
          eql             (transit/transit-str->clj body-str)
          ;; Extract the mutation/query
          ;; Note: Fulcro sends mutations as LazySeq, so use seq? not list?
          request         (first eql)
          mutation-sym    (when (seq? request) (first request))
          mutation-params (when (seq? request) (second request))]
      (case mutation-sym
        ;; Login mutation - stores user in session
        com.fulcrologic.fulcro.headless.loopback-remotes-spec/login
        (let [{:keys [username password]} mutation-params]
          (if (and (= username "admin") (= password "secret"))
            {:status  200
             :headers {"Content-Type" "application/transit+json"}
             :session (assoc session :user {:user/id 1 :user/name "Admin User" :user/role :admin})
             :body    (transit/transit-clj->str
                        {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/login
                         {:success true
                          :user    {:user/id 1 :user/name "Admin User" :user/role :admin}}})}
            {:status  200
             :headers {"Content-Type" "application/transit+json"}
             :session session
             :body    (transit/transit-clj->str
                        {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/login
                         {:success false
                          :error   "Invalid credentials"}})}))

        ;; Logout mutation - clears user from session
        com.fulcrologic.fulcro.headless.loopback-remotes-spec/logout
        {:status  200
         :headers {"Content-Type" "application/transit+json"}
         :session nil                                       ; Clear the session
         :body    (transit/transit-clj->str
                    {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/logout
                     {:success true}})}

        ;; Get current user - returns user from session
        com.fulcrologic.fulcro.headless.loopback-remotes-spec/get-current-user
        {:status  200
         :headers {"Content-Type" "application/transit+json"}
         :session session
         :body    (transit/transit-clj->str
                    {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/get-current-user
                     {:user (:user session)}})}

        ;; Protected action - only works if logged in
        com.fulcrologic.fulcro.headless.loopback-remotes-spec/protected-action
        (if-let [user (:user session)]
          {:status  200
           :headers {"Content-Type" "application/transit+json"}
           :session session
           :body    (transit/transit-clj->str
                      {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/protected-action
                       {:success true
                        :message (str "Hello, " (:user/name user) "!")
                        :data    {:action-result 42}}})}
          {:status  200
           :headers {"Content-Type" "application/transit+json"}
           :session session
           :body    (transit/transit-clj->str
                      {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/protected-action
                       {:success false
                        :error   "Unauthorized - must be logged in"}})})

        ;; Increment counter in session (stateful session example)
        com.fulcrologic.fulcro.headless.loopback-remotes-spec/increment-counter
        (let [new-count (inc (get session :counter 0))]
          {:status  200
           :headers {"Content-Type" "application/transit+json"}
           :session (assoc session :counter new-count)
           :body    (transit/transit-clj->str
                      {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/increment-counter
                       {:counter new-count}})})

        ;; Default - unknown mutation
        {:status  200
         :headers {"Content-Type" "application/transit+json"}
         :session session
         :body    (transit/transit-clj->str {})}))))

(defn make-ring-app
  "Create a Ring application with session middleware.
   Uses memory store for predictable testing behavior."
  []
  (let [session-store (memory-store)]
    (-> (make-api-handler)
      (wrap-session {:store        session-store
                     :cookie-name  "ring-session"
                     :cookie-attrs {:http-only true}}))))

;; =============================================================================
;; Fulcro Components and Mutations
;; =============================================================================

(defsc User [_ {:user/keys [id name role]}]
  {:query [:user/id :user/name :user/role]
   :ident :user/id}
  nil)

(defsc SessionRoot [_ {:session/keys [current-user counter logged-in? last-error]}]
  {:query         [:session/current-user :session/counter :session/logged-in? :session/last-error]
   :initial-state {:session/current-user nil
                   :session/counter      0
                   :session/logged-in?   false
                   :session/last-error   nil}}
  nil)

(defmutation login [{:keys [username password]}]
  (action [{:keys [state]}]
    ;; Optimistic: set logging-in state
    (swap! state assoc :session/logging-in? true :session/last-error nil))
  (remote [_] true)
  (ok-action [{:keys [state result]}]
    (let [body         (:body result)
          login-result (get body 'com.fulcrologic.fulcro.headless.loopback-remotes-spec/login)]
      (if (:success login-result)
        (swap! state merge
          {:session/current-user (:user login-result)
           :session/logged-in?   true
           :session/logging-in?  false})
        (swap! state merge
          {:session/last-error  (:error login-result)
           :session/logging-in? false}))))
  (error-action [{:keys [state]}]
    (swap! state merge
      {:session/last-error  "Network error"
       :session/logging-in? false})))

(defmutation logout [_]
  (action [{:keys [state]}]
    ;; Optimistic: clear user immediately
    (swap! state merge
      {:session/current-user nil
       :session/logged-in?   false}))
  (remote [_] true))

(defmutation get-current-user [_]
  (remote [_] true)
  (ok-action [{:keys [state result]}]
    (let [body        (:body result)
          user-result (get body 'com.fulcrologic.fulcro.headless.loopback-remotes-spec/get-current-user)]
      (when-let [user (:user user-result)]
        (swap! state merge
          {:session/current-user user
           :session/logged-in?   true})))))

(defmutation protected-action [_]
  (remote [_] true)
  (ok-action [{:keys [state result]}]
    (let [body          (:body result)
          action-result (get body 'com.fulcrologic.fulcro.headless.loopback-remotes-spec/protected-action)]
      (if (:success action-result)
        (swap! state assoc :session/last-action-result (:data action-result))
        (swap! state assoc :session/last-error (:error action-result))))))

(defmutation increment-counter [_]
  (action [{:keys [state]}]
    ;; Optimistic increment
    (swap! state update :session/counter (fnil inc 0)))
  (remote [_] true)
  (ok-action [{:keys [state result]}]
    (let [body           (:body result)
          counter-result (get body 'com.fulcrologic.fulcro.headless.loopback-remotes-spec/increment-counter)]
      ;; Sync with server counter
      (swap! state assoc :session/counter (:counter counter-result)))))

;; =============================================================================
;; Tests
;; =============================================================================

(specification "Ring session demo"
  (component "Login flow with memory session store"
    (let [ring-app (make-ring-app)
          remote   (ctr/fulcro-ring-remote ring-app :uri "/api")
          app      (ct/build-test-app {:root-class SessionRoot
                                       :remotes    {:remote remote}})]
      (behavior "initial state is logged out"
        (assertions
          "not logged in"
          (:session/logged-in? (rapp/current-state app)) => false
          "no current user"
          (:session/current-user (rapp/current-state app)) => nil))

      (behavior "login with valid credentials succeeds"
        (comp/transact! app [(login {:username "admin" :password "secret"})])
        (assertions
          "is now logged in"
          (:session/logged-in? (rapp/current-state app)) => true
          "current user is set"
          (get-in (rapp/current-state app) [:session/current-user :user/name]) => "Admin User"
          "no error"
          (:session/last-error (rapp/current-state app)) => nil))

      (behavior "session persists across requests"
        ;; Make another request - session should still have the user
        (comp/transact! app [(get-current-user)])
        (assertions
          "user still present from session"
          (get-in (rapp/current-state app) [:session/current-user :user/name]) => "Admin User"
          "still logged in"
          (:session/logged-in? (rapp/current-state app)) => true))

      (behavior "protected action works when logged in"
        (comp/transact! app [(protected-action)])
        (assertions
          "action succeeded"
          (get-in (rapp/current-state app) [:session/last-action-result :action-result]) => 42))

      (behavior "logout clears session"
        (comp/transact! app [(logout)])
        (assertions
          "no longer logged in"
          (:session/logged-in? (rapp/current-state app)) => false
          "current user cleared"
          (:session/current-user (rapp/current-state app)) => nil))

      (behavior "protected action fails after logout"
        (comp/transact! app [(protected-action)])
        (assertions
          "action failed with unauthorized error"
          (:session/last-error (rapp/current-state app)) => "Unauthorized - must be logged in"))))

  (component "Login with invalid credentials"
    (let [ring-app (make-ring-app)
          remote   (ctr/fulcro-ring-remote ring-app :uri "/api")
          app      (ct/build-test-app {:root-class SessionRoot
                                       :remotes    {:remote remote}})]
      (behavior "login fails with wrong password"
        (comp/transact! app [(login {:username "admin" :password "wrong"})])
        (assertions
          "not logged in"
          (:session/logged-in? (rapp/current-state app)) => false
          "error message set"
          (:session/last-error (rapp/current-state app)) => "Invalid credentials"))))

  (component "Session counter (stateful session)"
    (let [ring-app (make-ring-app)
          remote   (ctr/fulcro-ring-remote ring-app :uri "/api")
          app      (ct/build-test-app {:root-class SessionRoot
                                       :remotes    {:remote remote}})]
      (behavior "counter increments across requests"
        (comp/transact! app [(increment-counter)])
        (assertions
          "counter is 1 after first increment"
          (:session/counter (rapp/current-state app)) => 1)

        (comp/transact! app [(increment-counter)])
        (assertions
          "counter is 2 after second increment"
          (:session/counter (rapp/current-state app)) => 2)

        (comp/transact! app [(increment-counter)])
        (comp/transact! app [(increment-counter)])
        (comp/transact! app [(increment-counter)])
        (assertions
          "counter is 5 after five total increments"
          (:session/counter (rapp/current-state app)) => 5))))

  (component "Session isolation between apps"
    (let [ring-app (make-ring-app)
          remote1  (ctr/fulcro-ring-remote ring-app :uri "/api")
          remote2  (ctr/fulcro-ring-remote ring-app :uri "/api")
          app1     (ct/build-test-app {:root-class SessionRoot :remotes {:remote remote1}})
          app2     (ct/build-test-app {:root-class SessionRoot :remotes {:remote remote2}})]
      (behavior "sessions are isolated between different remotes"
        ;; Login on app1
        (comp/transact! app1 [(login {:username "admin" :password "secret"})])
        (assertions
          "app1 is logged in"
          (:session/logged-in? (rapp/current-state app1)) => true)

        ;; app2 should not be logged in (different session)
        (comp/transact! app2 [(get-current-user)])
        (assertions
          "app2 is not logged in"
          (:session/logged-in? (rapp/current-state app2)) => false))))

  (component "Cookie-based session verification"
    (let [ring-app (make-ring-app)
          remote   (ctr/fulcro-ring-remote ring-app :uri "/api")
          app      (ct/build-test-app {:root-class SessionRoot
                                       :remotes    {:remote remote}})]
      (behavior "remote state contains session cookie after login"
        (comp/transact! app [(login {:username "admin" :password "secret"})])
        ;; The remote should have a session cookie stored
        (let [state @(:state remote)]
          (assertions
            "cookies are stored"
            (some? (:cookies state)) => true)))))

  (component "Multiple users scenario"
    (let [ring-app     (make-ring-app)
          admin-remote (ctr/fulcro-ring-remote ring-app :uri "/api")
          guest-remote (ctr/fulcro-ring-remote ring-app :uri "/api")
          admin-app    (ct/build-test-app {:root-class SessionRoot :remotes {:remote admin-remote}})
          guest-app    (ct/build-test-app {:root-class SessionRoot :remotes {:remote guest-remote}})]
      (behavior "admin can access protected resources, guest cannot"
        ;; Admin logs in
        (comp/transact! admin-app [(login {:username "admin" :password "secret"})])

        ;; Admin accesses protected resource
        (comp/transact! admin-app [(protected-action)])
        (assertions
          "admin got the protected data"
          (get-in (rapp/current-state admin-app) [:session/last-action-result :action-result]) => 42)

        ;; Guest tries to access protected resource (not logged in)
        (comp/transact! guest-app [(protected-action)])
        (assertions
          "guest got unauthorized error"
          (:session/last-error (rapp/current-state guest-app)) => "Unauthorized - must be logged in")))))

;; =============================================================================
;; Additional test for direct session option
;; =============================================================================

(defn make-simple-api-handler
  "Create a simpler API handler that reads session directly from request.
   This handler does NOT use Ring's wrap-session middleware, making it
   suitable for testing the :session option of fulcro-ring-remote."
  []
  (fn [{:keys [body session]}]
    (let [body-str     (if (string? body) body (slurp body))
          eql          (transit/transit-str->clj body-str)
          request      (first eql)
          mutation-sym (when (seq? request) (first request))]
      (case mutation-sym
        com.fulcrologic.fulcro.headless.loopback-remotes-spec/get-current-user
        {:status  200
         :headers {"Content-Type" "application/transit+json"}
         :body    (transit/transit-clj->str
                    {'com.fulcrologic.fulcro.headless.loopback-remotes-spec/get-current-user
                     {:user (:user session)}})}
        ;; Default
        {:status  200
         :headers {"Content-Type" "application/transit+json"}
         :body    (transit/transit-clj->str {})}))))

(specification "Direct session option (bypassing cookies)"
  (component "Initial session via :session option"
    ;; Note: This test uses a simple handler WITHOUT wrap-session middleware.
    ;; When using Ring's wrap-session with a memory store, the session must
    ;; be established through the cookie flow. The :session option is useful
    ;; for handlers that read session directly from the request map.
    (let [ring-handler (make-simple-api-handler)
          ;; Start with an already-established session
          remote       (ctr/fulcro-ring-remote ring-handler
                         :uri "/api"
                         :session {:user {:user/id 99 :user/name "Pre-Authed User" :user/role :user}})
          app          (ct/build-test-app {:root-class SessionRoot
                                           :remotes    {:remote remote}})]
      (behavior "can start with pre-populated session"
        (comp/transact! app [(get-current-user)])
        (assertions
          "user from initial session is retrieved"
          (get-in (rapp/current-state app) [:session/current-user :user/name]) => "Pre-Authed User"
          "is logged in"
          (:session/logged-in? (rapp/current-state app)) => true)))))
