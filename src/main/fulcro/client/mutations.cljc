(ns fulcro.client.mutations
  #?(:cljs (:require-macros fulcro.client.mutations))
  (:require
    [clojure.spec.alpha :as s]
    [fulcro.util :as util :refer [join-key join-value join?]]
    [fulcro.logging :as log]
    [fulcro.client.primitives :as prim]
    #?(:cljs [cljs.loader :as loader])
    [fulcro.client.impl.protocols :as p]
    [fulcro.client.impl.parser :as parser]
    #?(:clj [cljs.analyzer :as ana])
    [clojure.string :as str]))


#?(:clj (s/def ::action (s/cat
                          :action-name (fn [sym] (and (symbol? sym) (str/ends-with? (name sym) "action")))
                          :action-args (fn [a] (and (vector? a) (= 1 (count a))))
                          :action-body (s/+ (constantly true)))))

#?(:clj (s/def ::remote (s/cat
                          :remote-name (fn [sym] (and (symbol? sym) (not (str/ends-with? (name sym) "action"))))
                          :remote-args (fn [a] (and (vector? a) (= 1 (count a))))
                          :remote-body (s/+ (constantly true)))))

#?(:clj (s/def ::mutation-args (s/cat
                                 :sym symbol?
                                 :doc (s/? string?)
                                 :arglist vector?
                                 :sections (s/* (s/or :action ::action :remote ::remote)))))
#?(:clj
   (defn defmutation* [macro-env args]
     (let [conform!        (fn [element spec value]
                             (when-not (s/valid? spec value)
                               (throw (ana/error macro-env (str "Syntax error in " element ": " (s/explain-str spec value)))))
                             (s/conform spec value))
           {:keys [sym doc arglist sections]} (conform! "defmutation" ::mutation-args args)
           _               (.println System/err (with-out-str (clojure.pprint/pprint sections)))
           {:keys [actions remotes]} (reduce (fn [result [k v]]
                                               (if (= :action k)
                                                 (update result :actions conj v)
                                                 (update result :remotes conj v)))
                                       {:actions [] :remotes []}
                                       sections)
           fqsym           (if (namespace sym)
                             sym
                             (symbol (name (ns-name *ns*)) (name sym)))
           intern?         (-> sym meta :intern)
           interned-symbol (cond
                             (string? intern?) (symbol (namespace fqsym) (str (name fqsym) intern?))
                             (symbol? intern?) intern?
                             :else fqsym)
           env-symbol      'fulcro-incoming-env
           action-blocks   (map (fn [{:keys [action-name action-args action-body]}]
                                  `(let [~(first action-args) ~env-symbol]
                                     {~(keyword (name action-name)) (fn [] ~@action-body)}))
                             actions)
           primary-action  (first (filter #(= 'action (:action-name %)) action-blocks))
           doc             (or doc "")
           remote-blocks   (map (fn [{:keys [remote-name remote-args remote-body]}]
                                  `(let [~(first remote-args) ~env-symbol]
                                     {~(keyword (name remote-name)) (do ~@remote-body)}))
                             remotes)
           multimethod     `(defmethod fulcro.client.mutations/mutate '~fqsym [~env-symbol ~'_ ~(first arglist)]
                              (merge
                                ~@action-blocks
                                ~@remote-blocks))]
       (if (and primary-action intern?)
         `(def ~interned-symbol ~doc
            (do
              ~multimethod
              (fn [~(first (:action-args primary-action)) ~(first arglist)]
                ~@(:action-body primary-action))))
         multimethod))))

#?(:clj
   (defmacro ^{:doc      "Define a Fulcro mutation.

                       The given symbol will be prefixed with the namespace of the current namespace, as if
                       it were def'd into the namespace.

                       The arglist should be the *parameter* arglist of the mutation, NOT the complete argument list
                       for the equivalent defmethod. For example:

                          (defmutation boo [{:keys [id]} ...) => (defmethod m/mutate *ns*/boo [{:keys [state ref]} _ {:keys [id]}] ...)

                       The mutation may include any combination of action and any number of remotes (by the remote name).

                       If `action` is supplied, it must be first.

                       (defmutation boo \"docstring\" [params-map]
                         (action [env] ...)
                         (my-remote [env] ...)
                         (other-remote [env] ...)
                         (remote [env] ...))

                       There is special support for placing the action as a var in the namespace. This support
                       only work when using a plain symbol. Simple add `:intern` metadata to the symbol. If
                       the metadata is true, it will intern the symbol as-is. It it is a string, it will suffix
                       the symbol with that string. If it is a symbol, it will use that symbol. The interned
                       symbol will act like the action side of the mutation, and has the signature:
                       `(fn [env params])`. This is also useful in devcards for using mkdn-pprint-source on mutations,
                       and should give you docstring and navigation support from nREPL.
                       "
               :arglists '([sym docstring? arglist action]
                            [sym docstring? arglist action remote]
                            [sym docstring? arglist remote])} defmutation
     [& args]
     (defmutation* &env args)))

;; Add methods to this to implement your local mutations
(defmulti mutate prim/dispatch)

;; Add methods to this to implement post mutation behavior (called after each mutation): WARNING: EXPERIMENTAL.
(defmulti post-mutate prim/dispatch)
(defmethod post-mutate :default [env k p] nil)

#?(:cljs
   (fulcro.client.mutations/defmutation set-props
     "
     mutation: A convenience helper, generally used 'bit twiddle' the data on a particular database table (using the component's ident).
     Specifically, merge the given `params` into the state of the database object at the component's ident.
     In general, it is recommended this be used for ui-only properties that have no real use outside of the component.
     "
     [params]
     (action [{:keys [state ref]}]
       (when (nil? ref) (log/error "ui/set-props requires component to have an ident."))
       (swap! state update-in ref (fn [st] (merge st params))))))

#?(:cljs
   (fulcro.client.mutations/defmutation toggle
     "mutation: A helper method that toggles the true/false nature of a component's state by ident.
      Use for local UI data only. Use your own mutations for things that have a good abstract meaning. "
     [{:keys [field]}]
     (action [{:keys [state ref]}]
       (when (nil? ref) (log/error "ui/toggle requires component to have an ident."))
       (swap! state update-in (conj ref field) not))))

(defmethod mutate :default [{:keys [target]} k _]
  (when (nil? target)
    (log/error "Unknown app state mutation. Have you required the file with your mutations?" k)))


(defn toggle!
  "Toggle the given boolean `field` on the specified component. It is recommended you use this function only on
  UI-related data (e.g. form checkbox checked status) and write clear top-level transactions for anything more complicated."
  [comp field]
  (prim/compressible-transact! comp `[(toggle {:field ~field})]))

(defn set-value!
  "Set a raw value on the given `field` of a `component`. It is recommended you use this function only on
  UI-related data (e.g. form inputs that are used by the UI, and not persisted data). Changes made via these
  helpers are compressed in the history."
  [component field value]
  (prim/compressible-transact! component `[(set-props ~{field value})]))

#?(:cljs
   (defn- ensure-integer
     "Helper for set-integer!, use that instead. It is recommended you use this function only on UI-related
     data (e.g. data that is used for display purposes) and write clear top-level transactions for anything else."
     [v]
     (let [rv (js/parseInt v)]
       (if (js/isNaN v) 0 rv)))
   :clj
   (defn- ensure-integer [v] (Integer/parseInt v)))

(defn target-value [evt] (.. evt -target -value))

(defn set-integer!
  "Set the given integer on the given `field` of a `component`. Allows same parameters as `set-string!`.

   It is recommended you use this function only on UI-related data (e.g. data that is used for display purposes)
   and write clear top-level transactions for anything else. Calls to this are compressed in history."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (ensure-integer (if event (target-value event) value))]
    (set-value! component field value)))

(defn set-string!
  "Set a string on the given `field` of a `component`. The string can be literal via named parameter `:value` or
  can be auto-extracted from a UI event using the named parameter `:event`

  Examples

  ```
  (set-string! this :ui/name :value \"Hello\") ; set from literal (or var)
  (set-string! this :ui/name :event evt) ; extract from UI event target value
  ```

  It is recommended you use this function only on UI-related
  data (e.g. data that is used for display purposes) and write clear top-level transactions for anything else.
  Calls to this are compressed in history."
  [component field & {:keys [event value]}]
  (assert (and (or event value) (not (and event value))) "Supply either :event or :value")
  (let [value (if event (target-value event) value)]
    (set-value! component field value)))

#?(:cljs
   (fulcro.client.mutations/defmutation set-query!
     "The mutation version of `prim/set-query!`. This version requires queryid as an input string.

     queryid (required) - A string query ID. Can be obtained via (prim/query-id Class qualifier)
     query - The new query
     params - The new query params

     One of query or params is required.
     "
     [{:keys [queryid query params]}]
     (action [{:keys [reconciler state]}]
       (swap! state prim/set-query* queryid {:query query :params params})
       (when reconciler
         (p/reindex! reconciler)))))

#?(:cljs
   (fulcro.client.mutations/defmutation merge!
     "The mutation version of prim/merge!"
     [{:keys [query data-tree remote]}]
     (action [{:keys [reconciler]}]
       (let [state (prim/app-state reconciler)
             {:keys [keys next]} (prim/merge* reconciler @state data-tree query)]
         (p/queue! reconciler keys remote)
         (reset! state next)
         (when-not (nil? remote)
           (p/reconcile! reconciler remote))))))

#?(:cljs
   (fulcro.client.mutations/defmutation send-history
     "Send the current app history to the server. The params can include anything and will be merged with a `:history` entry.
     Your server implementation of `fulcro.client.mutations/send-history` should record the data of history for
     retrieval by a root query for :support-request, which should at least include the stored :history and optionally a
     :comment from the user. You should add whatever identity makes sense for tracking."
     [params]
     (remote [{:keys [reconciler state ast]}]
       (let [history (-> reconciler (prim/get-history) deref)
             params  (assoc params :history history)]
         (assoc ast :params params)))))

(defn returning
  "Indicate the the remote operation will return a value of the given component type. The server-side mutation need
  simply return a tree matching that component's query and it will auto-merge into state. The ast param MUST be a query ast
  containing exactly one mutation that is *not* already a mutation join. The state is required for looking up dynamic queries, and
  may be nil if you use only static queries."
  [ast state class]
  {:pre [(symbol? (-> ast :key))]}
  (let [{:keys [key params query]} ast]
    (let [query' (cond-> (prim/get-query class state)
                   query (vary-meta #(merge (meta query) %)))]
      (prim/query->ast1 `[{(~key ~params) ~query'}]))))

(defn with-target
  "Set's a target for the return value from the mutation to be merged into. This can be combined with returning to define
  a path to insert the new entry."
  [ast target]
  {:pre [(symbol? (-> ast :key))]}
  (let [{:keys [key params query]} ast
        query' (if query
                 (vary-meta query assoc :fulcro.client.impl.data-fetch/target target)
                 (with-meta '[*] {:fulcro.client.impl.data-fetch/target target}))]
    (prim/query->ast1 `[{(~key ~params) ~query'}])))

(defn with-params
  "Modify an AST containing a single mutation, changing it's parameters to those given as an argument."
  [ast params]
  (assoc ast :params params))

(defn is-call? [expr]
  (and (list? expr)
    (symbol? (first expr))
    (or (= 1 (count expr))
      (map? (second expr)))))

(defn with-progressive-updates
  "Modifies the AST node to enable progressive updates (if available) about the response download progress.
  `progress-mutation` is a call expression (e.g. `(f {})`) for a mutation, which can include the normal parameter
  map. This mutation mutation will be triggered on each progress step. It will receive
  one call when the request is sent, followed by zero or more progress events from the low-level network layer,
  and one call when the request is done (with any status). The first and last calls are guaranteed.

  An extra parameter keyed at `fulcro.client.network/progress` will be included that contains a :progress key
  (:sending, :receiving, :complete, or :failed), and a status that will be dependent on the network implementation
  (e.g. a google XhrIO progress event)."
  [ast progress-mutation]
  {:pre [(symbol? (-> ast :key)) (is-call? progress-mutation)]}
  (update ast :key vary-meta assoc :fulcro.client.network/progress-mutation progress-mutation))

(defn progressive-update-transaction
  "Given a remote transaction containing one or more remote mutations, returns a local transaction of zero or
  more mutations that should be run to provide a progress update. The `progress` argument will be added to
  each resulting mutation in parameters as `:fulcro.client.network/progress`."
  [network-transaction progress]
  (let [add-progress (fn [expr]
                       (let [ast   (parser/expr->ast expr)
                             ast-2 (update ast :params assoc :fulcro.client.network/progress progress)]
                         (parser/ast->expr ast-2)))]
    (vec (keep
           (fn [m] (some-> m seq first meta :fulcro.client.network/progress-mutation add-progress))
           network-transaction))))

(defn with-abort-id
  "Modifies the mutation to enable network-level aborts. The id is a user-defined ID (any type) that identifies
  things that can be aborted on networking. IDs need not be unique per node, though aborting an ID that refers to
  more than one in-flight request will abort them all."
  [ast id]
  {:pre [(symbol? (-> ast :key))]}
  (update ast :key vary-meta assoc :fulcro.client.network/abort-id id))

(defn abort-ids
  "Returns a set of abort IDs from the given transaction."
  [tx]
  (set (keep
         (fn [m] (some-> m seq first meta :fulcro.client.network/abort-id))
         tx)))

(defn remove-ident*
  "Removes an ident, if it exists, from a list of idents in app state. This
  function is safe to use within mutations."
  [state-map ident path-to-idents]
  {:pre [(map? state-map)]}
  (let [new-list (fn [old-list]
                   (vec (filter #(not= ident %) old-list)))]
    (update-in state-map path-to-idents new-list)))

(defn integrate-ident*
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector."
  [state ident & named-parameters]
  {:pre [(map? state)]}
  (apply util/__integrate-ident-impl__ state ident named-parameters))
