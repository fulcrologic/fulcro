(ns fulcro.client.primitives
  #?(:cljs (:require-macros fulcro.client.primitives))
  (:refer-clojure :exclude #?(:clj [deftype replace var? force]
                              :cljs [var? key replace force]))
  (:require
    #?@(:clj  [clojure.main
               [cljs.core :refer [deftype specify! this-as js-arguments]]
               [clojure.reflect :as reflect]
               [cljs.util]]
        :cljs [[goog.string :as gstring]
               [cljsjs.react]
               [goog.object :as gobj]])
               fulcro-css.css
               [clojure.core.async :as async]
               [clojure.set :as set]
               [fulcro.history :as hist]
               [fulcro.logging :as log]
               [fulcro.tempid :as tempid]
               [fulcro.transit :as transit]
               [clojure.zip :as zip]
               [fulcro.client.impl.data-targeting :as targeting]
               [fulcro.client.impl.protocols :as p]
               [fulcro.client.impl.parser :as parser]
               [fulcro.util :as util]
               [clojure.walk :refer [prewalk]]
               [clojure.string :as str]
               [clojure.spec.alpha :as s]
    #?(:clj
               [clojure.future :refer :all])
               [cognitect.transit :as t])
  #?(:clj
     (:import [java.io Writer])))

(declare app-state app-root tempid? normalize-query focus-query* ast->query query->ast transact! remove-root! component?
  integrate-ident)

(defprotocol Ident
  (ident [this props] "Return the ident for this component"))

(defprotocol IQuery
  (query [this] "Return the component's unbound static query"))

(defprotocol InitialAppState
  (initial-state [clz params] "Get the initial state to be used for this component in app state. You are responsible for composing these together."))

(defn has-initial-app-state?
  #?(:cljs {:tag boolean})
  [component]
  #?(:clj  (if (fn? component)
             (some? (-> component meta :initial-state))
             (let [class (cond-> component (component? component) class)]
               (extends? InitialAppState class)))
     :cljs (implements? InitialAppState component)))

(defn has-ident?
  #?(:cljs {:tag boolean})
  [component]
  #?(:clj  (if (fn? component)
             (some? (-> component meta :ident))
             (let [class (cond-> component (component? component) class)]
               (extends? Ident class)))
     :cljs (implements? Ident component)))

(defn has-query?
  #?(:cljs {:tag boolean})
  [component]
  #?(:clj  (if (fn? component)
             (some? (-> component meta :query))
             (let [class (cond-> component (component? component) class)]
               (extends? IQuery class)))
     :cljs (implements? IQuery component)))

(defn get-initial-state
  "Get the initial state of a component. Needed because calling the protocol method from a defui component in clj will not work as expected."
  [class params]
  (some->
    #?(:clj  (when-let [initial-state (-> class meta :initial-state)]
               (initial-state class params))
       :cljs (when (implements? InitialAppState class)
               (initial-state class params)))
    (with-meta {:computed true})))

(defn computed-initial-state?
  "Returns true if the given initial state was computed from a call to get-initial-state."
  [s]
  (and (map? s) (some-> s meta :computed)))

(s/def ::remote keyword?)
(s/def ::ident (s/or :missing nil? :ident util/ident?))
(s/def ::query vector?)
(s/def ::transaction (s/every #(or (keyword? %) (util/mutation? %))
                       :kind vector?))
(s/def ::pessimistic? boolean?)
(s/def ::tempids (s/map-of tempid? any?))

(defn get-history
  "pass-through function for getting history, that enables testing (cannot mock protocols easily)"
  [reconciler]
  (when reconciler
    (p/get-history reconciler)))

(defn add-basis-time* [{:keys [children]} props time]
  (if (map? props)
    (if (seq children)
      (let [children (if (= :union (-> children first :type))
                       (apply concat (->> children first :children (map :children)))
                       children)]
        (-> (into props
              (comp
                (filter #(contains? props (:key %)))
                (map (fn [{:keys [key query] :as ast}]
                       (let [x   (get props key)
                             ast (cond-> ast

                                   (= '... query)
                                   (assoc :children children)

                                   (pos-int? query)
                                   (assoc :children (mapv #(cond-> %
                                                             (pos-int? (:query %))
                                                             (update :query dec))
                                                      children)))]
                         [key
                          (if (sequential? x)
                            (mapv #(add-basis-time* ast % time) x)
                            (add-basis-time* ast x time))]))))
              children)
          (vary-meta assoc ::time time)))
      (vary-meta props assoc ::time time))
    props))

(defn add-basis-time
  "Recursively add the given basis time to all of the maps in the props. This is part of the UI refresh optimization
  algorithm. Children that refresh in isolation could be mis-drawn if a parent subsequently does a re-render without
  a query (e.g. local state change). The basis times allow us to detect and avoid that."
  ([props time]
   (prewalk (fn [ele]
              (if (map? ele)
                (vary-meta ele assoc ::time time)
                ele)) props))
  ([q props time]
   (add-basis-time* (query->ast q) props time)))

(defn get-basis-time
  "Returns the basis time from the given props, or ::unset if not available."
  [props] (or (-> props meta ::time) :unset))

(defn get-current-time
  "get the current basis time from the reconciler. Used instead of calling the protocol method `basis-t` to facilitate testing."
  [reconciler] (p/basis-t reconciler))

(defn collect-statics
  "Collect the static declarations from the defui."
  [dt]
  (letfn [(split-on-static [forms]
            (split-with (complement '#{static}) forms))
          (split-on-symbol [forms]
            (split-with (complement symbol?) forms))]
    (loop [dt (seq dt) dt' [] statics {:fields {} :protocols []}]
      (if dt
        (let [[pre [_ sym & remaining :as post]] (split-on-static dt)
              dt' (into dt' pre)]
          (if (seq post)
            (cond
              (= sym 'field)
              (let [[field-info dt] (split-at 2 remaining)]
                (recur (seq dt) dt'
                  (update-in statics [:fields] conj (vec field-info))))
              (symbol? sym)
              (let [[protocol-info dt] (split-on-symbol remaining)]
                (recur (seq dt) dt'
                  (update-in statics [:protocols]
                    into (concat [sym] protocol-info))))
              :else (throw #?(:clj  (IllegalArgumentException. "Malformed static")
                              :cljs (js/Error. "Malformed static"))))
            (recur nil dt' statics)))
        {:dt dt' :statics statics}))))

(defn validate-statics [dt]
  (when-let [invalid (some #{"Ident" "IQuery"}
                       (map #(-> % str (str/split #"/") last)
                         (filter symbol? dt)))]
    (throw
      #?(:clj  (IllegalArgumentException.
                 (str invalid " protocol declaration must appear with `static`."))
         :cljs (js/Error.
                 (str invalid " protocol declaration must appear with `static`."))))))

(def lifecycle-sigs
  '{initLocalState                   [this]
    shouldComponentUpdate            [this next-props next-state]
    componentWillReceiveProps        [this next-props]
    componentWillUpdate              [this next-props next-state]
    componentDidUpdate               [this prev-props prev-state]
    componentWillMount               [this]
    componentDidMount                [this]
    componentWillUnmount             [this]
    render                           [this]
    ;; react 16
    componentDidCatch                [this error info]
    UNSAFE_componentWillReceiveProps [this next-props]
    UNSAFE_componentWillUpdate       [this next-props next-state]
    UNSAFE_componentWillMount        [this]
    getSnapshotBeforeUpdate          [this prev-props prev-state]
    ;; STATIC...no access to THIS
    getDerivedStateFromProps         [props state]})

(defn- validate-sig [[name sig :as method]]
  (let [required-signature (get lifecycle-sigs name)]
    (assert
      (<= (count required-signature) (count sig))           ; allow additional arg (context or snapshot)
      (str "Invalid signature for " name " got " sig ", need " required-signature))))

#?(:clj
   (def reshape-map-clj
     {:reshape
      {'render
       (fn [[name [this :as args] & body]]
         `(~name [this#]
            (let [~this this#]
              (binding [fulcro.client.primitives/*reconciler* (fulcro.client.primitives/get-reconciler this#)
                        fulcro.client.primitives/*depth*      (inc (fulcro.client.primitives/depth this#))
                        fulcro.client.primitives/*shared*     (fulcro.client.primitives/shared this#)
                        fulcro.client.primitives/*instrument* (fulcro.client.primitives/instrument this#)
                        fulcro.client.primitives/*parent*     this#]
                (let [ret#   (do ~@body)
                      props# (:props this#)]
                  (when-not @(:fulcro$mounted? props#)
                    (swap! (:fulcro$mounted? props#) not))
                  ret#)))))
       'componentDidMount
       (fn [[name [this :as args] & body]]
         `(~name [this#]
            (let [~this this#
                  reconciler# (fulcro.client.primitives/get-reconciler this#)
                  indexer# (get-in reconciler# [:config :indexer])]
              (when-not (nil? indexer#)
                (fulcro.client.impl.protocols/index-component! indexer# this#))
              ~@body)))}
      :defaults
      `{~'initLocalState
        ([this#])
        ~'componentWillMount
        ([this#]
          (let [reconciler# (fulcro.client.primitives/get-reconciler this#)
                lifecycle#  (get-in reconciler# [:config :lifecycle])
                indexer#    (get-in reconciler# [:config :indexer])]
            (when-not (nil? lifecycle#)
              (lifecycle# this# :mount))
            (when-not (nil? indexer#)
              (fulcro.client.impl.protocols/index-component! indexer# this#))))
        ~'render
        ([this#])}}))

(let [will-receive-props (fn [[name [this next-props :as args] & body]]
                           `(~name [this# next-props#]
                              (let [~this this#
                                    ~next-props (goog.object/get next-props# "fulcro$value")]
                                ~@body)))
      will-update        (fn [[name [this next-props next-state :as args] & body]]
                           `(~name [this# next-props# next-state#]
                              (let [~this this#
                                    ~next-props (goog.object/get next-props# "fulcro$value")
                                    ~next-state (goog.object/get next-state# "fulcro$state")]
                                ~@body)))]
  (def reshape-map
    {:reshape
     {'initLocalState
                                        (fn [[name [this :as args] & body]]
                                          `(~name ~args
                                             (let [ret# (do ~@body)]
                                               (cljs.core/js-obj "fulcro$state" ret#))))
      'componentWillReceiveProps        will-receive-props
      'UNSAFE_componentWillReceiveProps will-receive-props
      'componentWillUpdate              will-update
      'UNSAFE_componentWillUpdate       will-update
      'componentDidUpdate               (fn [[name [this prev-props prev-state & optional :as args] & body]]
                                          (let [snapshot-sym (if (symbol? (first optional)) (first optional) (gensym "snapshot"))]
                                            `(~name [this# prev-props# prev-state# snapshot#]
                                               (let [~this this#
                                                     ~snapshot-sym snapshot#
                                                     ~prev-props (goog.object/get prev-props# "fulcro$value")
                                                     ~prev-state (goog.object/get prev-state# "fulcro$state")]
                                                 (when (cljs.core/implements? fulcro.client.primitives/Ident this#)
                                                   (let [ident#      (fulcro.client.primitives/ident this# ~prev-props)
                                                         next-ident# (fulcro.client.primitives/ident this# (fulcro.client.primitives/props this#))]
                                                     (when (not= ident# next-ident#)
                                                       (let [idxr# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
                                                         (when-not (nil? idxr#)
                                                           (swap! (:indexes idxr#)
                                                             (fn [indexes#]
                                                               (-> indexes#
                                                                 (update-in [:ref->components ident#] disj this#)
                                                                 (update-in [:ref->components next-ident#] (fnil conj #{}) this#)))))))))
                                                 ~@body))))
      'componentDidMount                (fn [[name [this :as args] & body]]
                                          `(~name [this#]
                                             (let [~this this#
                                                   reconciler# (fulcro.client.primitives/get-reconciler this#)
                                                   lifecycle# (get-in reconciler# [:config :lifecycle])
                                                   indexer# (get-in reconciler# [:config :indexer])]
                                               (goog.object/set this# "fulcro$mounted" true)
                                               (when-not (nil? indexer#)
                                                 (fulcro.client.impl.protocols/index-component! indexer# this#))
                                               (when lifecycle#
                                                 (lifecycle# this# :mount))
                                               ~@body)))
      'componentWillUnmount             (fn [[name [this :as args] & body]]
                                          `(~name [this#]
                                             (let [~this this#
                                                   reconciler# (fulcro.client.primitives/get-reconciler this#)
                                                   lifecycle# (get-in reconciler# [:config :lifecycle])
                                                   cfg# (:config reconciler#)
                                                   st# (:state cfg#)
                                                   indexer# (:indexer cfg#)]
                                               (goog.object/set this# "fulcro$mounted" false)
                                               (when (and (not (nil? st#))
                                                       (get-in @st# [:fulcro.client.primitives/queries this#]))
                                                 (swap! st# update-in [:fulcro.client.primitives/queries] dissoc this#))
                                               (when lifecycle#
                                                 (lifecycle# this# :unmount))
                                               (when indexer#
                                                 (fulcro.client.impl.protocols/drop-component! indexer# this#))
                                               ~@body)))
      'shouldComponentUpdate            (fn [[name [this next-props next-state :as args] & body]]
                                          `(~name [this# next-props# next-state#]
                                             (let [~this this#
                                                   ~next-props (goog.object/get next-props# "fulcro$value")
                                                   ~next-state (goog.object/get next-state# "fulcro$state")]
                                               ~@body)))
      'getSnapshotBeforeUpdate          (fn [[name [this prev-props prev-state :as args] & body]]
                                          `(~name [this# prev-props# prev-state#]
                                             (let [~this this#
                                                   ~prev-props (goog.object/get prev-props# "fulcro$value")
                                                   ~prev-state (goog.object/get prev-state# "fulcro$state")]
                                               ~@body)))
      'render
                                        (fn [[name [this :as args] & body]]
                                          `(~name [this#]
                                             (let [~this this#]
                                               (binding [fulcro.client.primitives/*reconciler* (fulcro.client.primitives/get-reconciler this#)
                                                         fulcro.client.primitives/*depth*      (inc (fulcro.client.primitives/depth this#))
                                                         fulcro.client.primitives/*shared*     (fulcro.client.primitives/shared this#)
                                                         fulcro.client.primitives/*instrument* (fulcro.client.primitives/instrument this#)
                                                         fulcro.client.primitives/*parent*     this#]
                                                 ~@body))))}
     :defaults
     `{~'shouldComponentUpdate
       ;; Detect if props/state or children changed
       ([this# next-props# next-state#]
         (if fulcro.client.primitives/*blindly-render*
           true
           (let [next-children#     (. next-props# -children)
                 next-props#        (goog.object/get next-props# "fulcro$value")
                 current-props#     (fulcro.client.primitives/props this#)
                 props-changed?#    (not= current-props# next-props#)
                 next-state#        (goog.object/get next-state# "fulcro$state")
                 state-changed?#    (and (.. this# ~'-state)
                                      (not= (goog.object/get (. this# ~'-state) "fulcro$state")
                                        next-state#))
                 children-changed?# (not= (.. this# -props -children) next-children#)]
             (or props-changed?# state-changed?# children-changed?#))))

       ~'componentDidUpdate
       ;; Update index of component when its ident changes
       ([this# prev-props# prev-state#]
         (let [prev-props# (goog.object/get prev-props# "fulcro$value")]
           (when (cljs.core/implements? fulcro.client.primitives/Ident this#)
             (let [ident#      (fulcro.client.primitives/ident this# prev-props#)
                   next-ident# (fulcro.client.primitives/ident this# (fulcro.client.primitives/props this#))]
               (when (not= ident# next-ident#)
                 (let [idxr# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
                   (when-not (nil? idxr#)
                     (swap! (:indexes idxr#)
                       (fn [indexes#]
                         (-> indexes#
                           (update-in [:ref->components ident#] disj this#)
                           (update-in [:ref->components next-ident#] (fnil conj #{}) this#)))))))))))
       ~'componentDidMount
       ;; Add the component to the indexer when it mounts
       ([this#]
         (goog.object/set this# "fulcro$mounted" true)
         (let [indexer# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
           (when-not (nil? indexer#)
             (fulcro.client.impl.protocols/index-component! indexer# this#))))
       ~'componentWillUnmount
       ;; Remove the component from the indexer, and remove any dynamic queries for it
       ([this#]
         (let [r#       (fulcro.client.primitives/get-reconciler this#)
               cfg#     (:config r#)
               st#      (:state cfg#)
               indexer# (:indexer cfg#)]
           (goog.object/set this# "fulcro$mounted" false)
           ;; FIXME: WRONG...queries are not keyed by instance anymore (small possible memory leak)
           (when (and (not (nil? st#))
                   (get-in @st# [:fulcro.client.primitives/queries this#]))
             (swap! st# update-in [:fulcro.client.primitives/queries] dissoc this#))
           (when-not (nil? indexer#)
             (fulcro.client.impl.protocols/drop-component! indexer# this#))))}}))

(defn reshape [dt {:keys [reshape defaults]}]
  (letfn [(reshape* [x]
            (if (and (sequential? x)
                  (contains? reshape (first x)))
              (let [reshapef (get reshape (first x))]
                (validate-sig x)
                (reshapef x))
              x))
          (add-defaults-step [ret [name impl]]
            (if-not (some #{name} (map first (filter seq? ret)))
              (let [[before [p & after]] (split-with (complement '#{Object}) ret)]
                (into (conj (vec before) p (cons name impl)) after))
              ret))
          (add-defaults [dt]
            (reduce add-defaults-step dt defaults))
          (add-object-protocol [dt]
            (if-not (some '#{Object} dt)
              (conj dt 'Object)
              dt))]
    (->> dt (map reshape*) vec add-object-protocol add-defaults)))

#?(:clj (defn extract-static-methods [protocols]
          (letfn [(add-protocol-method [existing-methods method]
                    (let [nm              (first method)
                          new-arity       (rest method)
                          k               (keyword nm)
                          existing-method (get existing-methods k)]
                      (if existing-method
                        (let [single-arity?    (vector? (second existing-method))
                              existing-arities (if single-arity?
                                                 (list (rest existing-method))
                                                 (rest existing-method))]
                          (assoc existing-methods k (conj existing-arities new-arity 'fn)))
                        (assoc existing-methods k (list 'fn new-arity)))))]
            (when-not (empty? protocols)
              (let [result (->> protocols
                             (filter #(not (symbol? %)))
                             (reduce
                               (fn [r impl] (add-protocol-method r impl))
                               {}))]
                (if (contains? result :params)
                  result
                  (assoc result :params '(fn [this]))))))))

#?(:clj
   (defn defui*-clj [name forms]
     (let [docstring              (when (string? (first forms))
                                    (first forms))
           forms                  (cond-> forms
                                    docstring rest)
           {:keys [dt statics]} (collect-statics forms)
           [other-protocols obj-dt] (split-with (complement '#{Object}) dt)
           klass-name             (symbol (str name "_klass"))
           lifecycle-method-names (set (keys lifecycle-sigs))
           {obj-dt false non-lifecycle-dt true} (group-by
                                                  (fn [x]
                                                    (and (sequential? x)
                                                      (not (lifecycle-method-names (first x)))))
                                                  obj-dt)
           class-methods          (extract-static-methods (:protocols statics))]
       `(do
          ~(when-not (empty? non-lifecycle-dt)
             `(defprotocol ~(symbol (str name "_proto"))
                ~@(map (fn [[m-name args]] (list m-name args)) non-lifecycle-dt)))
          (declare ~name)
          (defrecord ~klass-name [~'state ~'refs ~'props ~'children]
            ;; TODO: non-lifecycle methods defined in the JS prototype - António
            fulcro.client.impl.protocols/IReactLifecycle
            ~@(rest (reshape obj-dt reshape-map-clj))

            ~@other-protocols

            ~@(:protocols statics)

            ~@(when-not (empty? non-lifecycle-dt)
                (list* (symbol (str name "_proto"))
                  non-lifecycle-dt))

            fulcro.client.impl.protocols/IReactComponent
            (~'-render [this#]
              (p/componentWillMount this#)
              (p/render this#)))
          (defmethod clojure.core/print-method ~(symbol (str (munge *ns*) "." klass-name))
            [o# ^Writer w#]
            (.write w# (str "#object[" (ns-name *ns*) "/" ~(str name) "]")))
          (let [c# (fn ~name [state# refs# props# children#]
                     (~(symbol (str (munge *ns*) "." klass-name ".")) state# refs# props# children#))]
            (def ~(with-meta name
                    (merge (meta name)
                      (when docstring
                        {:doc docstring})))
              (with-meta c#
                (merge {:component      c#
                        :component-ns   (ns-name *ns*)
                        :component-name ~(str name)}
                  ~class-methods))))))))

(defn defui*
  ([name form] (defui* name form nil))
  ([name forms env]
   (letfn [(field-set! [obj [field value]]
             `(set! (. ^js ~obj ~(symbol (str "-" field))) ~value))]
     (let [docstring        (when (string? (first forms))
                              (first forms))
           forms            (cond-> forms
                              docstring rest)
           {:keys [dt statics]} (collect-statics forms)
           _                (validate-statics dt)
           fqn              (if env
                              (symbol (-> env :ns :name str) (str name))
                              name)
           ctor             `(defn ~(with-meta name
                                      (merge {:jsdoc ["@constructor" "@nocollapse"]}
                                        (meta name)
                                        (when docstring
                                          {:doc docstring})))
                               []
                               (this-as this#
                                 (.apply js/React.Component this# (js-arguments))
                                 (if-not (nil? (.-initLocalState ^js this#))
                                   (set! (.-state this#) (.initLocalState ^js this#))
                                   (set! (.-state this#) (cljs.core/js-obj)))
                                 this#))
           set-react-proto! `(set! (.-prototype ~name)
                               (goog.object/clone js/React.Component.prototype))
           ctor             (if (-> name meta :once)
                              `(when-not (cljs.core/exists? ~name)
                                 ~ctor
                                 ~set-react-proto!)
                              `(do
                                 ~ctor
                                 ~set-react-proto!))
           display-name     (if env
                              (str (-> env :ns :name) "/" name)
                              'js/undefined)]
       `(do
          ~ctor
          (specify! (.-prototype ~name) ~@(reshape dt reshape-map))
          (set! (.. ~name -prototype -constructor) ~name)
          (set! (.. ~name -prototype -constructor -displayName) ~display-name)
          (set! (.-fulcro$isComponent ^js (.-prototype ^js ~name)) true)
          ~@(map #(field-set! name %) (:fields statics))
          (specify! ~name
            ~@(mapv #(cond-> %
                       (symbol? %) (vary-meta assoc :static true)) (:protocols statics)))
          (specify! (. ~name ~'-prototype) ~@(:protocols statics))
          (set! (.-cljs$lang$type ~name) true)
          (set! (.-cljs$lang$ctorStr ~name) ~(str fqn))
          (set! (.-cljs$lang$ctorPrWriter ~name)
            (fn [this# writer# opt#]
              (cljs.core/-write writer# ~(str fqn)))))))))

(defmacro defui [name & forms]
  (if (boolean (:ns &env))
    (defui* name forms &env)
    #?(:clj (defui*-clj name forms))))

(defmacro ui
  "Declare an anonymous UI component.  If the first argument is a keyword, then it is treated
  as the React version (defaults to :v15)."
  [& forms]
  (let [t (with-meta (gensym "ui_") {:anonymous true})]
    `(do (defui ~t ~@forms) ~t)))

;; =============================================================================
;; Globals & Dynamics

(def roots (atom {}))
(def ^{:dynamic true} *raf* nil)
(def ^{:dynamic true} *reconciler* nil)
(def ^{:dynamic true} *parent* nil)
(def ^{:dynamic true} *blindly-render* false)               ; when true: shouldComponentUpdate will return true even if their data/state hasn't changed
(def ^{:dynamic true} *shared* nil)
(def ^{:dynamic true} *instrument* nil)
(def ^{:dynamic true} *depth* 0)

#?(:clj
   (defn- munge-component-name [x]
     (let [ns-name (-> x meta :component-ns)
           cl-name (-> x meta :component-name)]
       (munge
         (str (str/replace (str ns-name) "." "$") "$" cl-name)))))

#?(:clj
   (defn- compute-react-key [cl props]
     (when-let [idx (-> props meta ::parser/data-path)]
       (str (munge-component-name cl) "_" idx))))

#?(:cljs
   (defn- compute-react-key [cl props]
     (if-let [rk (:react-key props)]
       rk
       (if-let [idx (-> props meta ::parser/data-path)]
         (str (. cl -name) "_" idx)
         js/undefined))))


(defn component?
  "Returns true if the argument is a component."
  #?(:cljs {:tag boolean})
  [x]
  (if-not (nil? x)
    #?(:clj  (or (instance? fulcro.client.impl.protocols.IReactComponent x)
               (satisfies? p/IReactComponent x))
       :cljs (true? (.-fulcro$isComponent ^js x)))
    false))

#?(:clj
   (defn react-type
     "Returns the component type, regardless of whether the component has been
      mounted"
     [component]
     {:pre [(component? component)]}
     (let [[klass-name] (str/split (reflect/typename (type component)) #"_klass")
           last-idx-dot (.lastIndexOf klass-name ".")
           ns           (clojure.main/demunge (subs klass-name 0 last-idx-dot))
           c            (subs klass-name (inc last-idx-dot))]
       @(or (find-var (symbol ns c))
          (find-var (symbol ns (clojure.main/demunge c)))))))

#?(:cljs
   (defn react-type
     "Returns the component type, regardless of whether the component has been
      mounted"
     [x]
     (or (gobj/get x "type") (type x))))

(defn- state [c]
  {:pre [(component? c)]}
  (.-state c))

(defn- get-raw-react-prop
  "PRIVATE: Do not use. GET a RAW react prop"
  [c k]
  #?(:clj  (get (:props c) k)
     :cljs (gobj/get (.-props c) k)))

(defn reconciler?
  "Returns true if x is a reconciler."
  #?(:cljs {:tag boolean})
  [x]
  #?(:cljs (implements? p/IReconciler x)
     :clj  (or (instance? p/IReconciler x)
             (satisfies? p/IReconciler x))))

(defn get-indexer
  "PRIVATE: Get the indexer associated with the reconciler."
  [reconciler]
  {:pre [(reconciler? reconciler)]}
  (-> reconciler :config :indexer))

(defn- sift-idents [res]
  (let [{idents true rest false} (group-by #(vector? (first %)) res)]
    [(into {} idents) (into {} rest)]))

(defn get-reconciler
  [c]
  {:pre [(component? c)]}
  (get-raw-react-prop c #?(:clj  :fulcro$reconciler
                           :cljs "fulcro$reconciler")))

(defn- parent
  "Returns the parent component."
  [component]
  (get-raw-react-prop component #?(:clj  :fulcro$parent
                                   :cljs "fulcro$parent")))

(defn depth
  "PRIVATE: Returns the render depth (a integer) of the component relative to
   the mount root."
  [component]
  (when (component? component)
    (get-raw-react-prop component #?(:clj  :fulcro$depth
                                     :cljs "fulcro$depth"))))

(defn react-key
  "Returns the components React key."
  [component]
  (get-raw-react-prop component #?(:clj  :fulcro$reactKey
                                   :cljs "fulcro$reactKey")))

#?(:clj
   (defn props [component]
     {:pre [(component? component)]}
     (:fulcro$value (:props component))))

#?(:cljs
   (defn props
     "Return a components props."
     [component]
     {:pre [(component? component)]}
     (gobj/get (.-props component) "fulcro$value")))

#?(:clj
   (defn init-local-state [component]
     (reset! (:state component) (.initLocalState component))))

(defn get-state
  "Get a component's local state. May provide a single key or a sequential
   collection of keys for indexed access into the component's local state."
  ([component]
   (get-state component []))
  ([component k-or-ks]
   {:pre [(component? component)]}
   (let [cst #?(:clj @(:state component)
                :cljs (when-let [state (. component -state)] (gobj/get state "fulcro$state")))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(defn- get-static-query
  "Get the statically-declared query of IQuery from a given class."
  [c]
  {:pre (has-query? c)}
  #?(:clj ((-> c meta :query) c) :cljs (query c)))

(defn some-hasquery?
  "Returns true if the given component or one of its parents has a query."
  [c]
  (loop [c c]
    (cond
      (nil? c) false
      (has-query? c) true
      :else (recur (parent c)))))

(defn get-ident
  "Get the ident for a mounted component OR using a component class.

  That arity-2 will return the ident using the supplied props map.

  The single-arity version should only be used with a mounted component (e.g. `this` from `render`), and will derive the
  props that were sent to it most recently."
  ([x]
   {:pre [(component? x)]}
   (if-let [m (props x)]
     (ident x m)
     (log/warn "get-ident was invoked on component with nil props (this could mean it wasn't yet mounted): " x)))
  ([class props]
    #?(:clj  (if-let [ident (if (component? class)
                              ident
                              (-> class meta :ident))]
               (let [id (ident class props)]
                 (if-not (util/ident? id)
                   (log/warn "get-ident returned an invalid ident for class:" class))
                 (if (= ::not-found (second id)) [(first id) nil] id))
               (log/warn "get-ident called with something that is either not a class or does not implement ident: " class))
       :cljs (if (implements? Ident class)
               (let [id (ident class props)]
                 (if-not (util/ident? id)
                   (log/warn "get-ident returned an invalid ident for class:" class))
                 (if (= ::not-found (second id)) [(first id) nil] id))
               (log/warn "get-ident called with something that is either not a class or does not implement ident: " class)))))

(defn component-name
  "Returns a string version of the given react component's name."
  [class]
  #?(:clj  (str (-> class meta :component-ns) "/" (-> class meta :component-name))
     :cljs (.-displayName class)))

(defn query-id
  "Returns a string ID for the query of the given class with qualifier"
  [class qualifier]
  (if (nil? class)
    (log/error "Query ID received no class (if you see this warning, it probably means metadata was lost on your query)" (ex-info "" {}))
    (when-let [classname (component-name class)]
      (str classname (when qualifier (str "$" qualifier))))))

#?(:clj
   (defn- is-element? [e]
     (or
       (instance? fulcro.client.impl.protocols.IReactComponent e)
       (instance? fulcro.client.impl.protocols.IReactDOMElement e)
       (satisfies? p/IReactComponent e)
       (satisfies? p/IReactDOMElement e))))

#?(:clj
   (defn fragment [& args]
     (let [children (if (is-element? (first args)) args (rest args))]
       (vec children))))

#?(:cljs
   (defn fragment
     "Wraps children in a React.Fragment. Props are optional, like normal DOM elements."
     [& args]
     (let [[props children] (if (map? (first args))
                              [(first args) (rest args)]
                              [#js {} args])]
       (apply js/React.createElement js/React.Fragment (clj->js props) children))))

#?(:clj
   (defn factory
     "Create a factory constructor from a component class created with
      fulcro.client.primitives/defui."
     ([class]
      (factory class nil))
     ([class {:keys [validator keyfn instrument? qualifier]
              :or   {instrument? true} :as opts}]
      {:pre [(fn? class)]}
      (with-meta
        (fn element-factory
          ([] (element-factory nil))
          ([props & children]
           (when-not (nil? validator)
             (assert (validator props)))
           (if (and *instrument* (var-get *instrument*) instrument?)
             (*instrument*
               {:props    props
                :children children
                :class    class
                :factory  (factory class (assoc opts :instrument? false))})
             (let [react-key (cond
                               (some? keyfn) (keyfn props)
                               (some? (:react-key props)) (:react-key props)
                               :else (compute-react-key class props))
                   ctor      class
                   ref       (:ref props)
                   props     {:fulcro$reactRef   ref
                              :fulcro$reactKey   react-key
                              :fulcro$value      (cond-> props
                                                   (map? props) (dissoc :ref))
                              :fulcro$queryid    (query-id class qualifier)
                              :fulcro$mounted?   (atom false)
                              :fulcro$reconciler *reconciler*
                              :fulcro$parent     *parent*
                              :fulcro$shared     *shared*
                              :fulcro$instrument *instrument*
                              :fulcro$depth      *depth*}
                   component (ctor (atom nil) (atom nil) props children)]
               (when ref
                 (assert (some? *parent*))
                 (swap! (:refs *parent*) assoc ref component))
               (init-local-state component)
               component))))
        {:class     class
         :queryid   (query-id class qualifier)
         :qualifier qualifier}))))

#?(:cljs
   (defn create-element [class props children]
     (apply js/React.createElement class props children)))

#?(:cljs
   (defn factory
     "Create a factory constructor from a component class created with
      defui."
     ([class] (factory class nil))
     ([class {:keys [validator keyfn instrument? qualifier]
              :or   {instrument? true} :as opts}]
      {:pre [(fn? class)]}
      (with-meta
        (fn element-factory [props & children]
          (when-not (nil? validator)
            (assert (validator props)))
          (if (and *instrument* instrument?)
            (*instrument*
              {:props    props
               :children children
               :class    class
               :factory  (factory class (assoc opts :instrument? false))})
            (let [key (if-not (nil? keyfn)
                        (keyfn props)
                        (compute-react-key class props))
                  ref (:ref props)
                  ref (cond-> ref (keyword? ref) str)]
              (create-element class
                #js {:key               key
                     :ref               ref
                     :fulcro$reactKey   key
                     :fulcro$value      props
                     :fulcro$queryid    (query-id class qualifier)
                     :fulcro$reconciler *reconciler*
                     :fulcro$parent     *parent*
                     :fulcro$shared     *shared*
                     :fulcro$instrument *instrument*
                     :fulcro$depth      *depth*}
                (or (util/force-children children) [])))))
        {:class     class
         :queryid   (query-id class qualifier)
         :qualifier qualifier}))))

(defn computed-factory
  "Similar to factory, but returns a function with the signature
  [props computed] instead of default [props & children]. This makes easier to send
  computed but will not accept children params."
  ([class] (computed-factory class {}))
  ([class options]
   (let [factory (factory class options)]
     (fn real-factory
       ([props] (real-factory props {}))
       ([props computed]
        (factory (computed props computed)))))))

(defn denormalize-query
  "Takes a state map that may contain normalized queries and a query ID. Returns the stored query or nil."
  [state-map ID]
  (let [get-stored-query (fn [id] (get-in state-map [::queries id :query]))]
    (when-let [normalized-query (get-stored-query ID)]
      (prewalk (fn [ele]
                 (if-let [q (and (string? ele) (get-stored-query ele))]
                   q
                   ele)) normalized-query))))

(defn get-query-by-id [state-map class queryid]
  (let [query (or (denormalize-query state-map queryid) (get-static-query class))]
    (with-meta query {:component class
                      :queryid   queryid})))

(defn is-factory?
  [class-or-factory]
  (and (fn? class-or-factory)
    (-> class-or-factory meta (contains? :qualifier))))

(def ^{:dynamic true :private true} *query-state* {})

(defn- get-query-id
  "Get the query id that is cached in the component's props."
  [component]
  {:pre [(component? component)]}
  (get-raw-react-prop component #?(:clj  :fulcro$queryid
                                   :cljs "fulcro$queryid")))

(defn get-query
  "Get the query for the given class or factory. If called without a state map, then you'll get the declared static
  query of the class. If a state map is supplied, then the dynamically set queries in that state will result in
  the current dynamically-set query according to that state."
  ([class-or-factory] (get-query class-or-factory *query-state*))
  ([class-or-factory state-map]
   (binding [*query-state* state-map]
     (let [class     (cond
                       (is-factory? class-or-factory) (-> class-or-factory meta :class)
                       (component? class-or-factory) (react-type class-or-factory)
                       :else class-or-factory)
           qualifier (if (is-factory? class-or-factory)
                       (-> class-or-factory meta :qualifier)
                       nil)
           queryid   (if (component? class-or-factory)
                       (get-query-id class-or-factory)
                       (query-id class qualifier))]
       (when (and class (has-query? class))
         (get-query-by-id state-map class queryid))))))


(defn link-element [element]
  (prewalk (fn link-element-helper [ele]
             (let [{:keys [queryid]} (meta ele)]
               (if queryid queryid ele))) element))

(defn deep-merge [& xs]
  "Merges nested maps without overwriting existing keys."
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn normalize-query-elements
  "Determines if there are query elements in the present query that need to be normalized as well. If so, it does so.
  Returns the new state map."
  [state-map query]
  (reduce (fn normalize-query-elements-reducer [state ele]
            (let [parameterized? (seq? ele)                 ; not using list? because it could show up as a lazyseq
                  raw-element    (if parameterized? (first ele) ele)]
              (cond
                (util/union? raw-element) (let [union-alternates            (first (vals raw-element))
                                                normalized-union-alternates (into {} (map link-element union-alternates))
                                                union-query-id              (-> union-alternates meta :queryid)]
                                            (assert union-query-id "Union query has an ID. Did you use extended get-query?")
                                            (deep-merge
                                              {::queries {union-query-id {:query normalized-union-alternates
                                                                          :id    union-query-id}}}
                                              (reduce (fn normalize-union-reducer [s [_ subquery]]
                                                        (normalize-query s subquery)) state union-alternates)))
                (util/join? raw-element) (normalize-query state (util/join-value raw-element))
                :else state)))
    state-map query))

(defn link-query
  "Find all of the elements (only at the top level) of the given query and replace them
  with their query ID"
  [query]
  (mapv link-element query))

(defn normalize-query
  "Given a state map and a query, returns a state map with the query normalized into the database. Query fragments
  that already appear in the state will not be added. "
  [state-map query]
  (let [new-state (normalize-query-elements state-map query)
        new-state (if (nil? (::queries new-state))
                    (assoc new-state ::queries {})
                    new-state)
        top-query (link-query query)]
    (if-let [queryid (some-> query meta :queryid)]
      (deep-merge {::queries {queryid {:query top-query :id queryid}}} new-state)
      new-state)))

(defn set-query*
  "Put a query in app state.
  NOTE: Indexes must be rebuilt after setting a query, so this function should primarily be used to build
  up an initial app state."
  [state-map ui-factory-class-or-queryid {:keys [query] :as args}]
  (let [queryid (cond
                  (nil? ui-factory-class-or-queryid) nil
                  (string? ui-factory-class-or-queryid) ui-factory-class-or-queryid
                  (some-> ui-factory-class-or-queryid meta (contains? :queryid)) (some-> ui-factory-class-or-queryid meta :queryid)
                  :otherwise (query-id ui-factory-class-or-queryid nil))
        setq*   (fn [state] (normalize-query (update state ::queries dissoc queryid) (with-meta query {:queryid queryid})))]
    (if (string? queryid)
      (cond-> state-map
        (contains? args :query) (setq*))
      (do
        (log/error "Set query failed. There was no query ID.")
        state-map))))

(defn gather-keys
  "Gather the keys that would be considered part of the refresh set for the given query.

  E.g. [:a {:j [:b]} {:u {:x [:l] :y [:k]}}] ==> #{:a :j :u}"
  [query]
  (cond
    (vector? query) (reduce (fn gather-keys-reducer [rv e]
                              (cond
                                (keyword? e) (conj rv e)
                                (and (util/ident? e) (= '_ (second e))) (conj rv (first e))
                                (and (list? e) (keyword? (first e))) (conj rv (first e))
                                (and (util/join? e) (util/ident? (util/join-key e)) (= '_ (-> e util/join-key second))) (conj rv (first (util/join-key e)))
                                (and (util/join? e) (keyword? (util/join-key e))) (conj rv (util/join-key e))
                                :else rv))
                      #{} query)
    (map? query) (-> query keys set)                        ; a union component, which has a map for a query
    :else #{}))

(defn- normalize* [query data refs union-seen]
  (cond
    (= '[*] query) data

    ;; union case
    (map? query)
    (let [class         (-> query meta :component)
          ident #?(:clj (when-let [ident (-> class meta :ident)]
                          (ident class data))
                   :cljs (when (implements? Ident class)
                           (get-ident class data)))]
      (if-not (nil? ident)
        (vary-meta (normalize* (get query (first ident)) data refs union-seen)
          assoc ::tag (first ident))                        ; FIXME: What is tag for?
        (throw #?(:clj  (IllegalArgumentException. "Union components must implement Ident")
                  :cljs (js/Error. "Union components must implement Ident")))))

    (vector? data) data                                     ;; already normalized

    :else
    (loop [q (seq query) ret data]
      (if-not (nil? q)
        (let [expr (first q)]
          (if (util/join? expr)
            (let [[k sel] (util/join-entry expr)
                  recursive?  (util/recursion? sel)
                  union-entry (if (util/union? expr) sel union-seen)
                  sel         (if recursive?
                                (if-not (nil? union-seen)
                                  union-seen
                                  query)
                                sel)
                  class       (-> sel meta :component)
                  v           (get data k)]
              (cond
                ;; graph loop: db->tree leaves ident in place
                (and recursive? (util/ident? v)) (recur (next q) ret)
                ;; normalize one
                (map? v)
                (let [x (normalize* sel v refs union-entry)]
                  (if-not (or (nil? class) (not #?(:clj  (-> class meta :ident)
                                                   :cljs (implements? Ident class))))
                    (let [i #?(:clj ((-> class meta :ident) class v)
                               :cljs (get-ident class v))]
                      (swap! refs update-in [(first i) (second i)] merge x)
                      (recur (next q) (assoc ret k i)))
                    (recur (next q) (assoc ret k x))))

                ;; normalize many
                (vector? v)
                (let [xs (into [] (map #(normalize* sel % refs union-entry)) v)]
                  (if-not (or (nil? class) (not #?(:clj  (-> class meta :ident)
                                                   :cljs (implements? Ident class))))
                    (let [is (into [] (map #?(:clj  #((-> class meta :ident) class %)
                                              :cljs #(get-ident class %))) xs)]
                      (if (vector? sel)
                        (when-not (empty? is)
                          (swap! refs
                            (fn [refs]
                              (reduce (fn [m [i x]]
                                        (update-in m i merge x))
                                refs (zipmap is xs)))))
                        ;; union case
                        (swap! refs
                          (fn [refs']
                            (reduce
                              (fn [ret [i x]]
                                (update-in ret i merge x))
                              refs' (map vector is xs)))))
                      (recur (next q) (assoc ret k is)))
                    (recur (next q) (assoc ret k xs))))

                ;; missing key
                (nil? v)
                (recur (next q) ret)

                ;; can't handle
                :else (recur (next q) (assoc ret k v))))
            (let [k (if (seq? expr) (first expr) expr)
                  v (get data k)]
              (if (nil? v)
                (recur (next q) ret)
                (recur (next q) (assoc ret k v))))))
        ret))))

(defn tree->db
  "Given a component class or instance and a tree of data, use the component's
   query to transform the tree into the default database format. All nodes that
   can be mapped via Ident implementations wil be replaced with ident links. The
   original node data will be moved into tables indexed by ident. If merge-idents
   option is true, will return these tables in the result instead of as metadata."
  ([x data]
   (tree->db x data false))
  ([x data #?(:clj merge-idents :cljs ^boolean merge-idents)]
   (let [refs (atom {})
         x    (if (vector? x) x (get-query x data))
         ret  (normalize* x data refs nil)]
     (if merge-idents
       (let [refs' @refs] (merge ret refs'))
       (with-meta ret @refs)))))


(defn- focused-join [expr ks full-expr union-expr]
  (let [expr-meta (meta expr)
        expr'     (cond
                    (map? expr)
                    (let [join-value (-> expr first second)
                          join-value (if (and (util/recursion? join-value)
                                           (seq ks))
                                       (if-not (nil? union-expr)
                                         union-expr
                                         full-expr)
                                       join-value)]
                      {(ffirst expr) (focus-query* join-value ks nil)})

                    (seq? expr) (list (focused-join (first expr) ks nil nil) (second expr))
                    :else expr)]
    (cond-> expr'
      (some? expr-meta) (with-meta expr-meta))))

(defn- focus-query*
  [query path union-expr]
  (if (empty? path)
    query
    (let [[k & ks] path]
      (letfn [(match [x]
                (= k (util/join-key x)))
              (value [x]
                (focused-join x ks query union-expr))]
        (if (map? query)                                    ;; UNION
          {k (focus-query* (get query k) ks query)}
          (into [] (comp (filter match) (map value) (take 1)) query))))))

(defn focus-query
  "Given a query, focus it along the specified path.

  Examples:
    (focus-query [:foo :bar :baz] [:foo])
    => [:foo]

    (fulcro.client.primitives/focus-query [{:foo [:bar :baz]} :woz] [:foo :bar])
    => [{:foo [:bar]}]"
  [query path]
  (focus-query* query path nil))

(declare focus-subquery*)

(defn- focus-subquery-union*
  [query-ast sub-ast]
  (let [s-index (into {} (map #(vector (:union-key %) %)) (:children sub-ast))]
    (assoc query-ast
      :children
      (reduce
        (fn [children {:keys [union-key] :as union-entry}]
          (if-let [sub (get s-index union-key)]
            (conj children (focus-subquery* union-entry sub))
            (conj children union-entry)))
        []
        (:children query-ast)))))

(defn- focus-subquery*
  [query-ast sub-ast]
  (let [q-index (into {} (map #(vector (:key %) %)) (:children query-ast))]
    (assoc query-ast
      :children
      (reduce
        (fn [children {:keys [key type] :as focus}]
          (if-let [source (get q-index key)]
            (cond
              (= :join type (:type source))
              (conj children (focus-subquery* source focus))

              (= :union type (:type source))
              (conj children (focus-subquery-union* source focus))

              :else
              (conj children source))
            children))
        []
        (:children sub-ast)))))

(defn focus-subquery
  "Given a query, focus it along the specified query expression.

  Examples:
    (focus-query [:foo :bar :baz] [:foo])
    => [:foo]

    (fulcro.client.primitives/focus-query [{:foo [:bar :baz]} :woz] [{:foo [:bar]} :woz])
    => [{:foo [:bar]} :woz]"
  [query sub-query]
  (let [query-ast (query->ast query)
        sub-ast   (query->ast sub-query)]
    (ast->query (focus-subquery* query-ast sub-ast))))

(defn- expr->key
  "Given a query expression return its key."
  [expr]
  (cond
    (keyword? expr) expr
    (map? expr) (ffirst expr)
    (seq? expr) (let [expr' (first expr)]
                  (when (map? expr')
                    (ffirst expr')))
    (util/ident? expr) (cond-> expr (= '_ (second expr)) first)
    :else
    (throw
      (ex-info (str "Invalid query expr " expr)
        {:type :error/invalid-expression}))))


(defn- query-zip
  "Return a zipper on a query expression."
  [root]
  (zip/zipper
    #(or (vector? %) (map? %) (seq? %))
    seq
    (fn [node children]
      (let [ret (cond
                  (vector? node) (vec children)
                  (map? node) (into {} children)
                  (seq? node) children)]
        (with-meta ret (meta node))))
    root))

(defn- move-to-key
  "Move from the current zipper location to the specified key. loc must be a
   hash map node."
  [loc k]
  (loop [loc (zip/down loc)]
    (let [node (zip/node loc)]
      (if (= k (first node))
        (-> loc zip/down zip/right)
        (recur (zip/right loc))))))

(defn- query-template
  "Given a query and a path into a query return a zipper focused at the location
   specified by the path. This location can be replaced to customize / alter
   the query."
  [query path]
  (letfn [(query-template* [loc path]
            (if (empty? path)
              loc
              (let [node (zip/node loc)]
                (if (vector? node)                          ;; SUBQUERY
                  (recur (zip/down loc) path)
                  (let [[k & ks] path
                        k' (expr->key node)]
                    (if (= k k')
                      (if (or (map? node)
                            (and (seq? node) (map? (first node))))
                        (let [loc'  (move-to-key (cond-> loc (seq? node) zip/down) k)
                              node' (zip/node loc')]
                          (if (map? node')                  ;; UNION
                            (if (seq ks)
                              (recur
                                (zip/replace loc'
                                  (zip/node (move-to-key loc' (first ks))))
                                (next ks))
                              loc')
                            (recur loc' ks)))               ;; JOIN
                        (recur (-> loc zip/down zip/down zip/down zip/right) ks)) ;; CALL
                      (recur (zip/right loc) path)))))))]
    (query-template* (query-zip query) path)))

(defn- replace [template new-query]
  (-> template (zip/replace new-query) zip/root))

(defn reduce-query-depth
  "Changes a join on key k with depth limit from [:a {:k n}] to [:a {:k (dec n)}]"
  [q k]
  (if-not (empty? (focus-query q [k]))
    (let [pos   (query-template q [k])
          node  (zip/node pos)
          node' (cond-> node (number? node) dec)]
      (replace pos node'))
    q))

(defn- reduce-union-recursion-depth
  "Given a union expression decrement each of the query roots by one if it
   is recursive."
  [union-expr recursion-key]
  (->> union-expr
    (map (fn [[k q]] [k (reduce-query-depth q recursion-key)]))
    (into {})))

(defn- mappable-ident? [refs ident]
  (and (util/ident? ident)
    (contains? refs (first ident))))

(defn- denormalize*
  "Denormalize a data based on query. refs is a data structure which maps idents
   to their values. map-ident is a function taking a ident to another ident,
   used during tempid transition. idents-seen is the set of idents encountered,
   used to limit recursion. union-expr is the current union expression being
   evaluated. recurse-key is key representing the current recursive query being
   evaluted."
  [query data refs map-ident idents-seen union-expr recurse-key]
  ;; support taking ident for data param
  (let [union-recur? (and union-expr recurse-key)
        recur-ident  (when union-recur?
                       data)
        data         (loop [data data]
                       (if (mappable-ident? refs data)
                         (recur (get-in refs (map-ident data)))
                         data))]
    (cond
      (vector? data)
      ;; join
      (let [step (fn [ident]
                   (if-not (mappable-ident? refs ident)
                     (if (= query '[*])
                       ident
                       (let [{props false joins true} (group-by util/join? query)
                             props (mapv #(cond-> % (seq? %) first) props)]
                         (loop [joins (seq joins) ret {}]
                           (if-not (nil? joins)
                             (let [join (first joins)
                                   [key sel] (util/join-entry join)
                                   v    (get ident key)]
                               (recur (next joins)
                                 (assoc ret
                                   key (denormalize* sel v refs map-ident
                                         idents-seen union-expr recurse-key))))
                             (merge (select-keys ident props) ret)))))
                     (let [ident'      (get-in refs (map-ident ident))
                           query       (cond-> query
                                         union-recur? (reduce-union-recursion-depth recurse-key))
                           ;; also reduce query depth of union-seen, there can
                           ;; be more union recursions inside
                           union-seen' (cond-> union-expr
                                         union-recur? (reduce-union-recursion-depth recurse-key))
                           query'      (cond-> query
                                         (map? query) (get (first ident)))] ;; UNION
                       (denormalize* query' ident' refs map-ident idents-seen union-seen' nil))))]
        (into [] (map step) data))

      (and (map? query) union-recur?)
      (denormalize* (get query (first recur-ident)) data refs map-ident
        idents-seen union-expr recurse-key)

      :else
      ;; map case
      (if (= '[*] query)
        data
        (let [{props false joins true} (group-by #(or (util/join? %) (util/ident? %)
                                                    (and (seq? %) (util/ident? (first %))))
                                         query)
              props (mapv #(cond-> % (seq? %) first) props)]
          (loop [joins (seq joins) ret {}]
            (if-not (nil? joins)
              (let [join        (first joins)
                    join        (cond-> join
                                  (seq? join) first)
                    join        (cond-> join
                                  (util/ident? join) (hash-map '[*]))
                    [key sel] (util/join-entry join)
                    recurse?    (util/recursion? sel)
                    recurse-key (when recurse? key)
                    v           (if (util/ident? key)
                                  (if (= '_ (second key))
                                    (get refs (first key))
                                    (get-in refs (map-ident key)))
                                  (get data key))
                    key         (cond-> key (util/unique-ident? key) first)
                    v           (if (mappable-ident? refs v)
                                  (loop [v v]
                                    (let [next (get-in refs (map-ident v))]
                                      (if (mappable-ident? refs next)
                                        (recur next)
                                        (map-ident v))))
                                  v)
                    limit       (if (number? sel) sel :none)
                    union-entry (if (util/union? join)
                                  sel
                                  (when recurse?
                                    union-expr))
                    sel         (cond
                                  recurse?
                                  (if-not (nil? union-expr)
                                    union-entry
                                    (reduce-query-depth query key))

                                  (and (mappable-ident? refs v)
                                    (util/union? join))
                                  (get sel (first v))

                                  (and (util/ident? key)
                                    (util/union? join))
                                  (get sel (first key))

                                  :else sel)
                    graph-loop? (and recurse?
                                  (contains? (set (get idents-seen key)) v)
                                  (= :none limit))
                    idents-seen (if (and (mappable-ident? refs v) recurse?)
                                  (-> idents-seen
                                    (update-in [key] (fnil conj #{}) v)
                                    (assoc-in [:last-ident key] v)) idents-seen)]
                (cond
                  (= 0 limit) (recur (next joins) ret)
                  graph-loop? (recur (next joins) ret)
                  (nil? v) (recur (next joins) ret)
                  :else (recur (next joins)
                          (assoc ret
                            key (denormalize* sel v refs map-ident
                                  idents-seen union-entry recurse-key)))))
              (if-let [looped-key (some
                                    (fn [[k identset]]
                                      (if (contains? identset (get data k))
                                        (get-in idents-seen [:last-ident k])
                                        nil))
                                    (dissoc idents-seen :last-ident))]
                looped-key
                (merge (select-keys data props) ret)))))))))

(defn db->tree
  "Given a query, some data in the default database format, and the entire
   application state in the default database format, return the tree where all
   ident links have been replaced with their original node values."
  ([query data refs]
   {:pre [(map? refs)]}
   (denormalize* query data refs identity {} nil nil))
  ([query data refs map-ident]
   {:pre [(map? refs)]}
   (denormalize* query data refs map-ident {} nil nil)))


(defn ref->any
  "Get any component from the indexer that matches the ref."
  [x ref]
  (let [indexer (if (reconciler? x) (get-indexer x) x)]
    (first (p/key->components indexer ref))))

(defn resolve-tempids
  "Replaces all tempids in app-state with the ids returned by the server."
  [state tid->rid]
  (if (empty? tid->rid)
    state
    (prewalk #(if (tempid? %) (get tid->rid % %) %) state)))

(defn rewrite-tempids-in-request-queue
  "Rewrite any pending requests in the request queue to account for the fact that a response might have
  changed ids that are expressed in the mutations of that queue. tempid-map MUST be a map from om
  tempid to real ids, not idents."
  [queue tempid-map]
  (loop [entry (async/poll! queue) entries []]
    (cond
      entry (recur (async/poll! queue) (conj entries (resolve-tempids entry tempid-map)))
      (seq entries) (doseq [e entries] (when-not (async/offer! queue e)
                                         (log/error "Unable to put request back on network queue during tempid rewrite!"))))))


(defn remove-loads-and-fallbacks
  "Removes all fulcro/load and tx/fallback mutations from the query"
  [query]
  (let [symbols-to-filter #{'fulcro/load `fulcro.client.data-fetch/load 'tx/fallback `fulcro.client.data-fetch/fallback}
        ast               (query->ast query)
        children          (:children ast)
        new-children      (filter (fn [child] (not (contains? symbols-to-filter (:dispatch-key child)))) children)
        new-ast           (assoc ast :children new-children)]
    (ast->query new-ast)))

(defn fallback-tx [tx resp]
  "Filters out everything from the tx that is not a fallback mutation, and adds the given server
  response to the parameters of the remaining fallbacks.

  Returns the fallback tx, or nil if the resulting expression is empty.

  tx is the original transaction.
  resp is the response from the server."
  (let [symbols-to-find #{'tx/fallback 'fulcro.client.data-fetch/fallback}
        ast             (query->ast tx)
        children        (:children ast)
        new-children    (->> children
                          (filter (fn [child] (contains? symbols-to-find (:dispatch-key child))))
                          (map (fn [ast] (update ast :params assoc :execute true :error resp))))
        new-ast         (assoc ast :children new-children)
        fallback-tx     (ast->query new-ast)]
    (when (not-empty fallback-tx)
      fallback-tx)))

(defn- is-ui-query-fragment?
  "Check the given keyword to see if it is in the :ui namespace."
  [kw]
  (let [kw (if (map? kw) (-> kw keys first) kw)]
    (when (keyword? kw) (some->> kw namespace (re-find #"^ui(?:\.|$)")))))

(defn strip-ui
  "Returns a new query with fragments that are in the `ui` namespace removed."
  [query]
  (let [ast              (query->ast query)
        drop-ui-children (fn drop-ui-children [ast-node]
                           (let [children (reduce (fn [acc n]
                                                    (if (is-ui-query-fragment? (:dispatch-key n))
                                                      acc
                                                      (conj acc (drop-ui-children n))))
                                            [] (:children ast-node))]
                             (if (seq children)
                               (assoc ast-node :children children)
                               (dissoc ast-node :children))))]
    (ast->query (drop-ui-children ast))))

(def nf ::not-found)

(defn as-leaf
  "Returns data with meta-data marking it as a leaf in the result."
  [data]
  (if (coll? data)
    (with-meta data {:fulcro/leaf true})
    data))

(defn leaf?
  "Returns true iff the given data is marked as a leaf in the result (according to the query). Requires pre-marking."
  [data]
  (or
    (not (coll? data))
    (empty? data)
    (and (coll? data)
      (-> data meta :fulcro/leaf boolean))))

(defn union->query
  "Turn a union query into a query that attempts to encompass all possible things that might be queried"
  [union-query]
  (->> union-query vals flatten set vec))

(defn mark-missing
  "Recursively walk the query and response marking anything that was *asked for* in the query but is *not* in the response as missing.
  The merge process (which happens later in the plumbing) looks for these markers as indicators to remove any existing
  data in the database (which has provably disappeared).

  The naive approach to data merging (even recursive) would fail to remove such data.

  Returns the result with missing markers in place (which are then used/removed in a later stage)."
  [result query]
  (let [missing-entity {:ui/fetch-state {:fulcro.client.impl.data-fetch/type :not-found}}]
    (reduce (fn [result element]
              (let [element      (cond
                                   (list? element) (first element)
                                   :else element)
                    result-key   (cond
                                   (keyword? element) element
                                   (util/join? element) (util/join-key element)
                                   :else nil)
                    result-value (get result result-key)]
                (cond
                  (is-ui-query-fragment? result-key)
                  result

                  ; plain missing prop
                  (and (keyword? element) (nil? (get result element)))
                  (assoc result element ::not-found)

                  ; recursion
                  (and (util/join? element) (or (number? (util/join-value element)) (= '... (util/join-value element))))
                  (let [k       (util/join-key element)
                        result' (get result k)]
                    (cond
                      (nil? result') (assoc result k ::not-found) ; TODO: Is this right? Or, should it just be `result`?
                      (vector? result') (assoc result k (mapv (fn [item] (mark-missing item query)) result'))
                      :otherwise (assoc result k (mark-missing result' query))))

                  ; pure ident query
                  (and (util/ident? element) (nil? (get result element)))
                  (assoc result element missing-entity)

                  ; union (a join with a map as a target query)
                  (util/union? element)
                  (let [v          (get result result-key ::not-found)
                        to-one?    (map? v)
                        to-many?   (vector? v)
                        wide-query (union->query (util/join-value element))]
                    (cond
                      to-one? (assoc result result-key (mark-missing v wide-query))
                      to-many? (assoc result result-key (mapv (fn [i] (mark-missing i wide-query)) v))
                      (= ::not-found v) (assoc result result-key ::not-found)
                      :else result))

                  ; ident-based join to nothing (removing table entries)
                  (and (util/join? element) (util/ident? (util/join-key element)) (nil? (get result (util/join-key element))))
                  (let [mock-missing-object (mark-missing {} (util/join-value element))]
                    (assoc result (util/join-key element) (merge mock-missing-object missing-entity)))

                  ; join to nothing
                  (and (util/join? element) (= ::not-found (get result (util/join-key element) ::not-found)))
                  (assoc result (util/join-key element) ::not-found)

                  ; to-many join
                  (and (util/join? element) (vector? (get result (util/join-key element))))
                  (assoc result (util/join-key element) (mapv (fn [item] (mark-missing item (util/join-value element))) (get result (util/join-key element))))

                  ; to-one join
                  (and (util/join? element) (map? (get result (util/join-key element))))
                  (assoc result (util/join-key element) (mark-missing (get result (util/join-key element)) (util/join-value element)))

                  ; join, but with a broken result (scalar instead of a map or vector)
                  (and (util/join? element) (vector? (util/join-value element)) (not (or (map? result-value) (vector? result-value))))
                  (assoc result result-key (mark-missing {} (util/join-value element)))

                  ; prop we found, but not a further join...mark it as a leaf so sweep can stop early on it
                  result-key
                  (update result result-key as-leaf)

                  :else result))) result query)))

(defn sweep-one "Remove not-found keys from m (non-recursive)" [m]
  (cond
    (map? m) (reduce (fn [acc [k v]]
                       (if (or (= ::not-found k) (= ::not-found v) (= ::tempids k) (= :tempids k))
                         acc
                         (assoc acc k v)))
               (with-meta {} (meta m)) m)
    (vector? m) (with-meta (mapv sweep-one m) (meta m))
    :else m))

(defn sweep "Remove all of the not-found keys (recursively) from v, stopping at marked leaves (if present)"
  [m]
  (cond
    (leaf? m) (sweep-one m)
    (map? m) (reduce (fn [acc [k v]]
                       (cond
                         (or (= ::not-found k) (= ::not-found v) (= ::tempids k) (= :tempids k)) acc
                         (and (util/ident? v) (= ::not-found (second v))) acc
                         :otherwise (assoc acc k (sweep v))))
               (with-meta {} (meta m))
               m)
    (vector? m) (with-meta (mapv sweep m) (meta m))
    :else m))

(defn sweep-merge
  "Do a recursive merge of source into target, but remove any target data that is marked as missing in the response. The
  missing marker is generated in the source when something has been asked for in the query, but had no value in the
  response. This allows us to correctly remove 'empty' data from the database without accidentally removing something
  that may still exist on the server (in truth we don't know its status, since it wasn't asked for, but we leave
  it as our 'best guess')"
  [target source]
  (reduce
    (fn [acc [key new-value]]
      (let [existing-value (get acc key)]
        (cond
          (or (= key ::tempids) (= key :tempids) (= key ::not-found)) acc
          (= new-value ::not-found) (dissoc acc key)
          (and (util/ident? new-value) (= ::not-found (second new-value))) acc
          (leaf? new-value) (assoc acc key (sweep-one new-value))
          (and (map? existing-value) (map? new-value)) (update acc key sweep-merge new-value)
          :else (assoc acc key (sweep new-value)))))
    target
    source))

(defn merge-handler
  "Handle merging incoming data, but be sure to sweep it of values that are marked missing. Also triggers the given mutation-merge
  if available."
  [mutation-merge target source]
  (let [source-to-merge (->> source
                          (filter (fn [[k _]] (not (symbol? k))))
                          (into {}))
        merged-state    (sweep-merge target source-to-merge)]
    (reduce (fn [acc [k v]]
              (if (and mutation-merge (symbol? k))
                (if-let [updated-state (mutation-merge acc k (dissoc v :tempids ::tempids))]
                  updated-state
                  (do
                    (log/info "Return value handler for" k "returned nil. Ignored.")
                    acc))
                acc)) merged-state source)))

(defn merge-mutation-joins
  "Merge all of the mutations that were joined with a query"
  [state query data-tree]
  (if (map? data-tree)
    (reduce (fn [updated-state query-element]
              (let [k       (and (util/mutation-join? query-element) (util/join-key query-element))
                    subtree (get data-tree k)]
                (if (and k subtree)
                  (let [subquery         (util/join-value query-element)
                        target           (-> (meta subquery) :fulcro.client.impl.data-fetch/target)
                        idnt             ::temporary-key
                        norm-query       [{idnt subquery}]
                        norm-tree        {idnt subtree}
                        norm-tree-marked (mark-missing norm-tree norm-query)
                        db               (tree->db norm-query norm-tree-marked true)]
                    (cond-> (sweep-merge updated-state db)
                      target (targeting/process-target idnt target)
                      (not target) (dissoc db idnt)))
                  updated-state))) state query)
    state))

(defn- merge-idents [tree config refs query]
  (let [{:keys [merge-ident indexer]} config
        ident-joins (into {} (comp
                               (map #(cond-> % (seq? %) first))
                               (filter #(and (util/join? %)
                                          (util/ident? (util/join-key %)))))
                      query)]
    (letfn [(step [tree' [ident props]]
              (if (:normalize config)
                (let [c-or-q (or (get ident-joins ident) (ref->any indexer ident))
                      props' (tree->db c-or-q props)
                      refs   (meta props')]
                  ((:merge-tree config)
                    (merge-ident config tree' ident props') refs))
                (merge-ident config tree' ident props)))]
      (reduce step tree refs))))

(defn- merge-novelty
  ([reconciler state-map result-tree query tempids]
   (let [state (if-let [migrate (-> reconciler :config :migrate)]
                 (let [root-component (app-root reconciler)
                       root-query     (when-not query (get-query root-component state-map))]
                   (migrate state-map (or query root-query) tempids))
                 state-map)]
     (merge-novelty reconciler state result-tree query)))
  ([reconciler state-map result-tree query]
   (let [config            (:config reconciler)
         [idts result-tree] (sift-idents result-tree)
         normalized-result (if (:normalize config)
                             (tree->db
                               (or query (:root @(:state reconciler)))
                               result-tree true)
                             result-tree)]
     (-> state-map
       (merge-mutation-joins query result-tree)
       (merge-idents config idts query)
       ((:merge-tree config) normalized-result)))))

(defn get-tempids [m] (or (get m :tempids) (get m ::tempids)))

(defn merge*
  "Internal implementation of merge. Given a reconciler, state (map), result, and query returns a map of the:

  `:keys` to refresh
  `:next` state
  and `::tempids` that need to be migrated"
  [reconciler state res query]
  (let [tempids (->> (filter (comp symbol? first) res)
                  (map (comp get-tempids second))
                  (reduce merge {}))]
    {:keys     (into [] (remove symbol?) (keys res))
     :next     (merge-novelty reconciler state res query tempids)
     ::tempids tempids}))

(defn merge!
  "Merge an arbitrary data-tree that conforms to the shape of the given query.

  query - A query, derived from defui components, that can be used to normalized a tree of data.
  data-tree - A tree of data that matches the nested shape of query
  remote - No longer used. May be passed, but is ignored."
  ([reconciler data-tree]
   (merge! reconciler data-tree nil))
  ([reconciler data-tree query]
   (merge! reconciler data-tree query nil))
  ([reconciler data-tree query remote]
   (let [tx `[(fulcro.client.mutations/merge! ~{:data-tree data-tree :query query :remote remote})]]
     (transact! reconciler tx))))

(defn build-prop->class-index!
  "Build an index from property to class using the (annotated) query."
  [prop->classes query]
  (prewalk (fn index-walk-helper [ele]
             (when-let [component (some-> ele meta :component)]
               (let [ks (gather-keys ele)]
                 (doseq [k ks]
                   (swap! prop->classes update k (fnil conj #{}) component))))
             ele) query))

(defrecord Indexer [indexes]
  #?(:clj  clojure.lang.IDeref
     :cljs IDeref)
  #?(:clj  (deref [_] @indexes)
     :cljs (-deref [_] @indexes))

  p/IIndexer
  (indexes [this] (:indexes this))
  (index-root [this root-class]
    (assert (:state this) "State map is in `this` for indexing root")
    (let [prop->classes (atom {})
          state-map     (get this :state)
          rootq         (get-query (factory root-class nil) state-map)]
      (build-prop->class-index! prop->classes rootq)
      (swap! indexes merge {:prop->classes @prop->classes})))

  (index-component! [_ c]
    (swap! indexes
      (fn component-indexer [indexes]
        (let [indexes (update-in indexes
                        [:class->components (react-type c)]
                        (fnil conj #{}) c)
              ident   (when #?(:clj  (satisfies? Ident c)
                               :cljs (implements? Ident c))
                        (let [ident (ident c (props c))]
                          (when-not (util/ident? ident)
                            (log/info
                              "malformed Ident. An ident must be a vector of "
                              "two elements (a keyword and an EDN value). Check "
                              "the Ident implementation of component `"
                              (.. c -constructor -displayName) "`."))
                          (when-not (some? (second ident))
                            (log/info
                              (str "component " (.. c -constructor -displayName)
                                "'s ident (" ident ") has a `nil` second element."
                                " This warning can be safely ignored if that is intended.")))
                          ident))]
          (if-not (nil? ident)
            (cond-> indexes
              ident (update-in [:ref->components ident] (fnil conj #{}) c))
            indexes)))))

  (drop-component! [_ c]
    (swap! indexes
      (fn drop-component-helper [indexes]
        (let [indexes (update-in indexes [:class->components (react-type c)] disj c)
              ident   (when #?(:clj  (satisfies? Ident c)
                               :cljs (implements? Ident c))
                        (ident c (props c)))]
          (if-not (nil? ident)
            (cond-> indexes
              ident (update-in [:ref->components ident] disj c))
            indexes)))))

  (key->components [_ k]
    (let [indexes @indexes]
      (if (component? k)
        #{k}
        (transduce (map #(get-in indexes [:class->components %]))
          (completing into)
          (get-in indexes [:ref->components k] #{})
          (get-in indexes [:prop->classes k]))))))


(defn- to-env [x]
  (let [config (if (reconciler? x) (:config x) x)]
    (select-keys config [:state :shared :parser])))

(defn gather-sends
  "Given an environment, a query and a set of remotes return a hash map of remotes
   mapped to the query specific to that remote."
  [{:keys [parser] :as env} q remotes tx-time]
  (into {}
    (comp
      (map #(vector % (some-> (parser env q %) (vary-meta assoc ::hist/tx-time tx-time))))
      (filter (fn [[_ v]] (pos? (count v)))))
    remotes))

(defn schedule-sends! [reconciler]
  (when (p/schedule-sends! reconciler)
    #?(:clj  (p/send! reconciler)
       :cljs (js/setTimeout #(p/send! reconciler) 0))))

#?(:cljs
   (defn- queue-render! [f]
     (cond
       (fn? *raf*) (*raf* f)

       (not (exists? js/requestAnimationFrame))
       (js/setTimeout f 16)

       :else
       (js/requestAnimationFrame f))))

#?(:cljs
   (defn schedule-render! [reconciler]
     (when (p/schedule-render! reconciler)
       (queue-render! #(p/reconcile! reconciler)))))

(defn mounted?
  "Returns true if the component is mounted."
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (and (component? x) @(get-raw-react-prop x :fulcro$mounted?))
     :cljs (and (component? x) ^boolean (boolean (goog.object/get x "fulcro$mounted")))))

(defn fulcro-ui->props
  "Finds props for a given component. Returns ::no-ident if the component has
  no ident (which prevents localized update). This eliminates the need for
  path data."
  [{:keys [parser state] :as env} c]
  (let [ui (when #?(:clj  (satisfies? Ident c)
                    :cljs (implements? Ident c))
             (let [id          (ident c (props c))
                   has-tempid? (tempid? (second id))
                   query       [{id (get-query c @state)}]
                   data-path   (-> (props c) meta ::parser/data-path)
                   result      (parser (assoc env :replacement-root-path data-path) query)
                   value       (get result id)]
               (if (and has-tempid? (or (nil? value) (empty? value)))
                 ::no-ident                                 ; tempid remap happened...cannot do targeted props until full re-render
                 value)))]
    (or ui ::no-ident)))

(defn computed
  "Add computed properties to props. Note will replace any pre-existing
   computed properties."
  [props computed-map]
  (when-not (nil? props)
    (if (vector? props)
      (cond-> props
        (not (empty? computed-map)) (vary-meta assoc :fulcro.client.primitives/computed computed-map))
      (cond-> props
        (not (empty? computed-map)) (assoc :fulcro.client.primitives/computed computed-map)))))

(defn get-computed
  "Return the computed properties on a component or its props."
  ([x]
   (get-computed x []))
  ([x k-or-ks]
   (when-not (nil? x)
     (let [props (cond-> x (component? x) props)
           ks    (into [:fulcro.client.primitives/computed]
                   (cond-> k-or-ks
                     (not (sequential? k-or-ks)) vector))]
       (if (vector? props)
         (-> props meta (get-in ks))
         (get-in props ks))))))

(defn children
  "Returns the component's children."
  [component]
  (let [cs #?(:clj (:children component)
              :cljs (.. component -props -children))]
    (if (or (coll? cs) #?(:cljs (array? cs))) cs [cs])))

#?(:cljs
   (defn force-update
     "An exception-protected React .forceUpdate"
     ([c cb]
      (try
        (.forceUpdate c cb)
        (catch :default e
          (log/error "Component" c "threw an exception while rendering ")
          (when goog.DEBUG
            (js/console.error e)))))
     ([c]
      (force-update c nil))))

(defn dedup-components-by-path
  "Remove components from the given list by removing those whose paths are encompassed by others. In other words,
   remove components from the list when there is a parent of that component also in the list."
  [components]
  (let [get-path     #(some-> % props meta ::parser/data-path)
        sorted-comps (sort-by get-path components)]
    (reduce (fn [acc c]
              (let [last-component (last acc)
                    prev-path      (get-path last-component)
                    path           (get-path c)
                    path-prefix    (take (count prev-path) path)]
                (if (or (= last-component c) (and prev-path (= prev-path path-prefix)))
                  acc
                  (conj acc c))))
      [] sorted-comps)))

(defn- optimal-render
  "Run an optimal render of the given `refresh-queue` (a list of idents and data query keywords). This function attempts
   to refresh the minimum number of components according to the UI depth and state. If it cannot do targeted updates then
   it will call `render-root` to render the entire UI. Other optimizations may apply in render-root."
  [reconciler refresh-queue render-root]
  (let [reconciler-state       (:state reconciler)
        {:keys [root render-props]} @reconciler-state
        config                 (:config reconciler)
        queued-components      (transduce
                                 (map #(p/key->components (:indexer config) %))
                                 #(into %1 %2) #{} refresh-queue)
        mounted-components     (filter mounted? queued-components)
        data-path              (fn [c] (some-> c props meta ::parser/data-path))
        parent-with-path       (fn pwp [c]
                                 (loop [p (parent c)]
                                   (cond
                                     (and p (data-path p)) p
                                     p (recur (parent p))
                                     :else root)))
        refreshable-components (reduce
                                 (fn [result c]
                                   (if (data-path c)
                                     (conj result c)
                                     (conj result (parent-with-path c))))
                                 []
                                 mounted-components)
        env                    (assoc (to-env config) :reconciler reconciler)]
    #?(:cljs
       (let [old-tree     (props root)
             components   (dedup-components-by-path refreshable-components)
             updated-tree (reduce
                            (fn [tree c]
                              (let [component-props (props c)
                                    computed        (get-computed component-props)
                                    target-path     (data-path c)
                                    next-raw-props  (fulcro-ui->props env c)
                                    force-root?     (or (not target-path) (= ::no-ident next-raw-props)) ; can't do optimized query
                                    next-props      (when-not force-root? (fulcro.client.primitives/computed next-raw-props computed))]
                                (if force-root?
                                  (do
                                    (when (not target-path)
                                      (log/warn "Optimal render skipping optimizations because component does not have a target path" c))
                                    (reduced nil))
                                  (let []
                                    (assoc-in tree target-path next-props)))))
                            old-tree
                            components)]
         (if updated-tree
           (render-props updated-tree)
           (let [start (inst-ms (js/Date.))
                 _     (render-root)
                 end   (inst-ms (js/Date.))]
             (if (> (- end start) 20) (log/warn "Root render took " (- end start) "ms"))))))))

(defrecord Reconciler [config state history]
  #?(:clj  clojure.lang.IDeref
     :cljs IDeref)
  #?(:clj  (deref [this] @(:state config))
     :cljs (-deref [_] @(:state config)))

  p/IReconciler
  (tick! [_] (swap! state update :t inc))
  (get-id [_] (:id @state))
  (basis-t [_] (:t @state))
  (get-history [_] history)

  (add-root! [this root-class target options]
    (let [ret   (atom nil)
          rctor (factory root-class)
          guid  (or (p/get-id this) (util/unique-key))]
      (when-not (p/get-id this)
        (swap! state assoc :id guid))
      (when (has-query? root-class)
        (p/index-root (assoc (:indexer config) :state (-> config :state deref)) root-class))
      (when (and (:normalize config)
              (not (:normalized @state)))
        (let [new-state (tree->db root-class @(:state config))
              refs      (meta new-state)]
          (reset! (:state config) (merge new-state refs))
          (swap! state assoc :normalized true)))
      (let [renderf (fn render-fn [data]
                      (binding [*reconciler* this
                                *shared*     (merge
                                               (:shared config)
                                               (when (:shared-fn config)
                                                 ((:shared-fn config) data)))
                                *instrument* (:instrument config)]
                        (let [c (cond
                                  #?@(:cljs [(not (nil? target)) ((:root-render config) (rctor data) target)])
                                  (nil? @ret) (rctor data)
                                  :else (when-let [c' @ret]
                                          #?(:clj  (do
                                                     (reset! ret nil)
                                                     (rctor data))
                                             :cljs (when (mounted? c')
                                                     (force-update c' data)))))]
                          (when (and (nil? @ret) (not (nil? c)))
                            (swap! state assoc :root c)
                            (reset! ret c)))))
            parsef  (fn parse-fn []
                      (let [root-query (get-query rctor (-> config :state deref))]
                        (assert (or (nil? root-query) (vector? root-query)) "Application root query must be a vector")
                        (if-not (nil? root-query)
                          (let [env        (to-env config)
                                root-props ((:parser config) env root-query)]
                            (when (empty? root-props)
                              (log/warn "WARNING: Root props were empty. Your root query returned no data!"))
                            (renderf root-props))
                          (renderf @(:state config)))))]
        (swap! state merge
          {:target target :render parsef :root root-class :render-props renderf
           :remove (fn remove-fn []
                     (remove-watch (:state config) (p/get-id this))
                     (swap! state
                       #(-> %
                          (dissoc :target) (dissoc :render) (dissoc :root)
                          (dissoc :remove)))
                     (when-not (nil? target)
                       ((:root-unmount config) target)))})
        (add-watch (:state config) (p/get-id this)
          (fn add-fn [_ _ _ _]
            #?(:cljs
               (if-not (has-query? root-class)
                 (queue-render! parsef)
                 (do
                   (p/tick! this)
                   (schedule-render! this))))))
        (parsef)
        @ret)))

  (remove-root! [_ target]
    (when-let [remove (:remove @state)]
      (remove)))

  (reindex! [this]
    (let [root       (get @state :root)
          root-class (react-type root)]
      (when (has-query? root)
        (let [indexer (:indexer config)]
          (p/index-root (assoc indexer :state (-> config :state deref)) root-class)))))

  (queue! [this ks]
    (p/queue! this ks nil))
  (queue! [_ ks remote]
    (if-not (nil? remote)
      (swap! state update-in [:remote-queue remote] into ks)
      (swap! state update-in [:queue] into ks)))

  (queue-sends! [_ sends]
    (swap! state update-in [:queued-sends]
      (:merge-sends config) sends))

  (schedule-render! [_]
    (if-not (:queued @state)
      (do
        (swap! state assoc :queued true)
        true)
      false))

  (schedule-sends! [_]
    (if-not (:sends-queued @state)
      (do
        (swap! state assoc :sends-queued true)
        true)
      false))

  (reconcile! [this]
    (p/reconcile! this nil))
  (reconcile! [this remote]
    (let [reconciler-state      @state
          components-to-refresh (if-not (nil? remote)
                                  (get-in reconciler-state [:remote-queue remote])
                                  (:queue reconciler-state))
          render-mode           (:render-mode config)
          force-root?           (or (empty? components-to-refresh) (contains? #{:keyframe :brutal} render-mode) *blindly-render*)
          blind-refresh?        (or (contains? #{:brutal} render-mode) *blindly-render*)
          rendered-root?        (atom false)
          render-root           (fn []
                                  (if-let [do-render (:render reconciler-state)]
                                    (when-not @rendered-root? ; make sure we only render root once per reconcile
                                      (reset! rendered-root? true)
                                      (do-render))
                                    (log/error "Render skipped. Renderer was nil. Possibly a hot code reload?")))]
      ;; IMPORTANT: Unfortunate naming that would require careful refactoring. `state` here is the RECONCILER's state, NOT
      ;; the application's state. That is in (:state config).
      (swap! state update-in [:queued] not)
      (if (not (nil? remote))
        (swap! state assoc-in [:remote-queue remote] [])
        (swap! state assoc :queue []))
      (binding [*blindly-render* blind-refresh?]
        (if force-root?
          (render-root)
          (optimal-render this components-to-refresh render-root)))))

  (send! [this]
    (let [sends (:queued-sends @state)]
      (when-not (empty? sends)
        (swap! state
          (fn clear-queue-fn [state]
            (-> state
              (assoc :queued-sends {})
              (assoc :sends-queued false))))
        ((:send config) sends
          (fn send-cb
            ([resp]
             (merge! this resp nil))
            ([resp query]
             (merge! this resp query))
            ([resp query remote]
             (when-not (nil? remote)
               (p/queue! this (keys resp) remote))
             (merge! this resp query remote)
             (p/reconcile! this remote))))))))

(defn reconciler
  "Construct a reconciler from a configuration map.

   Required parameters:
     :state        - the application state. If IAtom value is not supplied the
                     data will be normalized into the default database format
                     using the root query. This can be disabled by explicitly
                     setting the optional :normalize parameter to false.
     :parser       - the parser to be used

   Optional parameters:
     :id           - a unique ID that this reconciler will be known as. Used to resolve global variable usage when more than one app is on a page. If
                     left unspecified it will default to a random UUID.
     :shared       - a map of global shared properties for the component tree.
     :shared-fn    - a function to compute global shared properties from the root props.
                     the result is merged with :shared.
     :send         - required only if the parser will return a non-empty value when
                     run against the supplied :remotes. send is a function of two
                     arguments, the map of remote expressions keyed by remote target
                     and a callback which should be invoked with the result from each
                     remote target. Note this means the callback can be invoked
                     multiple times to support parallel fetching and incremental
                     loading if desired. The callback should take the response as the
                     first argument and the the query that was sent as the second
                     argument.
     :history      - A positive integer. The number of history steps to keep in memory.
     :normalize    - whether the state should be normalized. If true it is assumed
                     all novelty introduced into the system will also need
                     normalization.
     :remotes      - a vector of keywords representing remote services which can
                     evaluate query expressions. Defaults to [:remote]
     :root-render  - the root render function. Defaults to ReactDOM.render
     :root-unmount - the root unmount function. Defaults to
                     ReactDOM.unmountComponentAtNode
     :render-mode  - :normal - fastest, and the default. Components with idents can refresh in isolation.
                               shouldComponentUpdate returns false if state/data are unchanged. Follow-on reads are
                               required to refresh non-local concerns.
                     :keyframe - Every data change runs a root-level query and re-renders from root.
                                 shouldComponentUpdate is the same as :default. Follow-on reads are *not* needed for
                                 non-local UI refresh.
                     :brutal - Every data change runs a root-level query, and re-renders from root. shouldComponentUpdate
                               always returns true, forcing full React diff. Not really useful for anything but benchmarking.
     :lifecycle    - A function (fn [component event]) that is called when react components either :mount or :unmount. Useful for debugging tools.
     :tx-listen    - a function of 2 arguments that will listen to transactions.
                     The first argument is the parser's env map also containing
                     the old and new state. The second argument is a history-step (see history). It also contains
                     a couple of legacy fields for bw compatibility with 1.0."
  [{:keys [id state shared shared-fn
           parser normalize
           send merge-sends remotes
           merge-tree merge-ident
           lifecycle
           root-render root-unmount
           migrate render-mode
           instrument tx-listen
           history]
    :or   {merge-sends  #(merge-with into %1 %2)
           remotes      [:remote]
           render-mode  :normal
           history      200
           lifecycle    nil
           root-render  #?(:clj  (fn clj-root-render [c target] c)
                           :cljs #(js/ReactDOM.render %1 %2))
           root-unmount #?(:clj  (fn clj-unmount [x])
                           :cljs #(js/ReactDOM.unmountComponentAtNode %))}
    :as   config}]
  {:pre [(map? config)]}
  (let [idxr          (map->Indexer {:indexes (atom {})})
        norm? #?(:clj (instance? clojure.lang.Atom state)
                 :cljs (satisfies? IAtom state))
        state'        (if norm? state (atom state))
        ret           (Reconciler.
                        {:state       state' :shared shared :shared-fn shared-fn
                         :parser      parser :indexer idxr
                         :send        send :merge-sends merge-sends :remotes remotes
                         :merge-tree  merge-tree :merge-ident merge-ident
                         :normalize   (or (not norm?) normalize)
                         :root-render root-render :root-unmount root-unmount
                         :render-mode render-mode
                         :pathopt     true
                         :migrate     migrate
                         :lifecycle   lifecycle
                         :instrument  instrument :tx-listen tx-listen}
                        (atom {:queue        []
                               :remote-queue {}
                               :id           id
                               :queued       false :queued-sends {}
                               :sends-queued false
                               :target       nil :root nil :render nil :remove nil
                               :t            0 :normalized norm?})
                        (atom (hist/new-history history)))]
    ret))

(defn transact*
  "Internal implementation detail of transact!. Call that function instead."
  [reconciler c ref tx]
  (when reconciler
    (p/tick! reconciler)                                    ; ensure time moves forward. A tx that doesn't swap would fail to do so
    (let [cfg                (:config reconciler)
          ref                (if (and c #?(:clj  (satisfies? Ident c)
                                           :cljs (implements? Ident c)) (not ref))
                               (ident c (props c))
                               ref)
          env                (merge
                               (to-env cfg)
                               {:reconciler reconciler :component c}
                               (when ref
                                 {:ref ref}))
          old-state          @(:state cfg)
          history            (get-history reconciler)
          v                  ((:parser cfg) env tx)
          declared-refreshes (or (some-> v meta ::refresh vec) [])
          follow-on-reads    (concat declared-refreshes (filter keyword? tx))
          tx-time            (get-current-time reconciler)
          snds               (gather-sends env tx (:remotes cfg) tx-time)
          new-state          @(:state cfg)
          xs                 (cond-> follow-on-reads
                               (not (nil? c)) (conj c)
                               (not (nil? ref)) (conj ref))
          history-step       {::hist/tx            tx
                              ::hist/client-time   #?(:cljs (js/Date.) :clj (java.util.Date.))
                              ::hist/network-sends snds
                              ::hist/db-before     old-state
                              ::hist/db-after      new-state}]
      ; TODO: transact! should have access to some kind of UI hook on the reconciler that user's install to block UI when history is too full (due to network queue)
      (when history
        (swap! history hist/record-history-step tx-time history-step))
      (p/queue! reconciler (into xs (remove symbol?) (keys v)))
      (when-not (empty? snds)
        (doseq [[remote _] snds]
          (p/queue! reconciler xs remote))
        (p/queue-sends! reconciler snds)
        (schedule-sends! reconciler))
      (when-let [f (:tx-listen cfg)]
        (let [tx-data (merge env
                        {:old-state old-state
                         :new-state new-state})]
          (f tx-data (assoc history-step :tx tx :ret v))))
      v)))

(defn annotate-mutations
  "Given a query expression annotate all mutations by adding a :mutator -> ident
   entry to the metadata of each mutation expression in the query."
  [tx ident]
  (letfn [(annotate [expr ident]
            (cond-> expr
              (util/mutation? expr) (vary-meta assoc :mutator ident)))]
    (with-meta
      (into [] (map #(annotate % ident)) tx)
      (meta tx))))

(defn transact!
  "Given a reconciler or component run a transaction. tx is a parse expression
   that should include mutations followed by any necessary read. The reads will
   be used to trigger component re-rendering.

   Example:

     (transact! widget
       '[(do/this!) (do/that!)
         :read/this :read/that])

    NOTE: transact! is not safe to call from within mutations unless you defer it inside of a setTimeout. This is
    because otherwise you could potentially nest calls of swap! that will cause unexpected results. In general it
    the model of Fulcro is such that a call transact! within a mutation is technically just bad design. If you
    need pessimistic UI control, see ptransact! instead."
  ([x tx]
   {:pre [(or (component? x)
            (reconciler? x))
          (vector? tx)]}
   (log/debug "running transaction: " tx)
   (let [tx (cond-> tx
              (and (component? x) (satisfies? Ident x))
              (annotate-mutations (get-ident x)))]
     (cond
       (reconciler? x) (transact* x nil nil tx)
       (not (some-hasquery? x)) (do
                                  (log/error
                                    (str "transact! should be called on a component"
                                      "that implements IQuery or has a parent that"
                                      "implements IQuery"))
                                  (transact* (get-reconciler x) nil nil tx))
       :else (do
               (loop [p (parent x) x x tx tx]
                 (if (nil? p)
                   (let [r (get-reconciler x)]
                     (transact* r x nil tx))
                   (let [[x' tx] (if #?(:clj  (satisfies? p/ITxIntercept p)
                                        :cljs (implements? p/ITxIntercept p))
                                   [p (p/tx-intercept p tx)]
                                   [x tx])]
                     (recur (parent p) x' tx))))))))
  ([r ref tx]
   (transact* r nil ref tx)))

(defn compressible-transact!
  "Identical to `transact!`, but marks the history edge as compressible. This means that if more than one
  adjacent history transition edge is compressible, only the more recent of the sequence of them is kept. This
  is useful for things like form input fields, where storing every keystoke in history is undesirable.

  NOTE: history events that trigger remote interactions are not compressible, since they may be needed for
  automatic network error recovery handling.."
  [comp-or-reconciler tx]
  (transact! comp-or-reconciler (hist/compressible-tx tx)))

#?(:clj
   (defn set-state!
     ([component new-state cb]
      (reset! (:state component) new-state)
      (when cb (cb)))
     ([component new-state]
      (reset! (:state component) new-state))))

#?(:cljs
   (defn set-state!
     "Shallow merge new-state in the the state of this component. Uses React setState and will trigger an refresh
     according to React rules (see React dos for the version you're using). callback is as described in the React docs.

     If you're wanting low-level js interop, use React's setState. This function deals with cljs state."
     ([component new-state callback]
      {:pre [(component? component)]}
      (.setState component
        (fn [prev-state props]
          #js {"fulcro$state" (merge (gobj/get prev-state "fulcro$state") new-state)})
        callback))
     ([component new-state]
      {:pre [(component? component)]}
      (set-state! component new-state nil))))

(defn react-set-state!
  "DEPRECATED: Use set-state! which *is* a React-level primitive now."
  ([component new-state]
   (react-set-state! component new-state nil))
  ([component new-state cb]
   (set-state! component new-state cb)))

(defn update-state!
  "Update a component's local state. Similar to Clojure(Script)'s swap!"
  ([component f]
   (set-state! component (f (get-state component))))
  ([component f arg0]
   (set-state! component (f (get-state component) arg0)))
  ([component f arg0 arg1]
   (set-state! component (f (get-state component) arg0 arg1)))
  ([component f arg0 arg1 arg2]
   (set-state! component (f (get-state component) arg0 arg1 arg2)))
  ([component f arg0 arg1 arg2 arg3]
   (set-state! component (f (get-state component) arg0 arg1 arg2 arg3)))
  ([component f arg0 arg1 arg2 arg3 & arg-rest]
   (set-state! component
     (apply f (get-state component) arg0 arg1 arg2 arg3 arg-rest))))

(defn app-state
  "Return the reconciler's application state atom. Useful when the reconciler
   was initialized via denormalized data."
  [reconciler]
  {:pre [(reconciler? reconciler)]}
  (-> reconciler :config :state))

(defn app-root
  "Return the application's root component."
  [reconciler]
  {:pre [(reconciler? reconciler)]}
  (get @(:state reconciler) :root))

(defn query->ast
  "Given a query expression convert it into an AST."
  [query-expr]
  (parser/query->ast query-expr))

(defn query->ast1
  "Call query->ast and return the first children."
  [query-expr]
  (-> (query->ast query-expr) :children first))

(defn ast->query [query-ast]
  "Given an AST convert it back into a query expression."
  (parser/ast->expr query-ast true))

(defn force-root-render!
  "Force a re-render of the root. Runs a root query, disables shouldComponentUpdate, and renders the root component.
   This effectively forces React to do a full VDOM diff. Useful for things like changing locales where there are no
   real data changes, but the UI still needs to refresh.
   recomputing :shared."
  [reconciler]
  {:pre [(reconciler? reconciler)]}
  (when-let [render (get @(:state reconciler) :render)]     ; hot code reload can cause this to be nil
    (binding [*blindly-render* true]
      (render))))

(defn tempid
  "Return a temporary id."
  ([] (tempid/tempid))
  ([id] (tempid/tempid id)))

(defn tempid?
  "Return true if x is a tempid, false otherwise"
  #?(:cljs {:tag boolean})
  [x]
  (tempid/tempid? x))

#?(:cljs
   (defn reader
     "Create a transit reader. This reader can handler the tempid type.
      Can pass transit reader customization opts map."
     ([] (transit/reader))
     ([opts] (transit/reader opts)))
   :clj
   (defn reader
     "Create a transit reader. This reader can handler the tempid type.
      Can pass transit reader customization opts map."
     ([in] (transit/reader in))
     ([in opts] (transit/reader in opts))))

(defn writer
  "Create a transit writer. This writer can handler the tempid type.
   Can pass transit writer customization opts map."
  ([] (transit/writer))
  ([opts] (transit/writer opts)))

(defn dispatch
  "Helper function for implementing :read and :mutate as multimethods. Use this
   as the dispatch-fn."
  [_ key _] key)

(defn parser
  "Create a parser. The argument is a map of two keys, :read and :mutate. Both
   functions should have the signature (Env -> Key -> Params -> ParseResult).

   The mutation functions return a map keyed by:

   `:action` - The lambda to run to do the local optimistic version of that mutation
   any-keyword-matching-a-remote - A boolean true or AST expression of the thing to run on the named remote.
   :refresh - A vector of namespaced keywords of data that was/will be changed by this mutation

   When the parser runs on mutations it collects the `:refresh` list into the metadata of the results
   under the :fulcro.client.primitives/refresh key."
  [{:keys [read mutate] :as opts}]
  {:pre [(map? opts)]}
  (parser/parser opts))

(defn add-root!
  "Given a root component class and a target root DOM node, instantiate and
   render the root class using the reconciler's :state property. The reconciler
   will continue to observe changes to :state and keep the target node in sync.
   Note a reconciler may have only one root. If invoked on a reconciler with an
   existing root, the new root will replace the old one."
  ([reconciler root-class target]
   (add-root! reconciler root-class target nil))
  ([reconciler root-class target options]
   {:pre [(reconciler? reconciler) (fn? root-class)]}
   (when-let [old-reconciler (get @roots target)]
     (remove-root! old-reconciler target))
   (swap! roots assoc target reconciler)
   (p/add-root! reconciler root-class target options)))

(defn remove-root!
  "Remove a root target (a DOM element) from a reconciler. The reconciler will
   no longer attempt to reconcile application state with the specified root."
  [reconciler target]
  (p/remove-root! reconciler target))

(defn shared
  "Return the global shared properties of the root. See :shared and
   :shared-fn reconciler options."
  ([component]
   (shared component []))
  ([component k-or-ks]
   {:pre [(component? component)]}
   (let [shared #?(:clj (get-raw-react-prop component :fulcro$shared)
                   :cljs (gobj/get (. component -props) "fulcro$shared"))
         ks             (cond-> k-or-ks
                          (not (sequential? k-or-ks)) vector)]
     (cond-> shared
       (not (empty? ks)) (get-in ks)))))

(defn instrument [component]
  {:pre [(component? component)]}
  (get-raw-react-prop component #?(:clj  :fulcro$instrument
                                   :cljs "fulcro$instrument")))

#?(:cljs
   (defn- merge-pending-state! [c]
     (when-let [pending (some-> c .-state (gobj/get "fulcro$pendingState"))]
       (let [state    (.-state c)
             previous (gobj/get state "fulcro$state")]
         (gobj/remove state "fulcro$pendingState")
         (gobj/set state "fulcro$previousState" previous)
         (gobj/set state "fulcro$state" pending)))))

(defn class->any
  "Get any component from the indexer that matches the component class."
  [x class]
  (let [indexer (if (reconciler? x) (get-indexer x) x)]
    (first (get-in @indexer [:class->components class]))))

(defn class->all
  "Get any component from the indexer that matches the component class."
  [x class]
  (let [indexer (if (reconciler? x) (get-indexer x) x)]
    (get-in @indexer [:class->components class])))

(defn ref->components
  "Return all components for a given ref."
  [x ref]
  (when-not (nil? ref)
    (let [indexer (if (reconciler? x) (get-indexer x) x)]
      (p/key->components indexer ref))))

(defn get-rendered-state
  "Get the rendered state of component. fulcro.client.primitives/get-state always returns the
   up-to-date state."
  ([component]
   (get-rendered-state component []))
  ([component k-or-ks]
   {:pre [(component? component)]}
   (let [cst #?(:clj (get-state component)
                :cljs (some-> component .-state (gobj/get "fulcro$state")))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(defn nil-or-map?
  #?(:cljs {:tag boolean})
  [x]
  (or (nil? x) (map? x)))

(defn react-ref
  "Returns the component associated with a component's React ref."
  [component name]
  #?(:clj  (some-> @(:refs component) (get name))
     :cljs (some-> (.-refs component) (gobj/get name))))

(defn set-query!
  "Set a dynamic query. ALters the query, and then rebuilds internal indexes."
  [component-or-reconciler ui-factory-or-queryid {:keys [query params follow-on-reads] :as opts}]
  (let [reconciler (if (reconciler? component-or-reconciler)
                     component-or-reconciler
                     (get-reconciler component-or-reconciler))
        queryid    (cond
                     (string? ui-factory-or-queryid) ui-factory-or-queryid
                     (some-> ui-factory-or-queryid meta (contains? :queryid)) (some-> ui-factory-or-queryid meta :queryid)
                     :otherwise (query-id ui-factory-or-queryid nil))
        tx         (into `[(fulcro.client.mutations/set-query! {:queryid ~queryid :query ~query :params ~params})] follow-on-reads)]
    (if (and (string? queryid) (or query params))
      (do
        (transact! reconciler tx)                           ; against reconciler, because we need to re-render from root
        (p/reindex! reconciler))
      (log/error "Unable to set query. Invalid arguments."))))

(defn pessimistic-transaction->transaction
  "Converts a sequence of calls as if each call should run in sequence (deferring even the optimistic side until
  the prior calls have completed in a full-stack manner), and returns a tx that can be submitted via the normal
  `transact!`.

  The options map can contain:
  `valid-remotes` is a set of remote names in your application. Defaults to `#{:remote}`
  `env` is a map that is merged into the deferred transaction's `env`

  WARNING: If a mutation tries to interact with more than one simultaneous remote, the current implementation will wait
  until the *first* one of them completes (selected in a non-deterministic fashion), not all."
  ([tx] (pessimistic-transaction->transaction tx nil))
  ([tx {:keys [valid-remotes env state-map]
        :or   {valid-remotes #{:remote} env {} state-map {}}
        :as   options}]
   (let [ast-nodes             (:children (query->ast tx))
         {ast-calls true ast-reads false} (group-by #(= :call (:type %)) ast-nodes)
         ast-follow-on-reads   (ast->query {:type :root :children ast-reads})
         remote-for-ast-call   (fn [c] (let [dispatch-key (:dispatch-key c)
                                             get-remotes  (or (some-> (resolve 'fulcro.client.data-fetch/get-remotes) deref)
                                                            (fn [state-map sym]
                                                              (log/error "FAILED TO FIND get-remotes. CANNOT DERIVE REMOTES FOR ptransact! Assuming :remote")
                                                              #{:remote}))
                                             remotes      (if (= "fallback" (name dispatch-key)) ; fallbacks are a special case
                                                            #{}
                                                            (get-remotes state-map dispatch-key))]
                                         (when (seq remotes)
                                           (first remotes))))
         is-local?             (fn [c] (not (remote-for-ast-call c)))
         [ast-local-calls ast-remaining-calls] (split-with is-local? ast-calls)
         ast-first-remote-call (some-> ast-remaining-calls (first))
         remote                (some-> ast-first-remote-call remote-for-ast-call)
         unprocessed-call-asts (vec (rest ast-remaining-calls))
         [possible-fallback-asts distant-call-asts] (split-with is-local? unprocessed-call-asts)
         {fallback-asts true following-call-asts false} (group-by #(= "fallback" (-> % :dispatch-key (name))) possible-fallback-asts)
         unprocessed-tx        (ast->query {:type :root :children (concat following-call-asts distant-call-asts)})
         calls-to-run-now      (keep identity (concat ast-local-calls [ast-first-remote-call] fallback-asts))
         tx-for-calls          (ast->query {:type :root :children calls-to-run-now})
         tx-to-run-now         (into tx-for-calls ast-follow-on-reads)
         tx-to-defer           (into unprocessed-tx ast-follow-on-reads)
         defer?                (seq unprocessed-call-asts)
         deferred-params       (when defer?
                                 (merge (get options :env {})
                                   {:remote remote :tx (pessimistic-transaction->transaction tx-to-defer options)}))]
     (if defer?
       (into tx-to-run-now `[(fulcro.client.data-fetch/deferred-transaction ~deferred-params)])
       tx-to-run-now))))

(defn ptransact!
  "Like `transact!`, but ensures each call completes (in a full-stack, pessimistic manner) before the next call starts
  in any way. Note that two calls of this function have no guaranteed relationship to each other. They could end up
  intermingled at runtime. The only guarantee is that for *a single call* to `ptransact!`, the calls in the given tx will run
  pessimistically (one at a time) in the order given. Follow-on reads in the given transaction will be repeated after each remote
  interaction.

  `comp-or-reconciler` a mounted component or reconciler
  `tx` the tx to run
  `ref` the ident (ref context) in which to run the transaction (including all deferrals)

  NOTE: `ptransact!` *is* safe to use from within mutations (e.g. for retry behavior).
  WARNINGS: Mutations that interact with more than one remote *at the same time* will only wait for one of the remotes to finish.
  Also, mutations that just issue loads should *not* be used. This function defers pessimistic *writes*, not reads."
  ([comp-or-reconciler tx]
   (let [ref (when (and (component? comp-or-reconciler) (has-ident? comp-or-reconciler)) (get-ident comp-or-reconciler))]
     (ptransact! comp-or-reconciler ref tx)))
  ([comp-or-reconciler ref tx]
   (let [reconciler (if (reconciler? comp-or-reconciler) comp-or-reconciler (get-reconciler comp-or-reconciler))
         state-map  @(app-state reconciler)
         remotes    (some-> reconciler :config :remotes set)
         ptx        (pessimistic-transaction->transaction tx (cond-> {:valid-remotes remotes :state-map state-map}
                                                               ref (assoc :env {:ref ref})))]
     #?(:clj  (transact! comp-or-reconciler ptx)
        :cljs (js/setTimeout (fn [] (transact! comp-or-reconciler ptx)) 0)))))

#?(:clj
   (defn- is-link? [query-element] (and (vector? query-element)
                                     (keyword? (first query-element))
                                     ; need the double-quote because when in a macro we'll get the literal quote.
                                     (#{''_ '_} (second query-element)))))

#?(:clj
   (defn- legal-keys
     "Find the legal keys in a query. NOTE: This is at compile time, so the get-query calls are still embedded (thus cannot
     use the AST)"
     [query]
     (letfn [(keeper [ele]
               (cond
                 (list? ele) (recur (first ele))
                 (keyword? ele) ele
                 (is-link? ele) (first ele)
                 (and (map? ele) (keyword? (ffirst ele))) (ffirst ele)
                 (and (map? ele) (is-link? (ffirst ele))) (first (ffirst ele))
                 :else nil))]
       (set (keep keeper query)))))

#?(:clj
   (defn- children-by-prop [query]
     (into {}
       (keep #(if (and (map? %) (or (is-link? (ffirst %)) (keyword? (ffirst %))))
                (let [k   (if (vector? (ffirst %))
                            (first (ffirst %))
                            (ffirst %))
                      cls (-> % first second second)]
                  [k cls])
                nil) query))))

(defn- replace-and-validate-fn
  "Replace the first sym in a list (the function name) with the given symbol.

  sym - The symbol that the lambda should have
  external-args - A sequence of argmuments that the user should not include, but that you want to be inserted in the external-args by this function.
  user-arity - The number of external-args the user should supply (resulting user-arity is (count external-args) + user-arity).
  fn-form - The form to rewrite
  sym - The symbol to report in the error message (in case the rewrite uses a different target that the user knows)."
  ([sym external-args user-arity fn-form] (replace-and-validate-fn sym external-args user-arity fn-form sym))
  ([sym external-args user-arity fn-form user-known-sym]
   (when-not (<= user-arity (count (second fn-form)))
     (throw (ex-info (str "Invalid arity for " user-known-sym) {:expected (str user-arity " or more") :got (count (second fn-form))})))
   (let [user-args    (second fn-form)
         updated-args (into (vec (or external-args [])) user-args)
         body-forms   (drop 2 fn-form)]
     (->> body-forms
       (cons updated-args)
       (cons sym)))))

#?(:clj
   (defn- build-query-forms
     "Validate that the property destructuring and query make sense with each other."
     [class thissym propargs {:keys [template method]}]
     (cond
       template
       (do
         (assert (or (symbol? propargs) (map? propargs)) "Property args must be a symbol or destructuring expression.")
         (let [to-keyword        (fn [s] (cond
                                           (nil? s) nil
                                           (keyword? s) s
                                           :otherwise (let [nspc (namespace s)
                                                            nm   (name s)]
                                                        (keyword nspc nm))))
               destructured-keys (when (map? propargs) (->> (:keys propargs) (map to-keyword) set))
               queried-keywords  (legal-keys template)
               has-wildcard?     (some #{'*} template)
               to-sym            (fn [k] (symbol (namespace k) (name k)))
               illegal-syms      (mapv to-sym (set/difference destructured-keys queried-keywords))]
           (when (and (not has-wildcard?) (seq illegal-syms))
             (throw (ex-info (str "defsc " class ": " illegal-syms " destructured in props but do(es) not appear in your query!") {:offending-symbols illegal-syms})))
           `(~'static fulcro.client.primitives/IQuery (~'query [~thissym] ~template))))
       method
       `(~'static fulcro.client.primitives/IQuery ~(replace-and-validate-fn 'query [thissym] 0 method)))))

#?(:clj
   (defn- build-ident
     "Builds the ident form. If ident is a vector, then it generates the function and validates that the ID is
     in the query. Otherwise, if ident is of the form (ident [this props] ...) it simply generates the correct
     entry in defui without error checking."
     [thissym propsarg {:keys [:method :template]} is-legal-key?]
     (cond
       method `(~'static fulcro.client.primitives/Ident ~(replace-and-validate-fn 'ident [thissym propsarg] 0 method))
       template (let [table   (first template)
                      id-prop (or (second template) :db/id)]
                  (cond
                    (nil? table) (throw (ex-info "TABLE part of ident template was nil" {}))
                    (not (is-legal-key? id-prop)) (throw (ex-info "ID property of :ident does not appear in your :query" {:id-property id-prop}))
                    :otherwise `(~'static fulcro.client.primitives/Ident (~'ident [~'this ~'props] [~table (~id-prop ~'props)])))))))

#?(:clj
   (defn- build-render [classsym thissym propsym compsym csssym body]
     (let [css-bindings      (when csssym `[~csssym (fulcro-css.css/get-classnames ~classsym)])
           computed-bindings (when compsym `[~compsym (fulcro.client.primitives/get-computed ~thissym)])]
       `(~'Object
          (~'render [~thissym]
            (let [~propsym (fulcro.client.primitives/props ~thissym)
                  ~@computed-bindings
                  ~@css-bindings]
              ~@body))))))

#?(:clj
   (defn- make-lifecycle [thissym options]
     (let [possible-methods  (-> options keys set)
           lifecycle-kws     (->> lifecycle-sigs keys (map (comp keyword name)) set)
           methods-to-define (set/intersection lifecycle-kws possible-methods)
           get-signature     (fn [sym] (drop 1 (get lifecycle-sigs sym)))]
       (mapv (fn [method-kw]
               (let [sym       (symbol (name method-kw))
                     lambda    (get options method-kw)
                     signature (get-signature sym)
                     arity     (count signature)
                     method    (replace-and-validate-fn sym [thissym] arity lambda)]
                 method))
         methods-to-define))))

(defn make-state-map
  "Build a component's initial state using the defsc initial-state-data from
  options, the children from options, and the params from the invocation of get-initial-state."
  [initial-state children-by-query-key params]
  (let [join-keys (set (keys children-by-query-key))
        init-keys (set (keys initial-state))
        is-child? (fn [k] (contains? join-keys k))
        value-of  (fn value-of* [[isk isv]]
                    (let [param-name    (fn [v] (and (keyword? v) (= "param" (namespace v)) (keyword (name v))))
                          substitute    (fn [ele] (if-let [k (param-name ele)]
                                                    (get params k)
                                                    ele))
                          param-key     (param-name isv)
                          param-exists? (contains? params param-key)
                          param-value   (get params param-key)
                          child-class   (get children-by-query-key isk)]
                      (cond
                        ; parameterized lookup with no value
                        (and param-key (not param-exists?)) nil

                        ; to-one join, where initial state is a map to be used as child initial state *parameters* (enforced by defsc macro)
                        ; and which may *contain* parameters
                        (and (map? isv) (is-child? isk)) [isk (get-initial-state child-class (into {} (keep value-of* isv)))]

                        ; not a join. Map is literal initial value.
                        (map? isv) [isk (into {} (keep value-of* isv))]

                        ; to-many join. elements MUST be parameters (enforced by defsc macro)
                        (and (vector? isv) (is-child? isk)) [isk (mapv (fn [m] (get-initial-state child-class (into {} (keep value-of* m)))) isv)]

                        ; to-many join. elements might be parameter maps or already-obtained initial-state
                        (and (vector? param-value) (is-child? isk)) [isk (mapv (fn [params]
                                                                                 (if (computed-initial-state? params)
                                                                                   params
                                                                                   (get-initial-state child-class params))) param-value)]

                        ; vector of non-children
                        (vector? isv) [isk (mapv (fn [ele] (substitute ele)) isv)]

                        ; to-one join with parameter. value might be params, or an already-obtained initial-state
                        (and param-key (is-child? isk) param-exists?) [isk (if (computed-initial-state? param-value)
                                                                             param-value
                                                                             (get-initial-state child-class param-value))]
                        param-key [isk param-value]
                        :else [isk isv])))]
    (into {} (keep value-of initial-state))))

#?(:clj
   (defn- build-and-validate-initial-state-map [sym initial-state legal-keys children-by-query-key is-a-form?]
     (let [join-keys     (set (keys children-by-query-key))
           init-keys     (set (keys initial-state))
           illegal-keys  (if (set? legal-keys) (set/difference init-keys legal-keys) #{})
           is-child?     (fn [k] (contains? join-keys k))
           param-expr    (fn [v]
                           (if-let [kw (and (keyword? v) (= "param" (namespace v))
                                         (keyword (name v)))]
                             `(~kw ~'params)
                             v))
           parameterized (fn [init-map] (into {} (map (fn [[k v]] (if-let [expr (param-expr v)] [k expr] [k v])) init-map)))
           child-state   (fn [k]
                           (let [state-params    (get initial-state k)
                                 to-one?         (map? state-params)
                                 to-many?        (and (vector? state-params) (every? map? state-params))
                                 code?           (list? state-params)
                                 from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                                 child-class     (get children-by-query-key k)]
                             (when code?
                               (throw (ex-info (str "defsc " sym ": Illegal call " state-params ". Use a lambda to write code for initial state. Template mode for initial state requires simple maps (or vectors of maps) as parameters to children. See Developer's Guide.")
                                        {})))
                             (cond
                               (not (or from-parameter? to-many? to-one?)) (throw (ex-info "Initial value for a child must be a map or vector of maps!" {:offending-child k}))
                               to-one? `(fulcro.client.primitives/get-initial-state ~child-class ~(parameterized state-params))
                               to-many? (mapv (fn [params]
                                                `(fulcro.client.primitives/get-initial-state ~child-class ~(parameterized params)))
                                          state-params)
                               from-parameter? `(fulcro.client.primitives/get-initial-state ~child-class ~(param-expr state-params))
                               :otherwise nil)))
           kv-pairs      (map (fn [k]
                                [k (if (is-child? k)
                                     (child-state k)
                                     (param-expr (get initial-state k)))]) init-keys)
           state-map     (into {} kv-pairs)]
       (when (seq illegal-keys)
         (throw (ex-info "Initial state includes keys that are not in your query." {:offending-keys illegal-keys})))
       (if is-a-form?
         `(~'static fulcro.client.primitives/InitialAppState
            (~'initial-state [~'c ~'params] (fulcro.ui.forms/build-form ~sym (fulcro.client.primitives/make-state-map ~initial-state ~children-by-query-key ~'params))))
         `(~'static fulcro.client.primitives/InitialAppState
            (~'initial-state [~'c ~'params] (fulcro.client.primitives/make-state-map ~initial-state ~children-by-query-key ~'params)))))))

#?(:clj
   (defn- build-raw-initial-state
     "Given an initial state form that is a list (function-form), simple copy it into the form needed by defui."
     [thissym method]
     `(~'static fulcro.client.primitives/InitialAppState
        ~(replace-and-validate-fn 'initial-state [thissym] 1 method))))

#?(:clj
   (defn- build-initial-state [sym thissym {:keys [template method]} legal-keys query-template-or-method is-a-form?]
     (when (and template (contains? query-template-or-method :method))
       (throw (ex-info "When query is a method, initial state MUST be as well." {:component sym})))
     (cond
       method (build-raw-initial-state thissym method)
       template (let [query    (:template query-template-or-method)
                      children (or (children-by-prop query) {})]
                  (build-and-validate-initial-state-map sym template legal-keys children is-a-form?)))))

#?(:clj (s/def :fulcro.client.primitives.defsc/ident (s/or :template (s/and vector? #(= 2 (count %))) :method list?)))
#?(:clj (s/def :fulcro.client.primitives.defsc/query (s/or :template vector? :method list?)))
#?(:clj (s/def :fulcro.client.primitives.defsc/initial-state (s/or :template map? :method list?)))
#?(:clj (s/def :fulcro.client.primitives.defsc/css (s/or :template vector? :method list?)))
#?(:clj (s/def :fulcro.client.primitives.defsc/css-include (s/or :template (s/coll-of symbol? :kind vector?) :method list?)))
#?(:clj (s/def :fulcro.client.primitives.defsc/form-fields (s/or
                                                             :form-field-set (s/coll-of keyword? :kind set?)
                                                             :form-field-specs vector?)))

#?(:clj (s/def :fulcro.client.primitives.defsc/options (s/keys :opt-un [:fulcro.client.primitives.defsc/query
                                                                        :fulcro.client.primitives.defsc/ident
                                                                        :fulcro.client.primitives.defsc/initial-state
                                                                        :fulcro.client.primitives.defsc/css
                                                                        :fulcro.client.primitives.defsc/form-fields
                                                                        :fulcro.client.primitives.defsc/css-include])))

#?(:clj (s/def :fulcro.client.primitives.defsc/args (s/cat
                                                      :sym symbol?
                                                      :doc (s/? string?)
                                                      :arglist (s/and vector? #(<= 2 (count %) 5))
                                                      :options (s/? :fulcro.client.primitives.defsc/options)
                                                      :body (s/* any?))))
#?(:clj (s/def :fulcro.client.primitives.defsc/static #{'static}))
#?(:clj (s/def :fulcro.client.primitives.defsc/protocol-method list?))

#?(:clj (s/def :fulcro.client.primitives.defsc/protocols (s/* (s/cat :static (s/? :fulcro.client.primitives.defsc/static) :protocol symbol? :methods (s/+ :fulcro.client.primitives.defsc/protocol-method)))))

#?(:clj
   (defn- build-form [form-fields query]
     (cond
       (nil? form-fields) nil
       (set? form-fields) (let [valid-key?           (if (vector? query)
                                                       (legal-keys query)
                                                       (constantly true))
                                missing-form-config? (and (vector? query)
                                                       (not (some #(= "form-config-join" (if (symbol? %) (name %))) query)))]
                            (when-not (every? valid-key? form-fields)
                              (throw (ex-info ":form-fields include keywords that are not in the query" {})))
                            (when missing-form-config?
                              (throw (ex-info "Form fields are declared, but the query does not contain form-config-join" {})))
                            `(~'static fulcro.ui.form-state/IFormFields
                               (~'form-fields [~'this] ~form-fields)))
       (vector? form-fields) `(~'static fulcro.ui.forms/IForm
                                (~'form-spec [~'this] ~form-fields))
       :otherwise (throw (ex-info "Form fields must be a literal vector (if using forms) or a set (if using form-state)." {})))))

#?(:clj
   (defn build-css [thissym {css-method :method css-template :template} {include-method :method include-template :template}]
     (when (or css-method css-template include-method include-template)
       (let [local-form   (cond
                            css-template (if-not (vector? css-template)
                                           (throw (ex-info "css MUST be a vector of garden-syntax rules" {}))
                                           `(~'local-rules [~'_] ~css-template))
                            css-method (replace-and-validate-fn 'local-rules [thissym] 0 css-method 'css)
                            :else '(local-rules [_] []))
             include-form (cond
                            include-template (if-not (and (vector? include-template) (every? symbol? include-template))
                                               (throw (ex-info "css-include must be a vector of component symbols" {}))
                                               `(~'include-children [~'_] ~include-template))
                            include-method (replace-and-validate-fn 'include-children [thissym] 0 include-method 'css-include)
                            :else '(include-children [_] []))]
         `(~'static fulcro-css.css/CSS
            ~local-form
            ~include-form)))))

#?(:clj
   (defn defsc*
     [args]
     (if-not (s/valid? :fulcro.client.primitives.defsc/args args)
       (throw (ex-info "Invalid arguments"
                {:reason (str (-> (s/explain-data :fulcro.client.primitives.defsc/args args)
                                ::s/problems
                                first
                                :path) " is invalid.")})))
     (let [{:keys [sym doc arglist options body]} (s/conform :fulcro.client.primitives.defsc/args args)
           [thissym propsym computedsym csssym] arglist
           {:keys [ident query initial-state protocols form-fields css css-include]} options
           body                             (or body ['nil])
           ident-template-or-method         (into {} [ident]) ;clojure spec returns a map entry as a vector
           initial-state-template-or-method (into {} [initial-state])
           query-template-or-method         (into {} [query])
           css-template-or-method           (into {} [css])
           css-include-template-or-method   (into {} [css-include])
           has-css?                         (or css css-include)
           ; TODO: validate-css?                    (and (map? csssym) (:template css))
           validate-query?                  (and (:template query-template-or-method) (not (some #{'*} (:template query-template-or-method))))
           legal-key-checker                (if validate-query?
                                              (or (legal-keys (:template query-template-or-method)) #{})
                                              (complement #{}))
           parsed-protocols                 (when protocols (group-by :protocol (s/conform :fulcro.client.primitives.defsc/protocols protocols)))
           object-methods                   (when (contains? parsed-protocols 'Object) (get-in parsed-protocols ['Object 0 :methods]))
           lifecycle-methods                (make-lifecycle thissym options)
           addl-protocols                   (some->> (dissoc parsed-protocols 'Object)
                                              vals
                                              (map (fn [[v]]
                                                     (if (contains? v :static)
                                                       (concat ['static (:protocol v)] (:methods v))
                                                       (concat [(:protocol v)] (:methods v)))))
                                              (mapcat identity))
           ident-forms                      (build-ident thissym propsym ident-template-or-method legal-key-checker)
           state-forms                      (build-initial-state sym thissym initial-state-template-or-method legal-key-checker query-template-or-method false #_(vector? form-fields))
           query-forms                      (build-query-forms sym thissym propsym query-template-or-method)
           form-forms                       (build-form (some-> form-fields second) (:template query-template-or-method))
           css-forms                        (build-css thissym css-template-or-method css-include-template-or-method)
           render-forms                     (build-render sym thissym propsym computedsym csssym body)]
       (assert (or (nil? protocols) (s/valid? :fulcro.client.primitives.defsc/protocols protocols)) "Protocols must be valid protocol declarations")
       (when (and csssym (not (seq css-forms)))
         (throw (ex-info "You included a CSS argument, but there is no CSS localized to the component." {})))
       ;; TODO: Add CSS destructuring validation here? Must use dynamic loading of fulcro CSS IFF css is used, so that we
       ; don't have a hard dependency on it.
       ; You're at *compile time* in *Clojure*...you *cannot rely on components* that are defined because they might be only
       ; cljs artifacts.
       ; (when validate-css? (validate-css-destructuring csssym (:template css)))
       `(fulcro.client.primitives/defui ~(vary-meta sym assoc :once true)
          ~@addl-protocols
          ~@css-forms
          ~@state-forms
          ~@ident-forms
          ~@query-forms
          ~@form-forms
          ~@render-forms
          ~@lifecycle-methods
          ~@object-methods))))

#?(:clj
   (defmacro ^{:doc      "Define a stateful component. This macro emits a React UI class with a query,
   optional ident (if :ident is specified in options), optional initial state, optional css, lifecycle methods,
   and a render method. It can also cause the class to implement additional protocols that you specify. Destructuring is
   supported in the argument list.

   The template (data-only) versions do not have any arguments in scope
   The lambda versions have arguments in scope that make sense for those lambdas, as listed below:

   ```
   (defsc Component [this {:keys [db/id x] :as props} {:keys [onSelect] :as computed} css-classmap]
     {
      ;; stateful component options
      ;; query template is literal. Use the lambda if you have ident-joins or unions.
      :query [:db/id :x] ; OR (fn [] [:db/id :x]) ; this in scope
      ;; ident template is table name and ID property name
      :ident [:table/by-id :id] ; OR (fn [] [:table/by-id id]) ; this and props in scope
      ;; initial-state template is magic..see dev guide. Lambda version is normal.
      :initial-state {:x :param/x} ; OR (fn [params] {:x (:x params)}) ; this in scope
      :css [] ; garden css rules
      :css-include [] ; list of components that have CSS to compose towards root.

      ; React Lifecycle Methods (this in scope)
      :initLocalState            (fn [] ...) ; CAN BE used to call things as you might in a constructor. Return value is initial state.
      :shouldComponentUpdate     (fn [next-props next-state] ...)

      :componentDidUpdate        (fn [prev-props prev-state snapshot] ...) ; snapshot is optional, and is 16+. Is context for 15
      :componentDidMount         (fn [] ...)
      :componentWillUnmount      (fn [] ...)

      ;; DEPRECATED IN REACT 16 (to be removed in 17):
      :componentWillReceiveProps        (fn [next-props] ...)
      :componentWillUpdate              (fn [next-props next-state] ...)
      :componentWillMount               (fn [] ...)

      ;; Replacements for deprecated methods in React 16.3+
      :UNSAFE_componentWillReceiveProps (fn [next-props] ...)
      :UNSAFE_componentWillUpdate       (fn [next-props next-state] ...)
      :UNSAFE_componentWillMount        (fn [] ...)

      ;; ADDED for React 16:
      :componentDidCatch         (fn [error info] ...)
      :getSnapshotBeforeUpdate   (fn [prevProps prevState] ...)

      TODO:  :getDerivedStateFromProps  (fn [props state] ...)

      NOTE: shouldComponentUpdate should generally not be overridden other than to force it false so
      that other libraries can control the sub-dom. If you do want to implement it, then old props can
      be obtained from (prim/props this), and old state via (gobj/get (. this -state) \"fulcro$state\").

      ; Custom literal protocols (Object ok, too, to add arbitrary methods. Nothing automatically in scope.)
      :protocols [YourProtocol
                  (method [this] ...)]} ; nothing is automatically in scope
      ; BODY forms. May be omitted IFF there is an options map, in order to generate a component that is used only for queries/normalization.
      (dom/div #js {:onClick onSelect} x))
   ```

   See the Developer's Guide at book.fulcrologic.com for more details.
   "
               :arglists '([this dbprops computedprops]
                            [this dbprops computedprops local-css-classes])}
   defsc
     [& args]
     (let [location (str *ns* ":" (:line (meta &form)))]
       (try
         (defsc* args)
         (catch Exception e
           (throw (ex-info (str "Syntax Error at " location) {:cause e})))))))

(defmacro sc
  "Just like defsc, but returns the component instead. The arguments are the same, except do not supply a symbol:

  ```
  (let [C (prim/sc [this props] ...)] ...)
  ```
  "
  [& args]
  (let [t (with-meta (gensym "sc_") {:anonymous true})]
    `(do (defsc ~t ~@args) ~t)))

(defn integrate-ident
  "DEPRECATED: Use fulcro.client.mutations/integrate-ident* in your mutations instead."
  [state ident & named-parameters]
  {:pre [(map? state)]}
  (log/warn "integrate-ident is deprecated and will be removed in the future."
    "Please use fulcro.client.mutations/integrate-ident* in your mutations instead.")
  (apply util/__integrate-ident-impl__ state ident named-parameters))

(defn component-merge-query
  "Calculates the query that can be used to pull (or merge) a component with an ident
  to/from a normalized app database. Requires a tree of data that represents the instance of
  the component in question (e.g. ident will work on it)"
  [component object-data]
  (let [ident        (ident component object-data)
        object-query (get-query component)]
    [{ident object-query}]))

(defn- preprocess-merge
  "Does the steps necessary to honor the data merge technique defined by Fulcro with respect
  to data overwrites in the app database."
  [state-atom component object-data]
  (let [ident         (get-ident component object-data)
        object-query  (get-query component)
        object-query  (if (map? object-query) [object-query] object-query)
        base-query    (component-merge-query component object-data)
        ;; :fulcro/merge is way to make unions merge properly when joined by idents
        merge-query   [{:fulcro/merge base-query}]
        existing-data (get (db->tree base-query @state-atom @state-atom) ident {})
        marked-data   (mark-missing object-data object-query)
        merge-data    {:fulcro/merge {ident (util/deep-merge existing-data marked-data)}}]
    {:merge-query merge-query
     :merge-data  merge-data}))

(defn- is-atom?
  "Returns TRUE when x is an atom."
  [x]
  (instance? #?(:cljs cljs.core.Atom
                :clj  clojure.lang.Atom) x))

(defn integrate-ident!
  "DEPRECATED: Use fulcro.client.mutations/integrate-ident* in your mutations instead."
  [state ident & named-parameters]
  (log/warn "integrate-ident! is deprecated and will be removed in the future."
    "Please use fulcro.client.mutations/integrate-ident* in your mutations instead.")
  (assert (is-atom? state)
    "The state has to be an atom. Use 'integrate-ident' instead.")
  (apply swap! state util/__integrate-ident-impl__ ident named-parameters))

(defn merge-component
  "Given a state map of the application database, a component, and a tree of component-data: normalizes
   the tree of data and merges the component table entries into the state, returning a new state map.
   Since there is not an implied root, the component itself won't be linked into your graph (though it will
   remain correctly linked for its own consistency).
   Therefore, this function is just for dropping normalized things into tables
   when they themselves have a recursive nature. This function is useful when you want to create a new component instance
   and put it in the database, but the component instance has recursive normalized state. This is a basically a
   thin wrapper around `prim/tree->db`.

   See also integrate-ident, integrate-ident!, and merge-component!"
  [state-map component component-data]
  (if-let [top-ident (get-ident component component-data)]
    (let [query          [{top-ident (get-query component)}]
          state-to-merge {top-ident component-data}
          table-entries  (-> (tree->db query state-to-merge true)
                           (dissoc ::tables top-ident))]
      (util/deep-merge state-map table-entries))
    state-map))

(defn merge-component!
  "Normalize and merge a (sub)tree of application state into the application using a known UI component's query and ident.

  This utility function obtains the ident of the incoming object-data using the UI component's ident function. Once obtained,
  it uses the component's query and ident to normalize the data and place the resulting objects in the correct tables.
  It is also quite common to want those new objects to be linked into lists in other spot in app state, so this function
  supports optional named parameters for doing this. These named parameters can be repeated as many times as you like in order
  to place the ident of the new object into other data structures of app state.

  This function honors the data merge story for Fulcro: attributes that are queried for but do not appear in the
  data will be removed from the application. This function also uses the initial state for the component as a base
  for merge if there was no state for the object already in the database.

  This function will also trigger re-renders of components that directly render object merged, as well as any components
  into which you integrate that data via the named-parameters.

  This function is primarily meant to be used from things like server push and setTimeout/setInterval, where you're outside
  of the normal mutation story. Do not use this function within abstract mutations.

  - reconciler: A reconciler
  - component: The class of the component that corresponsds to the data. Must have an ident.
  - object-data: A map (tree) of data to merge. Will be normalized for you.
  - named-parameter: Post-processing ident integration steps. see integrate-ident!

  Any keywords that appear in ident integration steps will be added to the re-render queue.

  See also `fulcro.client.primitives/merge!`.
  "
  [reconciler component object-data & named-parameters]
  (if-not (has-ident? component)
    (log/error "merge-component!: component must implement Ident. Merge skipped.")
    (let [ident          (get-ident component object-data)
          reconciler     (if (contains? reconciler :reconciler) (:reconciler reconciler) reconciler)
          state          (app-state reconciler)
          data-path-keys (->> named-parameters (partition 2) (map second) flatten (filter keyword?) set vec)
          {:keys [merge-data merge-query]} (preprocess-merge state component object-data)]
      (merge! reconciler merge-data merge-query)
      (swap! state dissoc :fulcro/merge)
      ;; Use utils until we make smaller namespaces, requiring mutations would
      ;; cause circular dependency.
      (apply util/__integrate-ident-impl__ @state ident named-parameters)
      (p/queue! reconciler (conj data-path-keys ident))
      @state)))

(defn merge-alternate-unions
  "Walks the given query and calls (merge-fn parent-union-component union-child-initial-state) for each non-default element of a union that has initial app state.
  You probably want to use merge-alternate-union-elements[!] on a state map or app."
  [merge-fn root-component]
  (letfn [(walk-ast
            ([ast visitor]
             (walk-ast ast visitor nil))
            ([{:keys [children component type dispatch-key union-key key] :as parent-ast} visitor parent-union]
             (when (and component parent-union (= :union-entry type))
               (visitor component parent-union))
             (when children
               (doseq [ast children]
                 (cond
                   (= (:type ast) :union) (walk-ast ast visitor component) ; the union's component is on the parent join
                   (= (:type ast) :union-entry) (walk-ast ast visitor parent-union)
                   ast (walk-ast ast visitor nil))))))
          (merge-union [component parent-union]
            (let [default-initial-state   (and parent-union (has-initial-app-state? parent-union) (get-initial-state parent-union {}))
                  to-many?                (vector? default-initial-state)
                  component-initial-state (and component (has-initial-app-state? component) (get-initial-state component {}))]
              (when (and component component-initial-state parent-union (not to-many?) (not= default-initial-state component-initial-state))
                (merge-fn parent-union component-initial-state))))]
    (walk-ast
      (query->ast (get-query root-component))
      merge-union)))

;q: {:a (gq A) :b (gq B)
;is: (is A)  <-- default branch
;state:   { kw { id [:page :a]  }}
(defn merge-alternate-union-elements!
  "Walks the query and initial state of root-component and merges the alternate sides of unions with initial state into
  the application state database. See also `merge-alternate-union-elements`, which can be used on a state map and
  is handy for server-side rendering. This function side-effects on your app, and returns nothing."
  [app root-component]
  (merge-alternate-unions (partial merge-component! app) root-component))

(defn merge-alternate-union-elements
  "Just like merge-alternate-union-elements!, but usable from within mutations and on server-side rendering. Ensures
  that when a component has initial state it will end up in the state map, even if it isn't currently in the
  initial state of the union component (which can only point to one at a time)."
  [state-map root-component]
  (let [initial-state  (get-initial-state root-component nil)
        state-map-atom (atom state-map)
        merge-to-state (fn [comp tree] (swap! state-map-atom merge-component comp tree))
        _              (merge-alternate-unions merge-to-state root-component)
        new-state      @state-map-atom]
    new-state))


(defmacro with-parent-context
  "Wraps the given body with the correct internal bindings of the parent so that Fulcro internals
  will work when that body is embedded in unusual ways (e.g. as the body in a child-as-a-function
  React pattern)."
  [outer-parent & body]
  (if-not (:ns &env)
    `(do ~@body)
    `(let [parent# ~outer-parent
           r#      (or fulcro.client.primitives/*reconciler* (fulcro.client.primitives/get-reconciler parent#))
           d#      (or fulcro.client.primitives/*depth* (inc (fulcro.client.primitives/depth parent#)))
           s#      (or fulcro.client.primitives/*shared* (fulcro.client.primitives/shared parent#))
           i#      (or fulcro.client.primitives/*instrument* (fulcro.client.primitives/instrument parent#))
           p#      (or fulcro.client.primitives/*parent* parent#)]
       (binding [fulcro.client.primitives/*reconciler* r#
                 fulcro.client.primitives/*depth*      d#
                 fulcro.client.primitives/*shared*     s#
                 fulcro.client.primitives/*instrument* i#
                 fulcro.client.primitives/*parent*     p#]
         ~@body))))

