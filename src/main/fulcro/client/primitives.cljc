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
               [goog.log :as glog]
               [goog.object :as gobj]])
               [clojure.core.async :as async]
               [clojure.set :as set]
               [fulcro.history :as hist]
               [fulcro.client.logging :as log]
               [fulcro.tempid :as tempid]
               [fulcro.transit :as transit]
               [clojure.zip :as zip]
               [fulcro.client.impl.data-targeting :as targeting]
               [fulcro.client.impl.protocols :as p]
               [fulcro.client.impl.parser :as parser]
               [fulcro.util :as util]
               [clojure.walk :as walk :refer [prewalk]]
               [clojure.string :as str]
               [clojure.spec.alpha :as s]
    #?(:clj
               [clojure.future :refer :all])
               [cognitect.transit :as t])
  #?(:clj
           (:import [java.io Writer])
     :cljs (:import [goog.debug Console])))

(declare app-state app-root tempid? normalize-query focus-query* ast->query query->ast transact! remove-root! component?
  integrate-ident)

(defprotocol Ident
  (ident [this props] "Return the ident for this component"))

(defprotocol IQueryParams
  (params [this] "Return the query parameters"))

(defprotocol IQuery
  (query [this] "Return the component's unbound static query"))

;; DEPRECATED: Unless someone can give me a compelling case to keep this, I'm dropping it:
(defprotocol ILocalState
  (-set-state! [this new-state] "Set the component's local state")
  (-get-state [this] "Get the component's local state")
  (-get-rendered-state [this] "Get the component's rendered local state")
  (-merge-pending-state! [this] "Get the component's pending local state"))

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

(defn has-query-params?
  #?(:cljs {:tag boolean})
  [component]
  #?(:clj  (if (fn? component)
             (some? (-> component meta :params))
             (let [class (cond-> component (component? component) class)]
               (extends? IQueryParams class)))
     :cljs (implements? IQueryParams component)))

(defn get-initial-state
  "Get the initial state of a component. Needed because calling the protocol method from a defui component in clj will not work as expected."
  [class params]
  #?(:clj  (when-let [initial-state (-> class meta :initial-state)]
             (initial-state class params))
     :cljs (when (implements? InitialAppState class)
             (initial-state class params))))

(s/def ::remote keyword?)
(s/def ::ident util/ident?)
(s/def ::query vector?)
(s/def ::transaction (s/every #(or (keyword? %) (util/mutation? %))
                       :kind vector?))
(s/def ::component-or-reconciler vector?)
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

(defn- validate-statics [dt]
  (when-let [invalid (some #{"Ident" "IQuery" "IQueryParams"}
                       (map #(-> % str (str/split #"/") last)
                         (filter symbol? dt)))]
    (throw
      #?(:clj  (IllegalArgumentException.
                 (str invalid " protocol declaration must appear with `static`."))
         :cljs (js/Error.
                 (str invalid " protocol declaration must appear with `static`."))))))

(def lifecycle-sigs
  '{initLocalState            [this]
    shouldComponentUpdate     [this next-props next-state]
    componentWillReceiveProps [this next-props]
    componentWillUpdate       [this next-props next-state]
    componentDidUpdate        [this prev-props prev-state]
    componentWillMount        [this]
    componentDidMount         [this]
    componentWillUnmount      [this]
    render                    [this]})

(defn validate-sig [[name sig :as method]]
  (let [sig' (get lifecycle-sigs name)]
    (assert (= (count sig') (count sig))
      (str "Invalid signature for " name " got " sig ", need " sig'))))

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
       'componentWillMount
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

(def reshape-map
  {:reshape
   {'initLocalState
    (fn [[name [this :as args] & body]]
      `(~name ~args
         (let [ret# (do ~@body)]
           (cljs.core/js-obj "fulcro$state" ret#))))
    'componentWillReceiveProps
    (fn [[name [this next-props :as args] & body]]
      `(~name [this# next-props#]
         (let [~this this#
               ~next-props (fulcro.client.primitives/-next-props next-props# this#)]
           ~@body)))
    'componentWillUpdate
    (fn [[name [this next-props next-state :as args] & body]]
      `(~name [this# next-props# next-state#]
         (let [~this this#
               ~next-props (fulcro.client.primitives/-next-props next-props# this#)
               ~next-state (or (goog.object/get next-state# "fulcro$pendingState")
                             (goog.object/get next-state# "fulcro$state"))
               ret# (do ~@body)]
           (when (cljs.core/implements? fulcro.client.primitives/Ident this#)
             (let [ident#      (fulcro.client.primitives/ident this# (fulcro.client.primitives/props this#))
                   next-ident# (fulcro.client.primitives/ident this# ~next-props)]
               (when (not= ident# next-ident#)
                 (let [idxr# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
                   (when-not (nil? idxr#)
                     (swap! (:indexes idxr#)
                       (fn [indexes#]
                         (-> indexes#
                           (update-in [:ref->components ident#] disj this#)
                           (update-in [:ref->components next-ident#] (fnil conj #{}) this#)))))))))
           (fulcro.client.primitives/merge-pending-props! this#)
           (fulcro.client.primitives/merge-pending-state! this#)
           ret#)))
    'componentDidUpdate
    (fn [[name [this prev-props prev-state :as args] & body]]
      `(~name [this# prev-props# prev-state#]
         (let [~this this#
               ~prev-props (fulcro.client.primitives/-prev-props prev-props# this#)
               ~prev-state (goog.object/get prev-state# "fulcro$previousState")]
           ~@body
           (fulcro.client.primitives/clear-prev-props! this#))))
    'componentWillMount
    (fn [[name [this :as args] & body]]
      `(~name [this#]
         (let [~this this#
               reconciler# (fulcro.client.primitives/get-reconciler this#)
               indexer# (get-in reconciler# [:config :indexer])]
           (when-not (nil? indexer#)
             (fulcro.client.impl.protocols/index-component! indexer# this#))
           ~@body)))
    'componentDidMount
    (fn [[name [this :as args] & body]]
      `(~name [this#]
         (let [~this this#
               reconciler# (fulcro.client.primitives/get-reconciler this#)
               lifecycle# (get-in reconciler# [:config :lifecycle])]
           (goog.object/set this# "fulcro$mounted" true)
           (when-not (nil? lifecycle#)
             (lifecycle# this# :mount))
           ~@body)))
    'componentWillUnmount
    (fn [[name [this :as args] & body]]
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
           (when-not (nil? lifecycle#)
             (lifecycle# this# :unmount))
           (when-not (nil? indexer#)
             (fulcro.client.impl.protocols/drop-component! indexer# this#))
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
     ([this# next-props# next-state#]
       (let [next-children#     (. next-props# -children)
             next-props#        (goog.object/get next-props# "fulcro$value")
             next-props#        (cond-> next-props#
                                  (instance? FulcroProps next-props#) unwrap)
             current-props#     (fulcro.client.primitives/props this#)
             ; a parent could send in stale props due to a component-local state change..make sure we don't use them. (Props have a timestamp on metadata)
             next-props-stale?# (> (get-basis-time current-props#) (get-basis-time next-props#))
             props-changed?#    (and
                                  (not next-props-stale?#)
                                  (not= current-props# next-props#))
             state-changed?#    (and (.. this# ~'-state)
                                  (not= (goog.object/get (. this# ~'-state) "fulcro$state")
                                    (goog.object/get next-state# "fulcro$state")))
             children-changed?# (not= (.. this# -props -children)
                                  next-children#)]
         (or props-changed?# state-changed?# children-changed?#)))
     ~'componentWillUpdate
     ([this# next-props# next-state#]
       (when (cljs.core/implements? fulcro.client.primitives/Ident this#)
         (let [ident#      (fulcro.client.primitives/ident this# (fulcro.client.primitives/props this#))
               next-ident# (fulcro.client.primitives/ident this# (fulcro.client.primitives/-next-props next-props# this#))]
           (when (not= ident# next-ident#)
             (let [idxr# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
               (when-not (nil? idxr#)
                 (swap! (:indexes idxr#)
                   (fn [indexes#]
                     (-> indexes#
                       (update-in [:ref->components ident#] disj this#)
                       (update-in [:ref->components next-ident#] (fnil conj #{}) this#)))))))))
       (fulcro.client.primitives/merge-pending-props! this#)
       (fulcro.client.primitives/merge-pending-state! this#))
     ~'componentDidUpdate
     ([this# prev-props# prev-state#]
       (fulcro.client.primitives/clear-prev-props! this#))
     ~'componentWillMount
     ([this#]
       (let [indexer# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
         (when-not (nil? indexer#)
           (fulcro.client.impl.protocols/index-component! indexer# this#))))
     ~'componentDidMount
     ([this#] (goog.object/set this# "fulcro$mounted" true))
     ~'componentWillUnmount
     ([this#]
       (let [r#       (fulcro.client.primitives/get-reconciler this#)
             cfg#     (:config r#)
             st#      (:state cfg#)
             indexer# (:indexer cfg#)]
         (goog.object/set this# "fulcro$mounted" false)
         (when (and (not (nil? st#))
                 (get-in @st# [:fulcro.client.primitives/queries this#]))
           (swap! st# update-in [:fulcro.client.primitives/queries] dissoc this#))
         (when-not (nil? indexer#)
           (fulcro.client.impl.protocols/drop-component! indexer# this#))))}})

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

#?(:clj
   (defn- add-proto-methods* [pprefix type type-sym [f & meths :as form]]
     (let [pf          (str pprefix (name f))
           emit-static (when (-> type-sym meta :static)
                         `(~'js* "/** @nocollapse */"))]
       (if (vector? (first meths))
         ;; single method case
         (let [meth meths]
           [`(do
               ~emit-static
               (set! ~(#'cljs.core/extend-prefix type-sym (str pf "$arity$" (count (first meth))))
                 ~(with-meta `(fn ~@(#'cljs.core/adapt-proto-params type meth)) (meta form))))])
         (map (fn [[sig & body :as meth]]
                `(do
                   ~emit-static
                   (set! ~(#'cljs.core/extend-prefix type-sym (str pf "$arity$" (count sig)))
                     ~(with-meta `(fn ~(#'cljs.core/adapt-proto-params type meth)) (meta form)))))
           meths)))))

#?(:clj (intern 'cljs.core 'add-proto-methods* add-proto-methods*))

#?(:clj
   (defn- proto-assign-impls [env resolve type-sym type [p sigs]]
     (#'cljs.core/warn-and-update-protocol p type env)
     (let [psym        (resolve p)
           pprefix     (#'cljs.core/protocol-prefix psym)
           skip-flag   (set (-> type-sym meta :skip-protocol-flag))
           static?     (-> p meta :static)
           type-sym    (cond-> type-sym
                         static? (vary-meta assoc :static true))
           emit-static (when static?
                         `(~'js* "/** @nocollapse */"))]
       (if (= p 'Object)
         (#'cljs.core/add-obj-methods type type-sym sigs)
         (concat
           (when-not (skip-flag psym)
             (let [{:keys [major minor qualifier]} cljs.util/*clojurescript-version*]
               (if (and (== major 1) (== minor 9) (>= qualifier 293))
                 [`(do
                     ~emit-static
                     (set! ~(#'cljs.core/extend-prefix type-sym pprefix) cljs.core/PROTOCOL_SENTINEL))]
                 [`(do
                     ~emit-static
                     (set! ~(#'cljs.core/extend-prefix type-sym pprefix) true))])))
           (mapcat
             (fn [sig]
               (if (= psym 'cljs.core/IFn)
                 (#'cljs.core/add-ifn-methods type type-sym sig)
                 (#'cljs.core/add-proto-methods* pprefix type type-sym sig)))
             sigs))))))

#?(:clj (intern 'cljs.core 'proto-assign-impls proto-assign-impls))

#?(:clj (defn- extract-static-methods [protocols]
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
             `(set! (. ~obj ~(symbol (str "-" field))) ~value))]
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
                                      (merge {:jsdoc ["@constructor"]}
                                        (meta name)
                                        (when docstring
                                          {:doc docstring})))
                               []
                               (this-as this#
                                 (.apply js/React.Component this# (js-arguments))
                                 (if-not (nil? (.-initLocalState this#))
                                   (set! (.-state this#) (.initLocalState this#))
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
          (set! (.. ~name -prototype -fulcro$isComponent) true)
          ~@(map #(field-set! name %) (:fields statics))
          (specify! ~name
            ~@(mapv #(cond-> %
                       (symbol? %) (vary-meta assoc :static true)) (:protocols statics)))
          (specify! (. ~name ~'-prototype) ~@(:protocols statics))
          (set! (.-cljs$lang$type ~name) true)
          (set! (.-cljs$lang$ctorStr ~name) ~(str fqn))
          (set! (.-cljs$lang$ctorPrWriter ~name)
            (fn [this# writer# opt#]
              (cljs.core/-write writer# ~(str fqn))))
          ;; TODO: here is where we could emit uses of the statics in a try/catch so the Closure will not collapse them
          )))))

(defmacro defui [name & forms]
  (if (boolean (:ns &env))
    (defui* name forms &env)
    #?(:clj (defui*-clj name forms))))

(defmacro ui
  [& forms]
  (let [t (with-meta (gensym "ui_") {:anonymous true})]
    `(do (defui ~t ~@forms) ~t)))


;; =============================================================================
;; Globals & Dynamics

(def ^:private roots (atom {}))
(def ^{:dynamic true} *raf* nil)
(def ^{:dynamic true :private true} *reconciler* nil)
(def ^{:dynamic true :private true} *parent* nil)
(def ^{:dynamic true :private true} *shared* nil)
(def ^{:dynamic true :private true} *instrument* nil)
(def ^{:dynamic true :private true} *depth* 0)

#?(:clj
   (defn- munge-component-name [x]
     (let [ns-name (-> x meta :component-ns)
           cl-name (-> x meta :component-name)]
       (munge
         (str (str/replace (str ns-name) "." "$") "$" cl-name)))))

#?(:clj
   (defn- compute-react-key [cl props]
     (when-let [idx (-> props meta :om-path)]
       (str (munge-component-name cl) "_" idx))))

#?(:cljs
   (defn- compute-react-key [cl props]
     (if-let [rk (:react-key props)]
       rk
       (if-let [idx (-> props meta :om-path)]
         (str (. cl -name) "_" idx)
         js/undefined))))


(defn component?
  "Returns true if the argument is a component."
  #?(:cljs {:tag boolean})
  [x]
  (if-not (nil? x)
    #?(:clj  (or (instance? fulcro.client.impl.protocols.IReactComponent x)
               (satisfies? p/IReactComponent x))
       :cljs (true? (. x -fulcro$isComponent)))
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

(defn- get-prop
  "PRIVATE: Do not use"
  [c k]
  #?(:clj  (get (:props c) k)
     :cljs (gobj/get (.-props c) k)))

#?(:cljs (deftype ^:private FulcroProps [props basis-t]))

#?(:cljs
   (defn- om-props [props basis-t]
     (FulcroProps. props basis-t)))

#?(:cljs
   (defn- om-props-basis [om-props]
     (.-basis-t om-props)))

#?(:cljs (def ^:private nil-props (om-props nil -1)))

#?(:cljs
   (defn- get-props*
     [x k]
     (if (nil? x)
       nil-props
       (let [y (gobj/get x k)]
         (if (nil? y)
           nil-props
           y)))))

#?(:cljs
   (defn- get-prev-props [x]
     (get-props* x "fulcro$prev$value")))

#?(:cljs
   (defn- get-next-props [x]
     (get-props* x "fulcro$next$value")))

#?(:cljs
   (defn- get-props [x]
     (get-props* x "fulcro$value")))

#?(:cljs
   (defn- set-prop!
     "PRIVATE: Do not use"
     [c k v]
     (gobj/set (.-props c) k v)))

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
  (get-prop c #?(:clj  :fulcro$reconciler
                 :cljs "fulcro$reconciler")))

#?(:cljs
   (defn- unwrap [om-props]
     (.-props om-props)))

#?(:cljs
   (defn- props*
     ([x y]
      (max-key om-props-basis x y))
     ([x y z]
      (max-key om-props-basis x (props* y z)))))

#?(:cljs
   (defn- prev-props*
     ([x y]
      (min-key om-props-basis x y))
     ([x y z]
      (min-key om-props-basis
        (props* x y) (props* y z)))))

#?(:cljs
   (defn -prev-props [prev-props component]
     (let [cst   (.-state component)
           props (.-props component)]
       (unwrap
         (prev-props*
           (props* (get-props prev-props) (get-prev-props cst))
           (props* (get-props cst) (get-props props)))))))

#?(:cljs
   (defn -next-props [next-props component]
     (unwrap
       (props*
         (-> component .-props get-props)
         (get-props next-props)
         (-> component .-state get-next-props)))))

#?(:cljs
   (defn- merge-pending-props! [c]
     {:pre [(component? c)]}
     (let [cst     (. c -state)
           props   (.-props c)
           pending (gobj/get cst "fulcro$next$value")
           prev    (props* (get-props cst) (get-props props))]
       (gobj/set cst "fulcro$prev$value" prev)
       (when-not (nil? pending)
         (gobj/remove cst "fulcro$next$value")
         (gobj/set cst "fulcro$value" pending)))))

#?(:cljs
   (defn- clear-prev-props! [c]
     (gobj/remove (.-state c) "fulcro$prev$value")))

#?(:cljs
   (defn- t
     "Get basis t value for when the component last read its props from
      the global state."
     [c]
     (om-props-basis
       (props*
         (-> c .-props get-props)
         (-> c .-state get-props)))))

(defn- parent
  "Returns the parent component."
  [component]
  (get-prop component #?(:clj  :fulcro$parent
                         :cljs "fulcro$parent")))

(defn depth
  "PRIVATE: Returns the render depth (a integer) of the component relative to
   the mount root."
  [component]
  (when (component? component)
    (get-prop component #?(:clj  :fulcro$depth
                           :cljs "fulcro$depth"))))

(defn react-key
  "Returns the components React key."
  [component]
  (get-prop component #?(:clj  :fulcro$reactKey
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
     ;; When force updating we write temporarily props into state to avoid bogus
     ;; complaints from React. We record the basis T of the reconciler to determine
     ;; if the props recorded into state are more recent - props will get updated
     ;; when React actually re-renders the component.
     (unwrap
       (props*
         (-> component .-props get-props)
         (-> component .-state get-props)))))

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
   (let [cst (if #?(:clj  (satisfies? ILocalState component)
                    :cljs (implements? ILocalState component))
               (-get-state component)
               #?(:clj  @(:state component)
                  :cljs (when-let [state (. component -state)]
                          (or (gobj/get state "fulcro$pendingState")
                            (gobj/get state "fulcro$state")))))]
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
  "Given a mounted component with assigned props, return the ident for the
   component. 2-arity version works on client or server using a component and
   explicit props."
  ([x]
   {:pre [(component? x)]}
   (let [m (props x)]
     (assert (not (nil? m)) "get-ident invoked on component with nil props")
     (ident x m)))
  ([class props]
    #?(:clj  (when-let [ident (-> class meta :ident)]
               (ident class props))
       :cljs (when (implements? Ident class)
               (ident class props)))))

(defn- var? [x]
  (and (symbol? x)
    #?(:clj  (.startsWith (str x) "?")
       :cljs (gstring/startsWith (str x) "?"))))

(defn- var->keyword [x]
  (keyword (.substring (str x) 1)))

(defn- replace-var [expr params]
  (if (var? expr)
    (get params (var->keyword expr) expr)
    expr))

(defn- bind-query [query params]
  (let [qm  (meta query)
        tr  (map #(bind-query % params))
        ret (cond
              (seq? query) (apply list (into [] tr query))
              #?@(:clj [(instance? clojure.lang.IMapEntry query) (into [] tr query)])
              (coll? query) (into (empty query) tr query)
              :else (replace-var query params))]
    (cond-> ret
      (and qm #?(:clj  (instance? clojure.lang.IObj ret)
                 :cljs (satisfies? IMeta ret)))
      (with-meta qm))))


(defn query-id
  "Returns a string ID for the query of the given class with qualifier"
  [class qualifier]
  (if (nil? class)
    (log/error "Query ID received no class (if you see this warning, it probably means metadata was lost on your query)" (ex-info "" {}))
    (when-let [classname #?(:clj (-> (str (-> class meta :component-ns) "." (-> class meta :component-name))
                                   (str/replace "." "$")
                                   (str/replace "-" "_"))
                            :cljs (.-name class))]
      (str classname (when qualifier (str "$" qualifier))))))

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
                              :fulcro$path       (-> props meta :om-path)
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
                  ref (cond-> ref (keyword? ref) str)
                  t   (if-not (nil? *reconciler*)
                        (get-current-time *reconciler*)
                        0)]
              (create-element class
                #js {:key               key
                     :ref               ref
                     :fulcro$reactKey   key
                     :fulcro$value      (om-props props t)
                     :fulcro$path       (-> props meta :om-path)
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

(defn denormalize-query
  "Takes a state map that may contain normalized queries and a query ID. Returns the stored query or nil."
  [state-map ID]
  (let [get-stored-query (fn [id] (get-in state-map [::queries id :query]))]
    (when-let [normalized-query (get-stored-query ID)]
      (prewalk (fn [ele]
                 (if-let [q (and (string? ele) (get-stored-query ele))]
                   q
                   ele)) normalized-query))))

(defn get-query-params
  "get the declared static query params on a given class"
  [class]
  (when (has-query-params? class)
    #?(:clj  ((-> class meta :params) class)
       :cljs (params class))))

(defn get-query-by-id [state-map class queryid]
  (let [static-params (get-query-params class)
        query         (or (denormalize-query state-map queryid) (get-static-query class))
        params        (get-in state-map [::queries queryid :params] static-params)]
    (with-meta (bind-query query params) {:component class
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
  (get-prop component #?(:clj  :fulcro$queryid
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
             (if-let [{:keys [queryid]} (meta ele)]
               queryid
               ele)) element))

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
            (let [parameterized? (list? ele)
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
  [state-map ui-factory-class-or-queryid {:keys [query params]}]
  (let [queryid (cond
                  (nil? ui-factory-class-or-queryid) nil
                  (string? ui-factory-class-or-queryid) ui-factory-class-or-queryid
                  (some-> ui-factory-class-or-queryid meta (contains? :queryid)) (some-> ui-factory-class-or-queryid meta :queryid)
                  :otherwise (query-id ui-factory-class-or-queryid nil))]
    (if (string? queryid)
      (do
        ; we have to dissoc the old one, because normalize won't overwrite by default
        (let [new-state (normalize-query (update state-map ::queries dissoc queryid) (with-meta query {:queryid queryid}))
              params    (get-in new-state [::queries queryid :params] params)]
          (if params
            (assoc-in new-state [::queries queryid :params] params)
            new-state)))
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

(defn- path
  "Returns the component's data path."
  [c]
  (get-prop c #?(:clj  :fulcro$path
                 :cljs "fulcro$path")))

(defn- normalize* [query data refs union-seen]
  (cond
    (= '[*] query) data

    ;; union case
    (map? query)
    (let [class         (-> query meta :component)
          ident #?(:clj (when-let [ident (-> class meta :ident)]
                          (ident class data))
                   :cljs (when (implements? Ident class)
                           (ident class data)))]
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
                               :cljs (ident class v))]
                      (swap! refs update-in [(first i) (second i)] merge x)
                      (recur (next q) (assoc ret k i)))
                    (recur (next q) (assoc ret k x))))

                ;; normalize many
                (vector? v)
                (let [xs (into [] (map #(normalize* sel % refs union-entry)) v)]
                  (if-not (or (nil? class) (not #?(:clj  (-> class meta :ident)
                                                   :cljs (implements? Ident class))))
                    (let [is (into [] (map #?(:clj  #((-> class meta :ident) class %)
                                              :cljs #(ident class %))) xs)]
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
        (let [{props false joins true} (group-by #(or (util/join? %)
                                                    (util/ident? %)
                                                    (and (seq? %)
                                                      (util/ident? (first %))))
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
  "Replaces all om-tempids in app-state with the ids returned by the server."
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

(defn fallback-query [query resp]
  "Filters out everything from the query that is not a fallback mutation.
  Returns nil if the resulting expression is empty."
  (let [symbols-to-find #{'tx/fallback 'fulcro.client.data-fetch/fallback}
        ast             (query->ast query)
        children        (:children ast)
        new-children    (->> children
                          (filter (fn [child] (contains? symbols-to-find (:dispatch-key child))))
                          (map (fn [ast] (update ast :params assoc :execute true :error resp))))
        new-ast         (assoc ast :children new-children)
        fallback-query  (ast->query new-ast)]
    (when (not-empty fallback-query)
      fallback-query)))

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
  (reduce (fn [acc [key new-value]]
            (let [existing-value (get acc key)]
              (cond
                (or (= key ::tempids) (= key :tempids) (= key ::not-found)) acc
                (= new-value ::not-found) (dissoc acc key)
                (and (util/ident? new-value) (= ::not-found (second new-value))) acc
                (leaf? new-value) (assoc acc key (sweep-one new-value))
                (and (map? existing-value) (map? new-value)) (update acc key sweep-merge new-value)
                :else (assoc acc key (sweep new-value))))
            ) target source))

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

(defn- merge-novelty!
  [reconciler state res query]
  (let [config (:config reconciler)
        [idts res'] (sift-idents res)
        res'   (if (:normalize config)
                 (tree->db
                   (or query (:root @(:state reconciler)))
                   res' true)
                 res')]
    (-> state
      (merge-mutation-joins query res')
      (merge-idents config idts query)
      ((:merge-tree config) res'))))

(defn get-tempids [m] (or (get m :tempids) (get m ::tempids)))

(defn merge*
  "Internal implementation of merge. Given a reconciler, state, result, and query returns a map of the:

  `:keys` to refresh
  `:next` state
  and `::tempids` that need to be migrated"
  [reconciler state res query]
  {:keys     (into [] (remove symbol?) (keys res))
   :next     (merge-novelty! reconciler state res query)
   ::tempids (->> (filter (comp symbol? first) res)
               (map (comp get-tempids second))
               (reduce merge {}))})

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
                              (str "malformed Ident. An ident must be a vector of "
                                "two elements (a keyword and an EDN value). Check "
                                "the Ident implementation of component `"
                                (.. c -constructor -displayName) "`.")))
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
    (select-keys config [:state :shared :parser :pathopt])))

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
  #?(:clj  (and (component? x) @(get-prop x :fulcro$mounted?))
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
                   value       (get (parser env query) id)]
               (if (and has-tempid? (or (nil? value) (empty? value)))
                 ::no-ident                                 ; tempid remap happened...cannot do targeted props until full re-render
                 value)
               ))]
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
   (defn should-update?
     "Invoke the lifecycle method on the component to see if it would recommend an update given the next-props or next-props
     and next-state"
     ([c next-props]
      (should-update? c next-props nil))
     ([c next-props next-state]
      {:pre [(component? c)]}
      (.shouldComponentUpdate c
        #js {"fulcro$value" next-props}
        #js {"fulcro$state" next-state}))))

#?(:cljs
   (defn force-update
     "An exception-protected React .forceUpdate"
     ([c cb]
      (try
        (.forceUpdate c cb)
        (catch :default e
          (js/console.log "Component" c "threw an exception while rendering " e))))
     ([c]
      (force-update c nil))))

#?(:cljs
   (defn- update-props!
     "Store the given props onto the component so that when the factory is called (via forceUpdate) they can be used as the new
     props for the rendering of that component."
     [c next-props]
     {:pre [(component? c)]}
     ;; We cannot write directly to props, React will complain
     (doto (.-state c)
       (gobj/set "fulcro$next$value"
         (om-props next-props (get-current-time (get-reconciler c)))))))

#?(:cljs
   (defn- update-component!
     "Force an update of a component using the given new props, skipping the render from root. This will also update the
     recorded reconciler basis time of the props."
     [c next-props]
     {:pre [(component? c)]}
     (update-props! c next-props)
     (force-update c)))

(defrecord Reconciler [config state history]
  #?(:clj  clojure.lang.IDeref
     :cljs IDeref)
  #?(:clj  (deref [this] @(:state config))
     :cljs (-deref [_] @(:state config)))

  p/IReconciler
  (tick! [_] (swap! state update :t inc))
  (basis-t [_] (:t @state))
  (get-history [_] history)

  (add-root! [this root-class target options]
    (let [ret          (atom nil)
          rctor        (factory root-class)
          guid #?(:clj (java.util.UUID/randomUUID)
                  :cljs (random-uuid))]
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
                      (let [sel (get-query rctor (-> config :state deref))]
                        (assert (or (nil? sel) (vector? sel))
                          "Application root query must be a vector")
                        (if-not (nil? sel)
                          (let [env          (to-env config)
                                raw-props    ((:parser config) env sel)
                                current-time (get-current-time this)
                                v            (add-basis-time sel raw-props current-time)]
                            (when-not (empty? v)
                              (renderf v)))
                          (renderf @(:state config)))))]
        (swap! state merge
          {:target target :render parsef :root root-class
           :remove (fn remove-fn []
                     (remove-watch (:state config) (or target guid))
                     (swap! state
                       #(-> %
                          (dissoc :target) (dissoc :render) (dissoc :root)
                          (dissoc :remove)))
                     (when-not (nil? target)
                       ((:root-unmount config) target)))})
        (add-watch (:state config) (or target guid)
          (fn add-fn [_ _ _ _]
            #?(:cljs
               (if-not (has-query? root-class)
                 (queue-render! parsef)
                 (do
                   (p/tick! this)
                   (schedule-render! this))))))
        (parsef)
        (when-let [sel (get-query rctor (-> config :state deref))]
          (let [env  (to-env config)
                snds (gather-sends env sel (:remotes config) 0)]
            (when-not (empty? snds)
              (when-let [send (:send config)]
                (send snds
                  (fn send-cb
                    ([resp]
                     (merge! this resp nil)
                     (renderf ((:parser config) env sel)))
                    ([resp query]
                     (merge! this resp query)
                     (renderf ((:parser config) env sel)))
                    ([resp query remote]
                     (when-not (nil? remote)
                       (p/queue! this (keys resp) remote))
                     (merge! this resp query remote)
                     (p/reconcile! this remote))))))))
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
    (let [st             @state
          q              (if-not (nil? remote)
                           (get-in st [:remote-queue remote])
                           (:queue st))
          rendered-root? (atom false)
          render-root    (fn []
                           (if-let [do-render (:render st)]
                             (when-not @rendered-root?
                               (reset! rendered-root? true)
                               (do-render))
                             (log/error "Render skipped. Renderer was nil. Possibly a hot code reload?")))]
      (swap! state update-in [:queued] not)
      (if (not (nil? remote))
        (swap! state assoc-in [:remote-queue remote] [])
        (swap! state assoc :queue []))
      (if (empty? q)                                        ;3ms average keypress overhead with path-opt optimizations and incremental
        ;; TODO: need to move root re-render logic outside of batching logic
        (render-root)
        (let [cs   (transduce
                     (map #(p/key->components (:indexer config) %))
                     #(into %1 %2) #{} q)
              env  (assoc (to-env config) :reconciler this)
              root (:root @state)]
          #?(:cljs
             (doseq [c ((:optimize config) cs)]             ; sort by depth
               (let [current-time   (get-current-time this)
                     component-time (t c)
                     props-change?  (> current-time component-time)]
                 (when (mounted? c)
                   (let [computed       (get-computed (props c))
                         next-raw-props (if (has-query? c)
                                          (add-basis-time (get-query c @state) (fulcro-ui->props env c) current-time)
                                          (add-basis-time (fulcro-ui->props env c) current-time))
                         force-root?    (= ::no-ident next-raw-props) ; screw focused query...
                         next-props     (when-not force-root? (fulcro.client.primitives/computed next-raw-props computed))]
                     (if force-root?
                       (do
                         (force-update c)                   ; in case it was just a state update on that component, shouldComponentUpdate of root would keep it from working
                         (render-root))                     ; NOTE: This will update time on all components, so the rest of the doseq will quickly short-circuit
                       (do
                         (when (and (exists? (.-componentWillReceiveProps c))
                                 (has-query? root)
                                 props-change?)
                           (let [next-props (if (nil? next-props)
                                              (when-let [props (props c)]
                                                props)
                                              next-props)]
                             ;; `componentWilReceiveProps` is always called before `shouldComponentUpdate`
                             (.componentWillReceiveProps c
                               #js {:fulcro$value (om-props next-props (get-current-time this))})))
                         (when (should-update? c next-props (get-state c))
                           (if-not (nil? next-props)
                             (update-component! c next-props)
                             (force-update c))))))))))))))

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
     :lifecycle    - A function (fn [component event]) that is called when react component s and either :mount or :unmount. Useful for debugging tools.
     :tx-listen    - a function of 2 arguments that will listen to transactions.
                     The first argument is the parser's env map also containing
                     the old and new state. The second argument is a history-step (see history). It also contains
                     a couple of legacy fields for bw compatibility with 1.0."
  [{:keys [state shared shared-fn
           parser normalize
           send merge-sends remotes
           merge-tree merge-ident
           optimize lifecycle
           root-render root-unmount
           migrate
           instrument tx-listen
           history]
    :or   {merge-sends  #(merge-with into %1 %2)
           remotes      [:remote]
           history      200
           lifecycle    nil
           optimize     (fn depth-sorter [cs] (sort-by depth cs))
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
                         :optimize    optimize
                         :normalize   (or (not norm?) normalize)
                         :root-render root-render :root-unmount root-unmount
                         :pathopt     true
                         :migrate     migrate
                         :lifecycle   lifecycle
                         :instrument  instrument :tx-listen tx-listen}
                        (atom {:queue        []
                               :remote-queue {}
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
          follow-on-reads    (filter keyword? tx)
          tx-time            (get-current-time reconciler)
          snds               (gather-sends env tx (:remotes cfg) tx-time)
          new-state          @(:state cfg)
          xs                 (cond-> declared-refreshes
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
   (let [tx (cond-> tx
              (and (component? x) (satisfies? Ident x))
              (annotate-mutations (get-ident x)))]
     (cond
       (reconciler? x) (transact* x nil nil tx)
       (not (has-query? x)) (do
                              (when (some-hasquery? x) (log/error
                                                         (str "transact! should be called on a component"
                                                           "that implements IQuery or has a parent that"
                                                           "implements IQuery")))
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
     [component new-state]
     {:pre [(component? component)]}
     (if (satisfies? ILocalState component)
       (-set-state! component new-state)
       (reset! (:state component) new-state))))

#?(:cljs
   (defn set-state!
     "Set the component local state of the component. Analogous to React's
   setState. WARNING: This version triggers a reconcile which *will* run the query
   on the component. This is to avoid spurious overhead of multiple renders on the DOM by
   pushing the refresh off to the next animation frame; however, rapid animation updates
   will not work well with this. Use react-set-state! instead. This function may
   evolve in the future to just be the same as react-set-state!. Your feedback is
   welcome."
     [component new-state]
     {:pre [(component? component)]}
     (if (implements? ILocalState component)
       (-set-state! component new-state)
       (gobj/set (.-state component) "fulcro$pendingState" new-state))
     (if-let [r (get-reconciler component)]
       (do
         (p/queue! r [component])
         (schedule-render! r))
       (force-update component))))

(defn react-set-state!
  ([component new-state]
   (react-set-state! component new-state nil))
  ([component new-state cb]
   {:pre [(component? component)]}
    #?(:clj  (do
               (set-state! component new-state)
               (cb))
       :cljs (.setState component #js {"fulcro$state" new-state} cb))))

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
  "Force a re-render of the root. Not recommended for anything except
   recomputing :shared."
  [reconciler]
  {:pre [(reconciler? reconciler)]}
  ((get @(:state reconciler) :render)))

(defn tempid
  "Return a temporary id."
  ([] (tempid/tempid))
  ([id] (tempid/tempid id)))

(defn tempid?
  "Return true if x is a tempid, false otherwise"
  #?(:cljs {:tag boolean})
  [x]
  (tempid/tempid? x))

(defn reader
  "Create a transit reader. This reader can handler the tempid type.
   Can pass transit reader customization opts map."
  ([] (transit/reader))
  ([opts] (transit/reader opts)))

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
   (let [shared #?(:clj (get-prop component :fulcro$shared)
                   :cljs (gobj/get (. component -props) "fulcro$shared"))
         ks             (cond-> k-or-ks
                          (not (sequential? k-or-ks)) vector)]
     (cond-> shared
       (not (empty? ks)) (get-in ks)))))

(defn instrument [component]
  {:pre [(component? component)]}
  (get-prop component #?(:clj  :fulcro$instrument
                         :cljs "fulcro$instrument")))

#?(:cljs
   (defn- merge-pending-state! [c]
     (if (implements? ILocalState c)
       (-merge-pending-state! c)
       (when-let [pending (some-> c .-state (gobj/get "fulcro$pendingState"))]
         (let [state    (.-state c)
               previous (gobj/get state "fulcro$state")]
           (gobj/remove state "fulcro$pendingState")
           (gobj/set state "fulcro$previousState" previous)
           (gobj/set state "fulcro$state" pending))))))

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
  "Get the rendered state of component. om.next/get-state always returns the
   up-to-date state."
  ([component]
   (get-rendered-state component []))
  ([component k-or-ks]
   {:pre [(component? component)]}
   (let [cst (if #?(:clj  (satisfies? ILocalState component)
                    :cljs (implements? ILocalState component))
               (-get-rendered-state component)
               #?(:clj  (get-state component)
                  :cljs (some-> component .-state (gobj/get "fulcro$state"))))]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
; DRAGONS BE HERE: The following code HACKS the cljs compiler to add an annotation so that
; statics do not get removed from the defui components.
; FIXME: It would be nice to figure out how to get this to work without the hack.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#?(:clj
   (defn- add-proto-methods* [pprefix type type-sym [f & meths :as form]]
     (let [pf          (str pprefix (name f))
           emit-static (when (-> type-sym meta :static)
                         `(~'js* "/** @nocollapse */"))]
       (if (vector? (first meths))
         ;; single method case
         (let [meth meths]
           [`(do
               ~emit-static
               (set! ~(#'cljs.core/extend-prefix type-sym (str pf "$arity$" (count (first meth))))
                 ~(with-meta `(fn ~@(#'cljs.core/adapt-proto-params type meth)) (meta form))))])
         (map (fn [[sig & body :as meth]]
                `(do
                   ~emit-static
                   (set! ~(#'cljs.core/extend-prefix type-sym (str pf "$arity$" (count sig)))
                     ~(with-meta `(fn ~(#'cljs.core/adapt-proto-params type meth)) (meta form)))))
           meths)))))

#?(:clj (intern 'cljs.core 'add-proto-methods* add-proto-methods*))

#?(:clj
   (defn- proto-assign-impls [env resolve type-sym type [p sigs]]
     (#'cljs.core/warn-and-update-protocol p type env)
     (let [psym        (resolve p)
           pprefix     (#'cljs.core/protocol-prefix psym)
           skip-flag   (set (-> type-sym meta :skip-protocol-flag))
           static?     (-> p meta :static)
           type-sym    (cond-> type-sym
                         static? (vary-meta assoc :static true))
           emit-static (when static?
                         `(~'js* "/** @nocollapse */"))]
       (if (= p 'Object)
         (#'cljs.core/add-obj-methods type type-sym sigs)
         (concat
           (when-not (skip-flag psym)
             (let [{:keys [major minor qualifier]} cljs.util/*clojurescript-version*]
               (if (and (== major 1) (== minor 9) (>= qualifier 293))
                 [`(do
                     ~emit-static
                     (set! ~(#'cljs.core/extend-prefix type-sym pprefix) cljs.core/PROTOCOL_SENTINEL))]
                 [`(do
                     ~emit-static
                     (set! ~(#'cljs.core/extend-prefix type-sym pprefix) true))])))
           (mapcat
             (fn [sig]
               (if (= psym 'cljs.core/IFn)
                 (#'cljs.core/add-ifn-methods type type-sym sig)
                 (#'cljs.core/add-proto-methods* pprefix type type-sym sig)))
             sigs))))))

#?(:clj (intern 'cljs.core 'proto-assign-impls proto-assign-impls))

(comment
  "ptransact! transactions are converted as follows:"
  [(f) (g) (h)] -> [(f) (deferred-transaction {:remote remote-of-f
                                               :tx     [(g) (deferred-transaction {:remote  remote-of-g
                                                                                   :post-tx [(h)]})]})]
  "And the deffered transaction triggers a mock load that runs the given tx through the post-mutatio mechanism. There
  is a short-circuit bit of logic in the actual send to networking to keep it uninvolved (no net traffic).")

(defn pessimistic-transaction->transaction
  "Converts a sequence of calls as if each call should run in sequence (deferring even the optimistic side until
  the prior calls have completed in a full-stack manner), and returns a tx that can be submitted via the normal
  `transact!`."
  [ref tx]
  (let [ast-nodes     (:children (query->ast tx))
        {calls true reads false} (group-by #(= :call (:type %)) ast-nodes)
        first-call    (first calls)
        dispatch-key  (:dispatch-key first-call)
        get-remote    (or (some-> (resolve 'fulcro.client.data-fetch/get-remote) deref) (fn [sym]
                                                                                          (log/error "FAILED TO FIND MUTATE. CANNOT DERIVE REMOTES FOR ptransact!")
                                                                                          :remote))
        remote        (or (get-remote dispatch-key) :remote)
        tx-to-run-now (into [(ast->query first-call)] (ast->query {:type :root :children reads}))]
    (if (seq (rest calls))
      (let [remaining-tx (ast->query {:type :root :children (into (vec (rest calls)) reads)})]
        (into tx-to-run-now `[(fulcro.client.data-fetch/deferred-transaction {:remote ~remote
                                                                              :ref    ~ref
                                                                              :tx     ~(pessimistic-transaction->transaction ref remaining-tx)})]))
      tx-to-run-now)))

(defn ptransact!
  "Like `transact!`, but ensures each call completes (in a full-stack, pessimistic manner) before the next call starts
  in any way. Note that two calls of this function have no guaranteed relationship to each other. They could end up
  intermingled at runtime. The only guarantee is that for *a single call* to `ptransact!`, the calls in the given tx will run
  pessimistically (one at a time) in the order given. Follow-on reads in the given transaction will be repeated after each remote
  interaction.

  NOTE: `ptransact!` *is* safe to use from within mutations (e.g. for retry behavior)."
  [comp-or-reconciler tx]
  (let [ref (if (component? comp-or-reconciler) (get-ident comp-or-reconciler))]
    #?(:clj  (transact! comp-or-reconciler (pessimistic-transaction->transaction ref tx))
       :cljs (js/setTimeout (fn [] (transact! comp-or-reconciler (pessimistic-transaction->transaction ref tx))) 0))))

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
   (when-not (= user-arity (count (second fn-form)))
     (throw (ex-info (str "Invalid arity for " user-known-sym) {:expected user-arity :got (count (second fn-form))})))
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
        value-of  (fn value-of* [[k v]]
                    (let [param-name    (fn [v] (and (keyword? v) (= "param" (namespace v)) (keyword (name v))))
                          substitute    (fn [ele] (if-let [k (param-name ele)]
                                                    (get params k)
                                                    ele))
                          param-key     (param-name v)
                          param-exists? (contains? params param-key)
                          param-value   (get params param-key)
                          child-class   (get children-by-query-key k)]
                      (cond
                        (and param-key (not param-exists?)) nil
                        (and (map? v) (is-child? k)) [k (get-initial-state child-class (into {} (keep value-of* v)))]
                        (map? v) [k (into {} (keep value-of* v))]
                        (and (vector? v) (is-child? k)) [k (mapv (fn [m] (get-initial-state child-class (into {} (keep value-of* m)))) v)]
                        (and (vector? param-value) (is-child? k)) [k (mapv (fn [params] (get-initial-state child-class params)) param-value)]
                        (vector? v) [k (mapv (fn [ele] (substitute ele)) v)]
                        (and param-key (is-child? k) param-exists?) [k (get-initial-state child-class param-value)]
                        param-key [k param-value]
                        :else [k v])))]
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
                                 from-parameter? (and (keyword? state-params) (= "param" (namespace state-params)))
                                 child-class     (get children-by-query-key k)]
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
#?(:clj (s/def :fulcro.client.primitives.defsc/css-include (s/or :template (s/and vector? #(every? symbol? %)) :method list?)))

#?(:clj (s/def :fulcro.client.primitives.defsc/options (s/keys :opt-un [:fulcro.client.primitives.defsc/query :fulcro.client.primitives.defsc/ident :fulcro.client.primitives.defsc/initial-state :fulcro.client.primitives.defsc/css :fulcro.client.primitives.defsc/css-include])))

#?(:clj (s/def :fulcro.client.primitives.defsc/args (s/cat
                                                      :sym symbol?
                                                      :doc (s/? string?)
                                                      :arglist (s/and vector? #(<= 2 (count %) 5))
                                                      :options (s/? :fulcro.client.primitives.defsc/options)
                                                      :body (s/* list?))))
#?(:clj (s/def :fulcro.client.primitives.defsc/static #{'static}))
#?(:clj (s/def :fulcro.client.primitives.defsc/protocol-method list?))

#?(:clj (s/def :fulcro.client.primitives.defsc/protocols (s/* (s/cat :static (s/? :fulcro.client.primitives.defsc/static) :protocol symbol? :methods (s/+ :fulcro.client.primitives.defsc/protocol-method)))))

#?(:clj
   (defn- build-form [form-fields]
     (when form-fields
       `(~'static ~'fulcro.ui.forms/IForm
          (~'form-spec [~'this] ~form-fields)))))

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
           body                             (or body ['(fulcro.client.dom/div nil "THIS COMPONENT HAS NO DECLARED UI")])
           ident-template-or-method         (into {} [ident]) ;clojure spec returns a map entry as a vector
           initial-state-template-or-method (into {} [initial-state])
           query-template-or-method         (into {} [query])
           css-template-or-method           (into {} [css])
           css-include-template-or-method   (into {} [css-include])
           has-css?                         (or css css-include)
           ; TODO: validate-css?                    (and (map? csssym) (:template css))
           validate-query?                  (and (:template query-template-or-method) (not (some #{'*} (:template query-template-or-method))))
           legal-key-cheker                 (if validate-query?
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
           ident-forms                      (build-ident thissym propsym ident-template-or-method legal-key-cheker)
           has-ident?                       (seq ident-forms)
           state-forms                      (build-initial-state sym thissym initial-state-template-or-method legal-key-cheker query-template-or-method (boolean (seq form-fields)))
           query-forms                      (build-query-forms sym thissym propsym query-template-or-method)
           form-forms                       (build-form form-fields)
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
       `(fulcro.client.primitives/defui ~(with-meta sym {:once true})
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
   (defsc Component [this {:keys [db/id x] :as props} {:keys [onSelect] :as computed}]
     {
      ;; stateful component options
      ;; query template is literal. Use the lambda if you have ident-joins or unions.
      :query [:db/id :x] ; OR (fn [] [:db/id :x]) ; this in scope
      ;; ident template is table name and ID property name
      :ident [:table/by-id :id] ; OR (fn [] [:table/by-id id]) ; this and props in scope
      ;; initial-state template is magic..see dev guide. Lambda version is normal.
      :initial-state {:x :param/x} ; OR (fn [params] {:x (:x params)}) ; this in scope

      ; React Lifecycle Methods (this in scope)
      :initLocalState            (fn [] ...)
      :shouldComponentUpdate     (fn [next-props next-state] ...)
      :componentWillReceiveProps (fn [next-props] ...)
      :componentWillUpdate       (fn [next-props next-state] ...)
      :componentDidUpdate        (fn [prev-props prev-state] ...)
      :componentWillMount        (fn [] ...)
      :componentDidMount         (fn [] ...)
      :componentWillUnmount      (fn [] ...)

      ; Custom literal protocols (Object ok, too, to add arbitrary methods. Nothing automatically in scope.)
      :protocols [YourProtocol
                  (method [this] ...)]} ; nothing is automatically in scope
      ; BODY forms. May be omitted IFF there is an options map, in order to generate a component that is used only for queries/normalization.
      (dom/div #js {:onClick onSelect} x))
   ```

   To use with Fulcro CSS, be sure to require `fulcro-css.css`, and add an the argument (only when used):

   ```
   (ns ui
     (require fulcro-css.css))

   (defsc Component [this props computed {:keys [my-classname] :as classnames}]
     {:css [[:.my-classname]] ; OR (fn [] [[:my-classname]])
      :css-include [] ; list of children from which CSS should also be pulled
      ... }
      (dom/div #js {:className my-classname} ...))
   ```

   Only the first two arguments are required (this and props).

   See section M05-More-Concise-UI of the Developer's Guide for more details.
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

(defn integrate-ident
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
  (let [actions (partition 2 named-parameters)]
    (reduce (fn [state [command data-path]]
              (let [already-has-ident-at-path? (fn [data-path] (some #(= % ident) (get-in state data-path)))]
                (case command
                  :prepend (if (already-has-ident-at-path? data-path)
                             state
                             (do
                               (assert (vector? (get-in state data-path)) (str "Path " data-path " for prepend must target an app-state vector."))
                               (update-in state data-path #(into [ident] %))))
                  :append (if (already-has-ident-at-path? data-path)
                            state
                            (do
                              (assert (vector? (get-in state data-path)) (str "Path " data-path " for append must target an app-state vector."))
                              (update-in state data-path conj ident)))
                  :replace (let [path-to-vector (butlast data-path)
                                 to-many?       (and (seq path-to-vector) (vector? (get-in state path-to-vector)))
                                 index          (last data-path)
                                 vector         (get-in state path-to-vector)]
                             (assert (vector? data-path) (str "Replacement path must be a vector. You passed: " data-path))
                             (when to-many?
                               (do
                                 (assert (vector? vector) "Path for replacement must be a vector")
                                 (assert (number? index) "Path for replacement must end in a vector index")
                                 (assert (contains? vector index) (str "Target vector for replacement does not have an item at index " index))))
                             (assoc-in state data-path ident))
                  (throw (ex-info "Unknown post-op to merge-state!: " {:command command :arg data-path})))))
      state actions)))

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
  "Integrate an ident into any number of places in the app state. This function is safe to use within mutation
  implementations as a general helper function.

  The named parameters can be specified any number of times. They are:

  - append:  A vector (path) to a list in your app state where this new object's ident should be appended. Will not append
  the ident if that ident is already in the list.
  - prepend: A vector (path) to a list in your app state where this new object's ident should be prepended. Will not append
  the ident if that ident is already in the list.
  - replace: A vector (path) to a specific location in app-state where this object's ident should be placed. Can target a to-one or to-many.
   If the target is a vector element then that element must already exist in the vector.
  "
  [state ident & named-parameters]
  (assert (is-atom? state)
    "The state has to be an atom. Use 'integrate-ident' instead.")
  (apply swap! state integrate-ident ident named-parameters))

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
      (apply integrate-ident! state ident named-parameters)
      (p/queue! reconciler data-path-keys)
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
              (when-not default-initial-state
                (log/warn "WARNING: Subelements of union " (.. parent-union -displayName) " have initial state. This means your default branch of the union will not have initial application state."))
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


