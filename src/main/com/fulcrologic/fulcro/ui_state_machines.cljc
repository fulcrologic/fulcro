(ns com.fulcrologic.fulcro.ui-state-machines
  #?(:cljs (:require-macros com.fulcrologic.fulcro.ui-state-machines))
  (:refer-clojure :exclude [load])
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [ghostwheel.core :refer [>defn => | ? <-]]
    [taoensso.timbre :as log]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as dft]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.misc :as util :refer [atom?]]))

(declare asm-value trigger-state-machine-event! apply-action)
(def mutation-delegate (m/->Mutation `mutation-delegate))

(defn set-js-timeout! [f tm]
  #?(:clj  (f)
     :cljs (js/setTimeout f tm)))

(defn clear-js-timeout! [timer]
  #?(:cljs (js/clearTimeout timer)))

(s/def ::atom (s/with-gen atom? #(s/gen #{(atom {}) (atom #{}) (atom nil)})))

;; Active State Machine and ENV specs
(s/def ::state-map map?)
(s/def ::refresh-vector (s/with-gen (s/coll-of eql/ident? :kind vector?) #(s/gen [[:table 1] [:other :tab]])))
(s/def ::fulcro-app comp/fulcro-app?)
(s/def ::source-actor-ident eql/ident?)
(s/def ::actor-name keyword?)
(s/def ::actor->component-name (s/map-of ::actor-name keyword?))
(s/def ::actor->ident (s/map-of ::actor-name eql/ident?))
(s/def ::ident->actor (s/map-of eql/ident? ::actor-name))
(s/def ::active-state keyword?)                             ; The state the active instance is currently in
(s/def ::state-machine-id (s/with-gen symbol? #(s/gen #{'the-state-machine}))) ; The symbol of the state machine's definition
(s/def ::asm-id any?)                                       ; The ID of the active instance in fulcro state
(s/def ::local-storage (s/map-of keyword? any?))
(s/def ::timeout pos-int?)
(s/def ::timer-id (s/with-gen any? #(s/gen #{:timer-1 42})))
(s/def ::cancel-fn (s/with-gen (s/or :f fn? :s set?) #(s/gen #{#{:event! :other!}})))
(s/def ::cancel-on (s/with-gen (fn fn-or-set* [i] (let [f (-> i meta :cancel-on)]
                                                    (or (fn? f) (set? f)))) #(s/gen #{(with-meta {} {:cancel-on (fn [e] true)})})))
(s/def ::js-timer (s/with-gen #(-> % meta :timer boolean) #(s/gen #{(with-meta {} {:timer {}})})))
(s/def ::timeout-descriptor (s/keys :req [::js-timer ::timeout ::event-id ::timer-id ::cancel-on] :opt [::event-data]))
(s/def ::queued-timeouts (s/coll-of ::timeout-descriptor))
(s/def ::active-timers (s/map-of ::timer-id ::timeout-descriptor))
(s/def ::asm (s/keys :req [::asm-id ::state-machine-id ::active-state ::actor->ident ::actor->component-name
                           ::ident->actor ::active-timers ::local-storage]))
(s/def ::state-id keyword?)
(s/def ::event-data map?)
(s/def ::event-id keyword?)
(s/def ::trigger-descriptor (s/keys :req [::asm-id ::event-id] :opt [::event-data]))
(s/def ::queued-triggers (s/coll-of ::trigger-descriptor))
(s/def ::env (s/keys :req [::state-map ::asm-id]
               :opt [::source-actor-ident ::event-id ::event-data ::queued-triggers
                     ::queued-mutations ::queued-loads ::queued-timeouts]))

(>defn fake-handler [env] [::env => ::env] env)

;; State Machine Definition Specs
(s/def ::actor-names (s/coll-of ::actor-name :kind set?))
(s/def ::event-predicate (s/with-gen fn? #(s/gen #{(fn [_] false) (fn [_] true)})))
(s/def ::handler (s/with-gen fn? #(s/gen #{fake-handler})))
(s/def ::target-state ::state-id)
(s/def ::event-processing (s/keys :opt [::handler ::event-predicate ::target-state]))
(s/def ::events (s/map-of ::event-id ::event-processing))
(s/def ::state (s/with-gen
                 (s/or
                   :handler (s/keys :req [::handler])
                   :events (s/keys :req [::events]))
                 #(s/gen #{{::handler fake-handler}})))
(s/def ::states (s/with-gen (s/map-of ::state-id ::state) #(s/gen #{{:initial {::handler fake-handler}}})))
(s/def ::alias keyword?)
(s/def ::aliases (s/map-of ::alias (s/tuple ::actor-name keyword?)))
(s/def ::plugin (s/with-gen any? #(s/gen #{(fn [aliases] nil)})))
(s/def ::plugins (s/map-of keyword? ::plugin))
(s/def ::event-names (s/coll-of keyword? :kind set?))
(s/def ::target-state keyword?)
(s/def ::state-machine-definition (s/with-gen
                                    (s/keys :req [::states] :opt [::actor-names ::aliases ::plugins ::event-names])
                                    #(s/gen #{{::actor-names #{:a}
                                               ::states      {:initial {::handler (fn [env] env)}}}})))

;; ================================================================================
;; State Machine Registry
;; ================================================================================

(def registry (atom {}))
(defn register-state-machine! [id definition] (swap! registry assoc id definition))


(>defn get-state-machine [id] [::state-machine-id => (s/nilable ::state-machine-definition)] (get @registry id))

(>defn lookup-state-machine [env]
  [::env => (s/nilable ::state-machine-definition)]
  (some->> (asm-value env [::state-machine-id]) (get @registry)))

(>defn lookup-state-machine-field
  [env ks]
  [::env (s/or :k keyword? :kpath vector?) => any?]
  (if (vector? ks)
    (get-in (lookup-state-machine env) ks)
    (get (lookup-state-machine env) ks)))

;; ================================================================================
;; Active State Machine API
;; ================================================================================

(defmutation trigger-state-machine-event
  "Mutation: Trigger an event on an active state machine"
  [{::keys [event-id event-data asm-id] :as params}]
  (action [{:keys [app] :as env}]
    (log/debug "Triggering" event-id "on" asm-id "with" event-data)
    (trigger-state-machine-event! env params)
    (app/schedule-render! app)
    true))

(defn trigger!
  "Trigger an event on an active state machine. Safe to use in mutation bodies."
  ([this active-state-machine-id event-id] (trigger! this active-state-machine-id event-id {}))
  ([this active-state-machine-id event-id extra-data]
   (comp/transact! this [(trigger-state-machine-event {::asm-id     active-state-machine-id
                                                       ::event-id   event-id
                                                       ::event-data extra-data})])))


(>defn new-asm
  "Create the runtime state for the given state machine in it's initial state.

  - `::state-machine-id` is the globally unique key of for a state machine definition.
  - `::asm-id` is a user-generated unique ID for the instance of the asm. This allows more than one
    instance of the same state machine definition to be active at the same time on the UI.
  - `::actor->ident` is a map from actor name to an ident.

  Returns an active state machine that can be stored in Fulcro state for a specific
  state machine definition."
  [{::keys [state-machine-id asm-id actor->ident actor->component-name]}]
  [(s/keys :req [::state-machine-id ::asm-id ::actor->ident]) => ::asm]
  (let [i->a (set/map-invert actor->ident)]
    {::asm-id                asm-id
     ::state-machine-id      state-machine-id
     ::active-state          :initial
     ::ident->actor          i->a
     ::actor->ident          actor->ident
     ::actor->component-name (or actor->component-name {})
     ::active-timers         {}
     ::local-storage         {}}))

(>defn asm-path
  "Returns the path to an asm elements in an asm `env`."
  [{::keys [state-map asm-id] :as env} ks]
  [::env (s/or :v vector? :k keyword?) => vector?]
  (let [path (if (vector? ks)
               (into [::state-map ::asm-id asm-id] ks)
               [::state-map ::asm-id asm-id ks])]
    (when (not (get-in state-map [::asm-id asm-id]))
      (log/debug "Attempt to get an ASM path" ks "for a state machine that is not in Fulcro state. ASM ID: " asm-id))
    path))

(>defn asm-value
  "Get the value of an ASM based on keyword OR key-path `ks`."
  [env ks]
  [::env (s/or :v vector? :k keyword?) => any?]
  (get-in env (asm-path env ks)))

(>defn valid-state?
  [env state-id]
  [::env ::state-id => boolean?]
  (let [states (set/union #{::exit ::started} (-> (lookup-state-machine-field env ::states) keys set))]
    (contains? states state-id)))

(>defn activate
  "Move to the given state. Returns a new env."
  [env state-id]
  [::env ::state-id => ::env]
  (if (valid-state? env state-id)
    (do
      (log/debug "Activating state " state-id "on" (::asm-id env))
      (assoc-in env (asm-path env ::active-state) state-id))
    (do
      (log/error "Activate called for invalid state: " state-id "on" (::asm-id env))
      env)))

(>defn store
  "Store a k/v pair with the active state machine (will only exist as long as it is active)"
  [env k v]
  [::env keyword? any? => ::env]
  (log/debug "Storing" k "->" v "on" (::asm-id env))
  (update-in env (asm-path env ::local-storage) assoc k v))

(>defn retrieve
  "Retrieve the value for a k from the active state machine. See `store`."
  ([env k]
   [::env keyword? => any?]
   (retrieve env k nil))
  ([env k dflt]
   [::env keyword? any? => any?]
   (get-in env (asm-path env [::local-storage k]) dflt)))

(>defn actor->ident
  [env actor-name]
  [::env ::actor-name => (s/nilable eql/ident?)]
  (when-let [lookup (get-in env (asm-path env ::actor->ident))]
    (lookup actor-name)))

(>defn resolve-alias
  "Looks up the given alias in the alias map and returns the real Fulcro state path or nil if no such path exists."
  [env alias]
  [::env ::alias => any?]
  (when-let [resolution-path (lookup-state-machine-field env [::aliases alias])]
    (let [[actor & subpath] resolution-path
          base-path (actor->ident env actor)
          real-path (into base-path subpath)]
      real-path)))

(>defn actor-path
  "Get the real Fulcro state-path for the entity of the given actor."
  ([env actor-name]
   [::env ::actor-name => (s/nilable vector?)]
   (actor-path env actor-name nil))
  ([env actor-name k]
   [::env ::actor-name any? => (s/nilable vector?)]
   (if-let [ident (actor->ident env actor-name)]
     (cond-> ident
       k (conj k))
     nil)))

(>defn set-actor-value
  "Set a value in the actor's Fulcro entity. Only the actor is resolved. The k is not processed as an alias. "
  [env actor-name k v]
  [::env ::actor-name any? any? => ::env]
  (if-let [path (actor-path env actor-name k)]
    (update env ::state-map assoc-in path v)
    env))

(>defn actor-value
  "Get the value of a particular key in the given actor's entity. If follow-idents? is true (which is the default),
  then it will recursively follow idents until it finds a non-ident value."
  ([{::keys [state-map] :as env} actor-name k follow-idents?]
   [::env ::actor-name any? boolean? => any?]
   (when-let [path (actor-path env actor-name k)]
     (loop [v (get-in state-map path) depth 100]
       (if (and follow-idents? (eql/ident? v) (pos-int? depth))
         (recur (get-in state-map v) (dec depth))
         v))))
  ([env actor-name k]
   [::env ::actor-name any? => any?]
   (actor-value env actor-name k true)))

(>defn alias-value
  "Get a Fulcro state value by state machine data alias."
  [{::keys [state-map] :as env} alias]
  [::env keyword? => any?]
  (if-let [real-path (resolve-alias env alias)]
    (get-in state-map real-path)
    (do
      (log/error "Unable to find alias in state machine:" alias)
      nil)))

(>defn set-aliased-value
  ([env alias new-value alias-2 value-2 & kv-pairs]
   [::env ::alias any? ::alias any? (s/* any?) => ::env]
   (let [kvs (into [[alias new-value] [alias-2 value-2]] (partition 2 kv-pairs))]
     (reduce
       (fn [e [k v]]
         (set-aliased-value e k v))
       env
       kvs)))
  ([env alias new-value]
   [::env ::alias any? => ::env]
   (if-let [real-path (resolve-alias env alias)]
     (do
       (log/debug "Updating value for " (::asm-id env) "alias" alias "->" new-value)
       (update env ::state-map assoc-in real-path new-value))
     (do
       (log/error "Attempt to set a value on an invalid alias:" alias)
       env))))

(>defn aliased-data
  "Extracts aliased data from Fulcro state to construct arguments. If explicit-args is supplied,
   then that is merged with aliased data, passed to the named plugin.  The return of the plugin is
   the result of this function"
  [env]
  [::env => map?]
  (let [alias-keys (some-> (lookup-state-machine-field env ::aliases) keys)]
    (reduce (fn [result k]
              (assoc result k (alias-value env k)))
      {}
      alias-keys)))

(>defn run
  "Run a state-machine plugin. Extracts aliased data from Fulcro state to construct arguments. If explicit-args is supplied,
   then that is merged with aliased data, passed to the named plugin.  The return of the plugin is
   the result of this function. Plugins cannot side-effect, and are meant for providing external computation algorithms
   that the state machine logic might need. For example, an actor representing a form might need to provide validation
   logic.

   If explicit-args are passed, then they will take *precedence* over the auto-extracted aliased data that is passed to
   the plugin."
  ([env plugin-name]
   [::env keyword? => any?]
   (run env plugin-name nil))
  ([env plugin-name explicit-args]
   [::env keyword? (s/nilable map?) => any?]
   (when-let [plugin (lookup-state-machine-field env [::plugins plugin-name])]
     (let [params (merge (aliased-data env) explicit-args)]
       (plugin params)))))

(>defn exit
  "Indicate that the state machine is done."
  [env]
  [::env => ::env]
  (log/debug "Exiting state machine" (::asm-id env))
  (activate env ::exit))

(>defn apply-event-value
  [env {::keys [event-id event-data]}]
  [::env (s/keys :opt [::event-id ::event-data]) => ::env]
  (let [alias (::alias event-data)
        value (:value event-data)]
    (cond-> env
      (and (= ::value-changed event-id) alias)
      (set-aliased-value alias value))))

(>defn state-machine-env
  "Create an env for use with other functions. Used internally, but may be used as a helper ."
  ([state-map asm-id]
   [::state-map ::asm-id => ::env]
   (state-machine-env state-map nil asm-id nil nil))
  ([state-map ref asm-id event-id event-data]
   [::state-map (s/nilable eql/ident?) ::asm-id (s/nilable ::event-id) (s/nilable ::event-data) => ::env]
   (cond-> {::state-map state-map
            ::asm-id    asm-id}
     event-id (assoc ::event-id event-id)
     (seq event-data) (assoc ::event-data event-data)
     ref (assoc ::source-actor-ident ref))))

(>defn with-actor-class
  "Associate a given component UI Fulcro class with an ident.  This is used with `begin!` in your actor map if the
  actor in question is going to be used with loads or mutations that return a value of that type. The actor's class
  can be retrieved for use in a handler using `(uism/actor-class env)`.

  ```
  (begin! ... {:person (uism/with-actor-class [:person/by-id 1] Person)})
  ```
  "
  [ident class]
  [eql/ident? comp/component-class? => eql/ident?]
  (vary-meta ident assoc ::class class))

(>defn any->actor-component-registry-key
  "Convert one of the possible inputs for an actor into an actor component registry key.

  v can be an ident with actor metadata (see `with-actor-class`), a Fulcro runtime instance whose `get-ident` returns
  a valid ident, or a Fulcro component class with a singleton ident.

  Returns the Fulcro component registry key (a keyword) that will be able to find the real Fulcro
  component for `v`."
  [v]
  [any? => (s/nilable keyword?)]
  (when-let [cls (cond
                   (and (eql/ident? v) (comp/component-class? (some-> v meta ::class))) (some-> v meta ::class)
                   (and (comp/component? v) (-> (comp/get-ident v) second)) (comp/react-type v)
                   (and (comp/component-class? v) (-> (comp/get-ident v {}) second)) v
                   :otherwise nil)]
    (let [str-name (comp/component-name cls)
          [ns nm] (str/split str-name #"/")
          k        (keyword ns nm)]
      k)))

(>defn actor-class
  "Returns the Fulcro component class that for the given actor, if set."
  [env actor-name]
  [::env ::actor-name => (s/nilable comp/component-class?)]
  (let [actor->component-name (asm-value env ::actor->component-name)
        cls                   (some-> actor-name actor->component-name comp/classname->class)]
    cls))

(>defn reset-actor-ident
  "Safely changes the ident of an actor.

  Makes sure ident is consistently reset and updates the actor class (if one is specified
  using `with-actor-class`)."
  [env actor ident]
  [::env ::alias eql/ident? => ::env]
  (let [new-actor             (any->actor-component-registry-key ident)
        actor->ident          (-> env
                                (asm-value ::actor->ident)
                                (assoc actor ident))
        ident->actor          (clojure.set/map-invert actor->ident)

        actor->ident-path     (asm-path env ::actor->ident)
        actor->component-path (conj (asm-path env ::actor->component-name) actor)
        ident->actor-path     (asm-path env ::ident->actor)]
    (-> env
      (assoc-in actor->ident-path actor->ident)
      (assoc-in ident->actor-path ident->actor)
      (cond->
        new-actor (assoc-in actor->component-path new-actor)))))

(>defn assoc-aliased
  "Similar to clojure.core/assoc but works on UISM env and aliases."
  ([env alias new-value alias-2 value-2 & kv-pairs]
   [::env ::alias any? ::alias any? (s/* any?) => ::env]
   (apply set-aliased-value env alias new-value
     alias-2 value-2 kv-pairs))
  ([env alias new-value]
   [::env ::alias any? => ::env]
   (set-aliased-value env alias new-value)))

(>defn update-aliased
  "Similar to clojure.core/update but works on UISM env and aliases."
  ([env k f]
   [::env ::alias any? => ::env]
   (assoc-aliased env k (f (alias-value env k))))
  ([env k f x]
   [::env ::alias any? any? => ::env]
   (assoc-aliased env k (f (alias-value env k) x)))
  ([env k f x y]
   [::env ::alias any? any? any? => ::env]
   (assoc-aliased env k (f (alias-value env k) x y)))
  ([env k f x y z]
   [::env ::alias any? any? any? any? => ::env]
   (assoc-aliased env k (f (alias-value env k) x y z)))
  ([env k f x y z & more]
   [::env ::alias any? any? any? any? (s/* any?) => ::env]
   (assoc-aliased env k (apply f (alias-value env k) x y z more))))

(>defn dissoc-aliased
  "Similar to clojure.core/dissoc but works on UISM env and aliases."
  ([env]
   [::env => ::env]
   env)
  ([env alias]
   [::env ::alias => ::env]
   (when-not (nil? env)
     (let [path     (resolve-alias env alias)
           sub-path (butlast path)
           k        (last path)]
       (log/debug "Dissoc of aliased value" alias "on" (::asm-id env))
       (apply-action env #(update-in % sub-path dissoc k)))))
  ([env k & ks]
   [::env ::alias (s/* ::alias) => ::env]
   (when-not (nil? env)
     (let [ret (dissoc-aliased env k)]
       (if ks
         (recur ret (first ks) (next ks))
         ret)))))

(>defn integrate-ident
  "Integrate an ident into any number of aliases in the state machine.
  Aliases must point to a list of idents.

  The named parameters can be specified any number of times. They are:

  - append:  A keyword (alias) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A keyword (alias) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list."
  [env ident & named-parameters]
  [::env eql/ident? (s/* (s/cat :name #{:prepend :append} :param keyword?)) => ::env]
  (log/debug "Integrating" ident "on" (::asm-id env))
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [env [command alias-to-idents]]
              (let [alias-value                 (alias-value env alias-to-idents)
                    already-has-ident-at-alias? (some #(= % ident) alias-value)]
                (case command
                  :prepend (if already-has-ident-at-alias?
                             env
                             (update-aliased env alias-to-idents #(into [ident] %)))
                  :append (if already-has-ident-at-alias?
                            env
                            (update-aliased env alias-to-idents (fnil conj []) ident))
                  (throw (ex-info "Unknown operation for integrate-ident: " {:command command :arg alias-to-idents})))))
      env actions)))

(>defn remove-ident
  "Removes an ident, if it exists, from an alias that points to a list of idents."
  [env ident alias-to-idents]
  [::env eql/ident? ::alias => ::env]
  (log/debug "Removing" ident "from" alias-to-idents "on" (::asm-id env))
  (let [new-list (fn [old-list]
                   (vec (filter #(not= ident %) old-list)))]
    (update-aliased env alias-to-idents new-list)))

(>defn queue-mutations!
  [app env]
  [::fulcro-app ::env => nil?]
  (let [queued-mutations (::queued-mutations env)]
    (doseq [mutation-params queued-mutations]
      (comp/transact! app [(mutation-delegate mutation-params)]))
    nil))

(>defn queue-actor-load!
  "Internal implementation. Queue a load of an actor."
  [app env actor-name component-class load-options]
  [::fulcro-app ::env ::actor-name (s/nilable comp/component-class?) ::load-options => nil?]
  (let [actor-ident (actor->ident env actor-name)
        cls         (or component-class (actor-class env actor-name))]
    (log/debug "Starting actor load" actor-name "on" (::asm-id env))
    (if (nil? cls)
      (log/error "Cannot run load. Counld not derive Fulcro class (and none was configured) for " actor-name)
      (df/load app actor-ident cls load-options))
    nil))

(>defn queue-normal-load!
  "Internal implementation. Queue a load."
  [app query-key component-class load-options]
  [::fulcro-app ::query-key (s/nilable comp/component-class?) ::load-options => nil?]
  (if (nil? query-key)
    (log/error "Cannot run load. query-key cannot be nil.")
    (do
      (log/debug "Starting load of" query-key)
      (df/load app query-key component-class load-options)))
  nil)

(>defn handle-load-error* [app load-request]
  [::fulcro-app ::load-options => nil?]
  (let [{::keys [asm-id error-event error-data]} (some-> load-request :post-mutation-params)]
    (log/debug "Handling load error" asm-id ":" error-event)
    (if (and asm-id error-event)
      (comp/transact! app [(trigger-state-machine-event (cond-> {::asm-id   asm-id
                                                                 ::event-id error-event}
                                                          error-data (assoc ::event-data error-data)))])
      (do
        (log/warn "A fallback occurred, but no event was defined by the client. Sending generic ::uism/load-error event.")
        (comp/transact! app [(trigger-state-machine-event (cond-> {::asm-id   asm-id
                                                                   ::event-id ::load-error}))])))
    nil))

(defmutation handle-load-error [_]
  (action [{:keys [app load-request]}]
    (handle-load-error* app load-request)))

(>defn queue-loads! [app env]
  [::fulcro-app ::env => nil?]
  (let [queued-loads (::queued-loads env)]
    (doseq [{::comp/keys [component-class]
             ::keys      [actor-name query-key load-options] :as load-params} queued-loads]
      (if actor-name                                        ; actor-centric load
        (queue-actor-load! app env actor-name component-class load-options)
        (queue-normal-load! app query-key component-class load-options))))
  nil)

(>defn update-fulcro-state!
  "Put the evolved state-map from an env into a (Fulcro) state-atom"
  [{::keys [asm-id] :as env} state-atom]
  [::env ::atom => nil?]
  (let [next-state (when env (asm-value env ::active-state))]
    (when-let [new-fulcro-state (some-> (::state-map env)
                                  ;; GC state machine if it exited
                                  (cond->
                                    (= ::exit next-state) (update ::asm-id dissoc asm-id)))]
      (reset! state-atom new-fulcro-state))
    nil))

(>defn set-timeout
  "Add a timeout named `timer-id` to the `env` that will send `event-id` with `event-data` event
   after `timeout` (in milliseconds) unless an event (i.e. some-event-id) occurs where a call
   to `(cancel-on-events some-event-id)` returns true.

   Setting a timeout on an existing timer-id will cancel the current one and start the new one.

   `cancel-on-events` is a predicate that will be passed an event ID on events. If it returns true
    on an event before the timeout fires, then the timeout will be auto-cancelled. If not specified, then
    it defaults to `(constantly false)`."
  ([env timer-id event-id event-data timeout]
   [::env ::timer-id ::event-id ::event-data pos-int? => ::env]
   (set-timeout env timer-id event-id event-data timeout (constantly false)))
  ([env timer-id event-id event-data timeout cancel-on-events]
   [::env ::timer-id ::event-id ::event-data pos-int? ::cancel-fn => ::env]
   (let [descriptor (cond-> {::timeout   timeout
                             ::timer-id  timer-id
                             ::js-timer  (with-meta {} {:timer true})
                             ::event-id  event-id
                             ::cancel-on (with-meta {} {:cancel-on cancel-on-events})}
                      event-data (assoc ::event-data event-data))]
     (update env ::queued-timeouts (fnil conj []) descriptor))))

(>defn clear-timeout!
  "Clear a scheduled timeout (if it has yet to fire).  Harmless to call if the timeout is gone. This call takes
  effect immediately (in terms of making sure the timeout does not fire)."
  [env timer-id]
  [::env ::timer-id => ::env]
  (log/debug "Clearing timeout " (::asm-id env) ":" timer-id)
  (let [{::keys [js-timer]} (asm-value env [::active-timers timer-id])
        real-js-timer (-> js-timer meta :timer)]
    (when real-js-timer
      (clear-js-timeout! real-js-timer))
    (-> env
      (update-in (asm-path env [::active-timers]) dissoc timer-id))))

(>defn generic-event-handler
  "Returns an event handler that can process events according to a state machine
  ::uism/events definition of the current event/state in `env`.
  If a definition cannot be found then it returns nil."
  [original-env]
  [::env => (s/nilable ::handler)]
  (let [smdef            (lookup-state-machine original-env)
        current-state-id (asm-value original-env ::active-state)
        current-event    (::event-id original-env)
        {::keys [event-predicate handler target-state] :as event-def} (some-> smdef ::states (get current-state-id) ::events (get current-event))]
    (if event-def
      (fn [env]
        (if (or (nil? event-predicate) (and event-predicate (event-predicate env)))
          (let [env                (if handler (or (handler env) env) env)
                post-handler-state (-> env (asm-value ::active-state))
                state-changed?     (not= post-handler-state current-state-id)]
            (cond-> env
              (and (not state-changed?) target-state) (activate target-state)))
          ;; IMPORTANT: UNDO value changes if the predicate is disabled
          original-env))
      nil)))

(>defn active-state-handler
  "Find the handler for the active state in the current env."
  [env]
  [::env => ::handler]
  (let [smdef         (lookup-state-machine env)
        current-state (asm-value env ::active-state)
        handler       (or
                        (get-in smdef [::states current-state ::handler])
                        (generic-event-handler env))]
    (if handler
      handler
      (let [{::keys [event-id]} env]
        (log/warn "UNEXPECTED EVENT: Did not find a way to handle event" event-id "in the current active state:" current-state)
        identity))))

(>defn ui-refresh-list
  "Returns a vector of things to refresh in Fulcro based on the final state of an active SM env."
  [env]
  [::env => (s/coll-of eql/ident? :kind vector?)]
  (let [actor-idents (or (some-> env (get-in (asm-path env ::actor->ident)) vals vec) [])]
    actor-idents))

(>defn get-js-timer [env timer-id]
  [::env ::timer-id => any?]
  (some-> (asm-value env [::active-timers timer-id]) ::js-timer meta :timer))

(>defn schedule-timeouts!
  "INTERNAL: actually schedule the timers that were submitted during the event handler."
  [app env]
  [::fulcro-app ::env => ::env]
  (let [{::keys [queued-timeouts asm-id]} env]
    (reduce
      (fn [env {::keys [timeout event-id event-data timer-id] :as descriptor}]
        (log/debug "Setting timeout" timer-id "on" asm-id "to send" event-id "in" timeout "ms")
        (let [current-timer (get-js-timer env timer-id)
              js-timer      (set-js-timeout! (fn []
                                               (log/debug "TIMEOUT on" asm-id "due to timer" timer-id "after" timeout "ms")
                                               (trigger! app asm-id event-id (or event-data {}))) timeout)
              descriptor    (update-in descriptor [::js-timer] vary-meta assoc :timer js-timer)]
          (when current-timer
            (log/debug "Clearing old timer (new timer supercedes)")
            (clear-js-timeout! current-timer))
          (assoc-in env (asm-path env [::active-timers timer-id]) descriptor)))
      env
      queued-timeouts)))

(>defn clear-timeouts-on-event!
  "Processes the auto-cancel of events. This is a normal part of the internals, but can be used in handlers
  to simulate a *different* event than acutally occured for the purpose of clearing sets of timers that
  auto-cancel on other events than what occurred."
  [env event-id]
  [::env ::event-id => ::env]
  (let [active-timers (asm-value env ::active-timers)]
    (reduce
      (fn [env timer-id]
        (let [cancel-predicate (some-> (get-in active-timers [timer-id ::cancel-on]) meta :cancel-on)]
          (when-not cancel-predicate
            (log/error "INTERNAL ERROR: Cancel predicate was nil for timer " timer-id))
          (if (and cancel-predicate (cancel-predicate event-id))
            (do
              (log/debug "Cancelling timer " timer-id "on" (::asm-id env) "due to event" event-id)
              (clear-timeout! env timer-id))
            env)))
      env
      (keys active-timers))))

(s/def :fulcro/app ::fulcro-app)
(s/def :fulcro/state ::atom)
(s/def ::mutation-env (s/keys :req-un [:fulcro/state :fulcro/app]))

(>defn trigger-queued-events!
  [mutation-env queued-triggers refresh-list]
  [::mutation-env (? ::queued-triggers) ::refresh-vector => ::refresh-vector]
  (let [result
        (reduce (fn [refresh-list event]
                  (into refresh-list (trigger-state-machine-event! mutation-env event)))
          refresh-list
          queued-triggers)]
    result))

(>defn trigger-state-machine-event!
  "IMPLEMENTATION DETAIL. Low-level implementation of triggering a state machine event. Does no direct interaction with
  Fulcro UI refresh.  Use `trigger!` instead.

  - `env` - A fulcro mutation env, containing at least the state atom and optionally the ref of the
    component that was the source of the event.
  - params - The parameters for the event

  Returns a vector of actor idents that should be refreshed."
  [{:keys [app state ref] :as mutation-env} {::keys [event-id event-data asm-id] :as params}]
  [::mutation-env ::trigger-descriptor => ::refresh-vector]
  (when-not (get-in @state [::asm-id asm-id])
    (log/error "Attemped to trigger event " event-id "on state machine" asm-id ", but that state machine has not been started (call begin! first)."))
  (let [sm-env       (state-machine-env @state ref asm-id event-id event-data)
        handler      (active-state-handler sm-env)
        valued-env   (apply-event-value sm-env params)
        handled-env  (handler (assoc valued-env ::fulcro-app app))
        final-env    (as-> (or handled-env valued-env) e
                       (clear-timeouts-on-event! e event-id)
                       (schedule-timeouts! app e))
        refresh-list (ui-refresh-list final-env)]
    (queue-mutations! app final-env)
    (queue-loads! app final-env)
    (update-fulcro-state! final-env state)
    (trigger-queued-events! mutation-env (::queued-triggers final-env) refresh-list)))

(>defn trigger
  "Trigger an event on another state machine.

  `env` - is the env in a state machine handler
  `state-machine-id` - The ID of the state machine you want to trigger an event on.
  `event` - The event ID you want to send.
  `event-data` - A map of data to send with the event

  Returns the updated env.  The actual event will not be sent until this handler finishes."
  ([env state-machine-id event]
   [::env ::asm-id ::event-id => ::env]
   (trigger env state-machine-id event {}))
  ([env state-machine-id event event-data]
   [::env ::asm-id ::event-id ::event-data => ::env]
   (update env ::queued-triggers (fnil conj []) {::asm-id     state-machine-id
                                                 ::event-id   event
                                                 ::event-data event-data})))

(defn set-string!
  "Similar to Fulcro's set-string, but it sets the string on an active state machine's data alias.
  event-or-string can be a string or a React DOM onChange event.

  The incoming `event-data` to your handler will include `::uism/alias` and `:value` (if you care to do anything
  with the value change event).

  NOTE: Generates a ::uism/value-changed event. If you're state machine is implemented with the events
  structure that allows an event-predicate, then this set will be ignored if the current state's event-predicate
  returns false."
  [this active-state-machine-id alias event-or-string]
  (let [value (if (string? event-or-string)
                event-or-string
                (or (some-> event-or-string .-target .-value) ""))]
    (trigger! this active-state-machine-id ::value-changed {::alias alias
                                                            :value  value})))

(defn set-value!
  "Similar to Fulcro's set-value, but it sets the raw value on an active state machine's data alias.

  The incoming `event-data` to your handler will include `::uism/alias` and `:value` (if you care to do anything
  with the value change event).

  NOTE: Generates a ::uism/value-changed event. If you're state machine is implemented with the events
  structure that allows an event-predicate, then this set will be ignored if the current state's event-predicate
  returns false."
  [this active-state-machine-id alias value]
  (trigger! this active-state-machine-id ::value-changed {::alias alias
                                                          :value  value}))

(defmutation begin
  "Mutation to begin a state machine. Use `begin!` instead."
  [{::keys [asm-id event-data] :as params}]
  (action [{:keys [app state] :as env}]
    (swap! state (fn [s]
                   (-> s
                     (assoc-in [::asm-id asm-id] (new-asm params)))))
    (trigger-state-machine-event! env (cond-> {::event-id   ::started
                                               ::asm-id     asm-id
                                               ::event-data {}}
                                        event-data (assoc ::event-data event-data)))
    (app/schedule-render! app)))

(>defn derive-actor-idents
  "Generate an actor->ident map."
  [actors]
  [(s/map-of ::actor-name (s/or
                            :ident eql/ident?
                            :component comp/component?
                            :class comp/component-class?)) => ::actor->ident]
  (into {}
    ;; v can be an ident, component, or component class
    (keep (fn [[actor-id v]]
            (cond
              (and (comp/component? v) (-> (comp/get-ident v) second))
              [actor-id (comp/get-ident v)]

              (and (comp/component-class? v) (-> (comp/get-ident v {}) second))
              [actor-id (comp/get-ident v {})]

              (eql/ident? v) [actor-id v]
              :otherwise (do
                           (log/error "The value given for actor" actor-id "had (or was) an invalid ident:" v)
                           nil))))
    actors))

(>defn derive-actor-components
  "Calculate the map from actor names to the Fulcro component registry names that represent those actors."
  [actors]
  [(s/map-of ::actor-name (s/or
                            :ident eql/ident?
                            :component comp/component?
                            :class comp/component-class?)) => ::actor->component-name]
  (into {}
    ;; v can be an ident, component, or component class
    (keep (fn [[actor-id v]]
            (when-let [k (any->actor-component-registry-key v)]
              [actor-id k])))
    actors))

(>defn begin!
  "Install and start a state machine.

  this - A UI component or app
  machine - A state machine defined with defstatemachine
  instance-id - An ID by which you will refer to this active instance.
  actors - A map of actor-names -> The ident, class, or react instance that represent them in the UI. Raw idents do not support SM loads.
  started-event-data - Data that will be sent with the ::uism/started event as ::uism/event-data"
  ([this machine instance-id actors]
   [(s/or :c comp/component? :r ::fulcro-app) ::state-machine-definition ::asm-id (s/map-of ::actor-name any?) => any?]
   (begin! this machine instance-id actors {}))
  ([this machine instance-id actors started-event-data]
   [(s/or :c comp/component? :r ::fulcro-app) ::state-machine-definition ::asm-id (s/map-of ::actor-name any?) ::event-data => any?]
   (let [actors->idents          (derive-actor-idents actors)
         actors->component-names (derive-actor-components actors)]
     (log/debug "begin!" instance-id)
     (comp/transact! this [(begin {::asm-id                instance-id
                                   ::state-machine-id      (::state-machine-id machine)
                                   ::event-data            started-event-data
                                   ::actor->component-name actors->component-names
                                   ::actor->ident          actors->idents})]))))

#?(:clj
   (defmacro defstatemachine [name body]
     (let [nmspc       (str (ns-name *ns*))
           storage-sym (symbol nmspc (str name))]
       `(do
          (def ~name (assoc ~body ::state-machine-id '~storage-sym))
          (register-state-machine! '~storage-sym ~body)))))

;; ================================================================================
;; I/O Integration: remote mutations
;; ================================================================================

(s/def ::target-actor ::actor-name)
(s/def ::target-alias ::alias)
(s/def ::ok-event ::event-id)
(s/def ::error-event ::event-id)
(s/def ::ok-data map?)
(s/def ::error-data map?)
(s/def ::mutation (s/with-gen symbol? #(s/gen #{`do-something})))
(def spec-mutation (m/->Mutation `spec-mutation))
(s/def ::mutation-decl (s/with-gen m/mutation-declaration? #(s/gen #{spec-mutation})))
(s/def ::mutation-context ::actor-name)
(s/def ::mutation-descriptor (s/keys :req [::mutation-context ::mutation]
                               :opt [::m/target ::ok-event ::ok-data ::error-event ::error-data
                                     ::m/returning ::mutation-remote]))
(s/def ::mutation-remote keyword?)
(s/def ::queued-mutations (s/coll-of ::mutation-descriptor))

(>defn compute-target
  "Compute a raw Fulcro target based on the possible options.

  `env` - The SM env

  targeting options:

  `::m/target explicit-target` - A raw Fulcro data fetch target.
  `::uism/target-actor actor-alias` - Helper that can translate an actor alias to a target
  `::uism/target-alias field-alias` - Helper that can translate a data alias to a target (ident + field)

  If more than one option is used, then `df/mutliple-targets` will be used to encode them all.
  "
  [env {::m/keys [target]
        ::keys   [target-actor target-alias]}]
  [::env (s/keys :opt [::m/target ::target-actor ::target-alias]) => (s/nilable vector?)]
  (let [noptions (count (keep identity [target target-actor target-alias]))
        actor    (when target-actor (actor->ident env target-actor))
        field    (when target-alias (resolve-alias env target-alias))]
    (if (> noptions 1)
      (if (and target (dft/multiple-targets? target))
        (into target (keep identity [actor field]))
        (apply df/multiple-targets (keep identity [target actor field])))
      (or target actor field))))

(let [mtrigger! (fn mutation-trigger* [{:keys [app state]} actor-ident asm-id event data]
                  (let [response   (get-in @state (conj actor-ident ::m/mutation-response))
                        event-data (merge {} data response)]
                    (comp/transact! app actor-ident [(trigger-state-machine-event {::asm-id     asm-id
                                                                                   ::event-id   event
                                                                                   ::event-data event-data})])))]
  (defmethod m/mutate `mutation-delegate [{:keys [state ast] :as env}]
    ;; mutation can be run for figuring out remote
    (let [{::keys [asm-id ok-event error-event mutation
                   mutation-context ok-data error-data mutation-remote] :as mp} (:params ast)]
      (if (or (empty? @state) (empty? mp))
        {(or mutation-remote :remote) true}
        (let [sm-env      (state-machine-env @state nil asm-id ok-event ok-data)
              actor-ident (actor->ident sm-env mutation-context)
              to-refresh  (ui-refresh-list sm-env)
              abort-id    (:abort-id mp)
              fixed-env   (-> env
                            (assoc :ref actor-ident)
                            ;(cond-> abort-id (update :ast m/with-abort-id abort-id))
                            (update :ast assoc
                              :key mutation
                              :dispatch-key mutation
                              :params (dissoc mp ::ok-event ::error-event ::mutation
                                        ::mutation-context ::ok-data ::error-data
                                        ::mutation-remote ::asm-id)))]
          (cond-> {:refresh                     to-refresh
                   (or mutation-remote :remote) true}
            ok-event (assoc :ok-action (fn []
                                         (log/debug "Remote mutation " mutation "success")
                                         (mtrigger! fixed-env actor-ident asm-id ok-event ok-data)))
            error-event (assoc :error-action (fn []
                                               (log/debug "Remote mutation " mutation "error")
                                               (mtrigger! fixed-env actor-ident asm-id error-event error-data)))))))))

(>defn trigger-remote-mutation
  "Run the given REMOTE mutation (a symbol or mutation declaration) in the context of the state machine.

  `env` - The SM handler environment
  `actor` - The name (keyword) of a defined actor.
  `mutation` - The symbol (or mutation declaration) of the *server* mutation to run. This function will *not* run a local
  version of the mutation.
  `options-and-params` - The parameters to pass to your mutation. This map can also include these additional
  state-machine options:

  `::m/returning Class` - Class to use for normalizing result.
  `::m/target explicit-target` - Target for result
  `::uism/target-actor actor` - Helper that can translate an actor name to a target, if returning a result.
  `::uism/target-alias field-alias` - Helper that can translate a data alias to a target (ident + field).
  `::uism/ok-event event-id` - The SM event to trigger when the pessimistic mutation succeeds (no default).
  `::uism/error-event event-id` - The SM event to trigger when the pessimistic mutation fails (no default).
  `::uism/ok-data map-of-data` - Data to include in the event-data on an ok event
  `::uism/error-data map-of-data` - Data to include in the event-data on an error event
  `::uism/mutation-remote` - The keyword name of the Fulcro remote (defaults to :remote)

  NOTE: The mutation response *will be merged* into the event data that is sent to the SM handler.

  This function does *not* side effect.  It queues the mutation to run after the handler exits."
  [env actor mutation options-and-params]
  [::env ::actor-name
   (s/or :sym ::mutation :decl ::mutation-decl)
   (s/keys :opt [::m/returning ::m/target ::target-actor
                 ::target-alias ::ok-event ::error-event ::ok-data ::error-data ::mutation-remote])
   => ::env]
  (let [target              (compute-target env options-and-params)
        asm-id              (::asm-id env)
        mutation-sym        (m/mutation-symbol mutation)
        mutation-descriptor (-> options-and-params
                              (dissoc ::target-actor ::target-alias ::m/target)
                              (assoc ::asm-id asm-id ::mutation mutation-sym ::mutation-context actor)
                              (cond->
                                (seq target) (assoc ::m/target target)))]
    (update env ::queued-mutations (fnil conj []) mutation-descriptor)))

;; ================================================================================
;; I/O: Load integration
;; ================================================================================

(s/def ::load-options map?)
(s/def ::query-key (s/or :key keyword? :ident eql/ident?))
(s/def ::load (s/keys :opt [::query-key ::comp/component-class ::load-options]))
(s/def ::queued-loads (s/coll-of ::load))
(s/def ::post-event ::event-id)
(s/def ::post-event-params map?)
(s/def ::fallback-event-params map?)

(>defn convert-load-options
  "INTERNAL: Convert SM load options into Fulcro load options."
  [env options]
  [::env (s/keys :opt [::post-event ::post-event-params ::fallback-event ::fallback-event-params]) => map?]
  (let [{::keys [post-event post-event-params fallback-event fallback-event-params target-actor target-alias]} options
        {:keys [marker]} options
        marker  (if (nil? marker) false marker)             ; force marker to false if it isn't set
        {::keys [asm-id]} env
        options (-> (dissoc options ::post-event ::post-event-params ::fallback-event ::fallback-event-params ::comp/component-class
                      ::target-alias ::target-actor)
                  (assoc :marker marker :abort-id asm-id :fallback `handle-load-error :post-mutation-params (merge post-event-params {::asm-id asm-id}))
                  (cond->
                    (or target-actor target-alias) (assoc :target (compute-target env options))
                    post-event (->
                                 (assoc :post-mutation `trigger-state-machine-event)
                                 (update :post-mutation-params assoc ::event-id post-event))
                    post-event-params (update :post-mutation-params assoc ::event-data post-event-params)
                    ;; piggieback the fallback params and event on post mutation data, since it is the only thing we can see
                    fallback-event (update :post-mutation-params assoc ::error-event fallback-event)
                    fallback-event-params (update :post-mutation-params assoc ::error-data fallback-event-params)))]
    options))

(>defn load
  "Identical API to fulcro's data fetch `load`, but using a handle `env` instead of a component/app.
   Adds the load request to then env which will be sent to Fulcro as soon as the handler finishes.

   The 3rd argument can be a Fulcro class or a UISM actor name that was registered with `begin!`.

  The `options` are as in Fulcro's load, with the following additional keys for convenience:

  `::uism/post-event`:: An event to send when the load is done (instead of calling a mutation)
  `::uism/post-event-params`:: Extra parameters to send as event-data on the post-event.
  `::uism/fallback-event`:: The event to send if the load triggers a fallback.
  `::uism/fallback-event-params`:: Extra parameters to send as event-data on a fallback.
  `::uism/target-actor`:: Set target to a given actor's ident.
  `::uism/target-alias`:: Set load target to the path defined by the given alias.

   NOTE: In general a state machine should declare an actor for items in the machine and use `load-actor` instead of
   this function so that the state definitions themselves need not be coupled (via code) to the UI."
  ([env key-or-ident component-class-or-actor-name]
   [::env ::query-key (s/or :a ::actor-name :c comp/component-class?) => ::env]
   (load env key-or-ident component-class-or-actor-name {}))
  ([env key-or-ident component-class-or-actor-name options]
   [::env ::query-key (s/or :a ::actor-name :c comp/component-class?) ::load-options => ::env]
   (let [options (convert-load-options env options)
         class   (if (s/valid? ::actor-name component-class-or-actor-name)
                   (actor-class env component-class-or-actor-name)
                   component-class-or-actor-name)]
     (update env ::queued-loads (fnil conj []) (cond-> {}
                                                 class (assoc ::comp/component-class class)
                                                 key-or-ident (assoc ::query-key key-or-ident)
                                                 options (assoc ::load-options options))))))

(>defn load-actor
  "Load (refresh) the given actor. If the actor *is not* on the UI, then you *must* specify
   `:com.fulcrologic.fulcro.primitives/component-class` in the `options` map.

   options can contain the normal `df/load` parameters, and also:

  `::comp/component-class` - The defsc name of the component to use for normalization and query. Only needed if the
    actor was not declared using a Fulcro component or component class.
  `::uism/post-event`:: An event to send when the load is done (instead of calling a mutation)
  `::uism/post-event-params`:: Extra parameters to send as event-data on the post-event.
  `::uism/fallback-event`:: The event to send if the load triggers a fallback.
  `::uism/fallback-event-params`:: Extra parameters to send as event-data on a fallback.

   Adds a load request to then env which will be sent to Fulcro as soon as the handler finishes."
  ([env actor-name]
   [::env ::actor-name => ::env]
   (load-actor env actor-name {}))
  ([env actor-name {::comp/keys [component-class] :as options}]
   [::env ::actor-name ::load-options => ::env]
   (let [options (convert-load-options env options)]
     (update env ::queued-loads (fnil conj []) (cond-> {::actor-name   actor-name
                                                        ::load-options options}
                                                 component-class (assoc ::comp/component-class component-class))))))

(>defn apply-action
  "Run a mutation helper function (e.g. a fn of Fulcro state)."
  [env mutation-helper & args]
  [::env fn? (s/* any?) => ::env]
  (log/debug "Applying mutation helper to state of" (::asm-id env))
  (apply update env ::state-map mutation-helper args))


(>defn get-active-state
  "Get the name of the active state for an active state machine using a component."
  [this asm-id]
  [(s/or :c comp/component? :r ::fulcro-app) ::asm-id => (? keyword?)]
  (let [state-map (-> this (comp/any->app) (app/current-state))]
    (some-> state-map
      ::asm-id
      (get asm-id)
      ::active-state)))
