(ns fulcro.client.primitives
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
               [fulcro.history :as hist]
               [fulcro.client.logging :as log]
               [fulcro.tempid :as tempid]
               [fulcro.transit :as transit]
               [clojure.zip :as zip]
               [clojure.pprint :refer [pprint]]
               [fulcro.client.impl.protocols :as p]
               [fulcro.client.impl.parser :as parser]
               [fulcro.util :as util]
               [cljs.analyzer :as ana]
               [cljs.analyzer.api :as ana-api]
               [clojure.walk :refer [prewalk]]
               [clojure.string :as str]
               [clojure.spec.alpha :as s]
               [cognitect.transit :as t])
  #?(:clj
           (:import [java.io Writer])
     :cljs (:import [goog.debug Console])))

(s/def ::remote keyword?)
(s/def ::ident util/ident?)
(s/def ::query vector?)

(defn get-history
  "pass-through function for getting history, that enables testing (cannot mock protocols easily)"
  [reconciler]
  (when reconciler
    (p/get-history reconciler)))

(defn add-basis-time
  "Recursively add the given basis time to all of the maps in the props."
  [props time]
  (prewalk (fn [ele]
             (if (map? ele)
               (vary-meta ele assoc ::time time)
               ele)) props))

(defn get-basis-time [props] (or (-> props meta ::time) :unset))

(defn get-current-time
  "get the current basis time from the reconciler. Used instead of the protocol to facilitate testing."
  [reconciler] (p/basis-t reconciler))

(defn collect-statics [dt]
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
                  (when-not @(:omcljs$mounted? props#)
                    (swap! (:omcljs$mounted? props#) not))
                  ret#)))))
       'componentWillMount
       (fn [[name [this :as args] & body]]
         `(~name [this#]
            (let [~this this#
                  indexer# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
              (when-not (nil? indexer#)
                (fulcro.client.impl.protocols/index-component! indexer# this#))
              ~@body)))}
      :defaults
      `{~'initLocalState
        ([this#])
        ~'componentWillMount
        ([this#]
          (let [indexer# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
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
           (cljs.core/js-obj "omcljs$state" ret#))))
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
               ~next-state (or (goog.object/get next-state# "omcljs$pendingState")
                             (goog.object/get next-state# "omcljs$state"))
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
               ~prev-state (goog.object/get prev-state# "omcljs$previousState")]
           ~@body
           (fulcro.client.primitives/clear-prev-props! this#))))
    'componentWillMount
    (fn [[name [this :as args] & body]]
      `(~name [this#]
         (let [~this this#
               indexer# (get-in (fulcro.client.primitives/get-reconciler this#) [:config :indexer])]
           (when-not (nil? indexer#)
             (fulcro.client.impl.protocols/index-component! indexer# this#))
           ~@body)))
    'componentWillUnmount
    (fn [[name [this :as args] & body]]
      `(~name [this#]
         (let [~this this#
               r# (fulcro.client.primitives/get-reconciler this#)
               cfg# (:config r#)
               st# (:state cfg#)
               indexer# (:indexer cfg#)]
           (when (and (not (nil? st#))
                   (get-in @st# [:fulcro.client.primitives/queries this#]))
             (swap! st# update-in [:fulcro.client.primitives/queries] dissoc this#))
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
   `{~'isMounted
     ([this#]
       (boolean
         (or (some-> this# .-_reactInternalFiber .-stateNode)
           ;; Pre React 16 support. Remove when we don't wish to support
           ;; React < 16 anymore - Antonio
           (some-> this# .-_reactInternalInstance .-_renderedComponent))))
     ~'shouldComponentUpdate
     ([this# next-props# next-state#]
       (let [next-children#     (. next-props# -children)
             next-props#        (goog.object/get next-props# "omcljs$value")
             next-props#        (cond-> next-props#
                                  (instance? OmProps next-props#) unwrap)
             current-props#     (fulcro.client.primitives/props this#)
             ; a parent could send in stale props due to a component-local state change..make sure we don't use them. (Props have a timestamp on metadata)
             next-props-stale?# (> (get-basis-time current-props#) (get-basis-time next-props#))
             props-changed?#    (and
                                  (not next-props-stale?#)
                                  (not= current-props# next-props#))
             state-changed?#    (and (.. this# ~'-state)
                                  (not= (goog.object/get (. this# ~'-state) "omcljs$state")
                                    (goog.object/get next-state# "omcljs$state")))
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
     ~'componentWillUnmount
     ([this#]
       (let [r#       (fulcro.client.primitives/get-reconciler this#)
             cfg#     (:config r#)
             st#      (:state cfg#)
             indexer# (:indexer cfg#)]
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
           rname            (if env
                              (:name (ana/resolve-var (dissoc env :locals) name))
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
          (set! (.-cljs$lang$type ~rname) true)
          (set! (.-cljs$lang$ctorStr ~rname) ~(str rname))
          (set! (.-cljs$lang$ctorPrWriter ~rname)
            (fn [this# writer# opt#]
              (cljs.core/-write writer# ~(str rname)))))))))

(defmacro defui [name & forms]
  (if (boolean (:ns &env))
    (defui* name forms &env)
    #?(:clj (defui*-clj name forms))))

(defmacro ui
  [& forms]
  (let [t (with-meta (gensym "ui_") {:anonymous true})]
    `(do (defui ~t ~@forms) ~t)))

;; TODO: #?:clj invariant - António
(defn invariant*
  [condition message env]
  (let [opts     (ana-api/get-options)
        fn-scope (:fn-scope env)
        fn-name  (some-> fn-scope first :name str)]
    (when-not (:elide-asserts opts)
      `(when-not ~condition
         (log/error (str "Invariant Violation"
                      (when-not (nil? ~fn-name)
                        (str " (in function: `" ~fn-name "`)"))
                      ": " ~message))))))

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
  "Returns true if the argument is an Om component."
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

#?(:cljs (deftype ^:private OmProps [props basis-t]))

#?(:cljs
   (defn- om-props [props basis-t]
     (OmProps. props basis-t)))

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
     (get-props* x "omcljs$prev$value")))

#?(:cljs
   (defn- get-next-props [x]
     (get-props* x "omcljs$next$value")))

#?(:cljs
   (defn- get-props [x]
     (get-props* x "omcljs$value")))

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
  (get-prop c #?(:clj  :omcljs$reconciler
                 :cljs "omcljs$reconciler")))

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
           pending (gobj/get cst "omcljs$next$value")
           prev    (props* (get-props cst) (get-props props))]
       (gobj/set cst "omcljs$prev$value" prev)
       (when-not (nil? pending)
         (gobj/remove cst "omcljs$next$value")
         (gobj/set cst "omcljs$value" pending)))))

#?(:cljs
   (defn- clear-prev-props! [c]
     (gobj/remove (.-state c) "omcljs$prev$value")))

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
  "Returns the parent Om component."
  [component]
  (get-prop component #?(:clj  :omcljs$parent
                         :cljs "omcljs$parent")))

(defn depth
  "PRIVATE: Returns the render depth (a integer) of the component relative to
   the mount root."
  [component]
  (when (component? component)
    (get-prop component #?(:clj  :omcljs$depth
                           :cljs "omcljs$depth"))))

(defn react-key
  "Returns the components React key."
  [component]
  (get-prop component #?(:clj  :omcljs$reactKey
                         :cljs "omcljs$reactKey")))


#?(:clj
   (defn props [component]
     {:pre [(component? component)]}
     (:omcljs$value (:props component))))

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

(defprotocol Ident
  (ident [this props] "Return the ident for this component"))

(defprotocol IQueryParams
  (params [this] "Return the query parameters"))

(defprotocol IQuery
  (query [this] "Return the component's unbound static query"))

(defprotocol IDynamicQuery
  (dynamic-query [this state] "Return the component's unbound dynamic query"))

;; DEPRECATED: Unless someone can give me a compelling case to keep this, I'm dropping it:
(defprotocol ILocalState
  (-set-state! [this new-state] "Set the component's local state")
  (-get-state [this] "Get the component's local state")
  (-get-rendered-state [this] "Get the component's rendered local state")
  (-merge-pending-state! [this] "Get the component's pending local state"))

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
                          (or (gobj/get state "omcljs$pendingState")
                            (gobj/get state "omcljs$state")))))]
     (get-in cst (if (sequential? k-or-ks) k-or-ks [k-or-ks])))))

(defn has-dynamic-query?
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :dynamic-query))
             (let [class (cond-> x (component? x) class)]
               (extends? IDynamicQuery class)))
     :cljs (implements? IDynamicQuery x)))

(defn has-static-query?
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :query))
             (let [class (cond-> x (component? x) class)]
               (extends? IQuery class)))
     :cljs (implements? IQuery x)))

(defn has-query-params?
  #?(:cljs {:tag boolean})
  [x]
  #?(:clj  (if (fn? x)
             (some? (-> x meta :params))
             (let [class (cond-> x (component? x) class)]
               (extends? IQueryParams class)))
     :cljs (implements? IQueryParams x)))

(defn- get-static-query [c]
  {:pre (has-static-query? c)}
  #?(:clj ((-> c meta :query) c) :cljs (query c)))

(defn- get-dynamic-query [c state]
  {:pre (has-dynamic-query? c)}
  #?(:clj ((-> c meta :dynamic-query) c state) :cljs (dynamic-query c state)))

(defn has-query?
  "Returns true if the given class or component has a dynamic or static query."
  [x]
  (or (has-dynamic-query? x) (has-static-query? x)))

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
  (let [classname #?(:clj (-> (str (-> class meta :component-ns) "." (-> class meta :component-name))
                            (str/replace "." "$")
                            (str/replace "-" "_"))
                     :cljs (.-name class))]
    (str classname (when qualifier (str "$" qualifier)))))

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
                   props     {:omcljs$reactRef   ref
                              :omcljs$reactKey   react-key
                              :omcljs$value      (cond-> props
                                                   (map? props) (dissoc :ref))
                              :omcljs$queryid    (query-id class qualifier)
                              :omcljs$mounted?   (atom false)
                              :omcljs$path       (-> props meta :om-path)
                              :omcljs$reconciler *reconciler*
                              :omcljs$parent     *parent*
                              :omcljs$shared     *shared*
                              :omcljs$instrument *instrument*
                              :omcljs$depth      *depth*}
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
                     :omcljs$reactKey   key
                     :omcljs$value      (om-props props t)
                     :omcljs$path       (-> props meta :om-path)
                     :omcljs$queryid    (query-id class qualifier)
                     :omcljs$reconciler *reconciler*
                     :omcljs$parent     *parent*
                     :omcljs$shared     *shared*
                     :omcljs$instrument *instrument*
                     :omcljs$depth      *depth*}
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
        query         (or (denormalize-query state-map queryid) (if (has-dynamic-query? class)
                                                                  (get-dynamic-query class state-map)
                                                                  (get-static-query class)))
        params        (get-in state-map [::queries queryid :params] static-params)]
    (with-meta (bind-query query params) {:component class
                                          :queryid   queryid})))

(defn is-factory?
  [class-or-factory]
  (and (fn? class-or-factory)
    (-> class-or-factory meta (contains? :qualifier))))

(defn get-query
  "Get the query for the given class or factory. Obtains the static query unless state is supplied and the target component(s)
  implement IDynamicQuery."
  ([class-or-factory]
   (let [class  (if (is-factory? class-or-factory)
                  (-> class-or-factory meta :class)
                  class-or-factory)
         q      (cond
                  (has-dynamic-query? class) (get-dynamic-query class {})
                  (has-static-query? class) (get-static-query class)
                  :otherwise nil)
         params (when (has-query-params? class)
                  (get-query-params class))
         c      (-> q meta :component)]
     (assert (nil? c) (str "Query violation, " class-or-factory, " reuses " c " query"))
     (with-meta (bind-query q params) {:component class-or-factory
                                       :queryid   (query-id class-or-factory nil)})))
  ([class-or-factory state-map]
   (let [class     (cond
                     (is-factory? class-or-factory) (-> class-or-factory meta :class)
                     (component? class-or-factory) (react-type class-or-factory)
                     :else class-or-factory)
         qualifier (if (is-factory? class-or-factory)
                     (-> class-or-factory meta :qualifier)
                     nil)
         queryid   (query-id class qualifier)]
     (when (and class (has-query? class))
       (get-query-by-id state-map class queryid)))))

(declare normalize-query)

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
  NOTE: Indexes must be rebuilt after setting a query, so this function should only be used to build
  up an initial app state. Do not use it as part of a mutation."
  [state-map queryid-or-class-or-ui-factory {:keys [query params]}]
  (if-let [queryid (cond
                     (string? queryid-or-class-or-ui-factory) queryid-or-class-or-ui-factory
                     (contains? (meta queryid-or-class-or-ui-factory) :queryid) (some-> queryid-or-class-or-ui-factory meta :queryid)
                     :otherwise (query-id queryid-or-class-or-ui-factory nil))]
    (do
      ; we have to dissoc the old one, because normalize won't overwrite by default
      (let [new-state (normalize-query (update state-map ::queries dissoc queryid) (with-meta query {:queryid queryid}))
            params    (get-in new-state [::queries queryid :params] params)]
        (if params
          (assoc-in new-state [::queries queryid :params] params)
          new-state)))
    state-map))

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
  "Returns the component's Om data path."
  [c]
  (get-prop c #?(:clj  :omcljs$path
                 :cljs "omcljs$path")))

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
          assoc :prim/tag (first ident))
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
  "Given a Om component class or instance and a tree of data, use the component's
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

(declare focus-query*)

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
  (log/info "RES:" res query)
  (let [config (:config reconciler)
        [idts res'] (sift-idents res)
        res'   (if (:normalize config)
                 (tree->db
                   (or query (:root @(:state reconciler)))
                   res' true)
                 res')]
    (-> state
      (merge-idents config idts query)
      ((:merge-tree config) res'))))

(defn default-merge [reconciler state res query]
  {:keys    (into [] (remove symbol?) (keys res))
   :next    (merge-novelty! reconciler state res query)
   :tempids (->> (filter (comp symbol? first) res)
              (map (comp :tempids second))
              (reduce merge {}))})

(declare transact!)

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
      (map #(vector % (some-> (parser env q %) (with-meta {::hist/tx-time tx-time}))))
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
  #?(:clj  (and (component? x) @(get-prop x :omcljs$mounted?))
     :cljs (and (component? x) ^boolean (.isMounted x))))


(defn fulcro-ui->props
  "Finds props for a given component. Returns ::no-ident if the component has
  no ident (which prevents localized update). This eliminates the need for
  path data."
  [{:keys [parser state] :as env} c]
  (let [ui (when #?(:clj  (satisfies? Ident c)
                    :cljs (implements? Ident c))
             (let [id    (ident c (props c))
                   query [{id (get-query c @state)}]]
               (get (parser env query) id)))]
    (or ui
      (let [component-name (.. c -constructor -displayName)]
        (log/debug (str "PERFORMANCE NOTE: " component-name " does not have an ident (ignore this message if it is your root). This will cause full root-level renders that may affect rendering performance."))
        ::no-ident))))

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
  #?(:clj  (:children component)
     :cljs (.. component -props -children)))

#?(:cljs
   (defn should-update?
     "Invoke the lifecycle method on the component to see if it would recommend an update given the next-props or next-props
     and next-state"
     ([c next-props]
      (should-update? c next-props nil))
     ([c next-props next-state]
      {:pre [(component? c)]}
      (.shouldComponentUpdate c
        #js {:omcljs$value next-props}
        #js {:omcljs$state next-state}))))

#?(:cljs
   (defn- update-props!
     "Store the given props onto the component so that when the factory is called (via forceUpdate) they can be used as the new
     props for the rendering of that component."
     [c next-props]
     {:pre [(component? c)]}
     ;; We cannot write directly to props, React will complain
     (doto (.-state c)
       (gobj/set "omcljs$next$value"
         (om-props next-props (get-current-time (get-reconciler c)))))))

#?(:cljs
   (defn- update-component!
     "Force an update of a component using the given new props, skipping the render from root. This will also update the
     recorded reconciler basis time of the props."
     [c next-props]
     {:pre [(component? c)]}
     (update-props! c next-props)
     (.forceUpdate c)))


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
                                                     (.forceUpdate c' data)))))]
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
                                v            (add-basis-time raw-props current-time)]
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
            (p/tick! this)
            #?(:cljs
               (if-not (has-query? root-class)
                 (queue-render! parsef)
                 (schedule-render! this)))))
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
      (when (has-dynamic-query? root)
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
                         next-raw-props (add-basis-time (fulcro-ui->props env c) current-time)
                         force-root?    (= ::no-ident next-raw-props) ; screw focused query...
                         next-props     (when-not force-root? (fulcro.client.primitives/computed next-raw-props computed))]
                     (if force-root?
                       (do
                         (.forceUpdate c)                   ; in case it was just a state update on that component, shouldComponentUpdate of root would keep it from working
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
                               #js {:omcljs$value (om-props next-props (get-current-time this))})))
                         (when (should-update? c next-props (get-state c))
                           (if-not (nil? next-props)
                             (update-component! c next-props)
                             (.forceUpdate c))))))))))))))

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
     :tx-listen    - a function of 2 arguments that will listen to transactions.
                     The first argument is the parser's env map also containing
                     the old and new state. The second argument is a history-step (see history). It also contains
                     a couple of legacy fields for bw compatibility with 1.0."
  [{:keys [state shared shared-fn
           parser normalize
           send merge-sends remotes
           merge merge-tree merge-ident
           optimize
           root-render root-unmount
           migrate id-key
           instrument tx-listen
           history]
    :or   {merge-sends  #(merge-with into %1 %2)
           remotes      [:remote]
           merge        default-merge
           history      100
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
                         :merge       merge :merge-tree merge-tree :merge-ident merge-ident
                         :optimize    optimize
                         :normalize   (or (not norm?) normalize)
                         :root-render root-render :root-unmount root-unmount
                         :pathopt     true
                         :migrate     migrate :id-key id-key
                         :instrument  instrument :tx-listen tx-listen}
                        (atom {:queue        []
                               :remote-queue {}
                               :queued       false :queued-sends {}
                               :sends-queued false
                               :target       nil :root nil :render nil :remove nil
                               :t            0 :normalized norm?})
                        (atom (hist/new-history history)))]
    ret))

(defn transact* [reconciler c ref tx]
  (p/tick! reconciler)                                      ; ensure time moves forward. A tx that doesn't swap would fail to do so
  (let [cfg          (:config reconciler)
        ref          (if (and c #?(:clj  (satisfies? Ident c)
                                   :cljs (implements? Ident c)) (not ref))
                       (ident c (props c))
                       ref)
        env          (merge
                       (to-env cfg)
                       {:reconciler reconciler :component c}
                       (when ref
                         {:ref ref}))
        old-state    @(:state cfg)
        history      (get-history reconciler)
        v            ((:parser cfg) env tx)
        tx-time      (get-current-time reconciler)
        snds         (gather-sends env tx (:remotes cfg) tx-time)
        new-state    @(:state cfg)
        xs           (cond-> []
                       (not (nil? c)) (conj c)
                       (not (nil? ref)) (conj ref))
        history-step {::hist/tx        tx
                      ::hist/tx-result v
                      ::hist/db-before old-state
                      ::hist/db-after  new-state}]
    ; TODO: transact! should have access to some kind of UI hook on the reconciler that user's install to block UI when history is too full (due to network queue)
    (when history
      (swap! history hist/record-history-step tx-time history-step))
    (p/queue! reconciler (into xs (remove symbol?) (keys v)))
    (when-not (empty? snds)
      (log/info "Scheduling sends" snds)
      (doseq [[remote _] snds]
        (p/queue! reconciler xs remote))
      (p/queue-sends! reconciler snds)
      (schedule-sends! reconciler))
    (when-let [f (:tx-listen cfg)]
      (let [tx-data (merge env
                      {:old-state old-state
                       :new-state new-state})]
        (f tx-data (assoc history-step :tx tx :ret v))))
    v))

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
         :read/this :read/that])"
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
                                                           "that implements IDynamicQuery or has a parent that"
                                                           "implements IDynamicQuery")))
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
       (gobj/set (.-state component) "omcljs$pendingState" new-state))
     (if-let [r (get-reconciler component)]
       (do
         (p/queue! r [component])
         (schedule-render! r))
       (.forceUpdate component))))

(defn react-set-state!
  ([component new-state]
   (react-set-state! component new-state nil))
  ([component new-state cb]
   {:pre [(component? component)]}
    #?(:clj  (do
               (set-state! component new-state)
               (cb))
       :cljs (.setState component #js {:omcljs$state new-state} cb))))

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
  "Create a Om Next transit reader. This reader can handler the tempid type.
   Can pass transit reader customization opts map."
  ([] (transit/reader))
  ([opts] (transit/reader opts)))

(defn writer
  "Create a Om Next transit writer. This writer can handler the tempid type.
   Can pass transit writer customization opts map."
  ([] (transit/writer))
  ([opts] (transit/writer opts)))

(defn dispatch
  "Helper function for implementing :read and :mutate as multimethods. Use this
   as the dispatch-fn."
  [_ key _] key)

(defn parser
  "Create a parser. The argument is a map of two keys, :read and :mutate. Both
   functions should have the signature (Env -> Key -> Params -> ParseResult)."
  [{:keys [read mutate] :as opts}]
  {:pre [(map? opts)]}
  (parser/parser opts))

(declare remove-root!)

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
  "Return the global shared properties of the Om Next root. See :shared and
   :shared-fn reconciler options."
  ([component]
   (shared component []))
  ([component k-or-ks]
   {:pre [(component? component)]}
   (let [shared #?(:clj (get-prop component :omcljs$shared)
                   :cljs (gobj/get (. component -props) "omcljs$shared"))
         ks             (cond-> k-or-ks
                          (not (sequential? k-or-ks)) vector)]
     (cond-> shared
       (not (empty? ks)) (get-in ks)))))

(defn instrument [component]
  {:pre [(component? component)]}
  (get-prop component #?(:clj  :omcljs$instrument
                         :cljs "omcljs$instrument")))

#?(:cljs
   (defn- merge-pending-state! [c]
     (if (implements? ILocalState c)
       (-merge-pending-state! c)
       (when-let [pending (some-> c .-state (gobj/get "omcljs$pendingState"))]
         (let [state    (.-state c)
               previous (gobj/get state "omcljs$state")]
           (gobj/remove state "omcljs$pendingState")
           (gobj/set state "omcljs$previousState" previous)
           (gobj/set state "omcljs$state" pending))))))

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
                  :cljs (some-> component .-state (gobj/get "omcljs$state"))))]
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
        queryid    (if (string? ui-factory-or-queryid)
                     ui-factory-or-queryid
                     (-> ui-factory-or-queryid meta :queryid))
        tx         (into `[(fulcro.client.mutations/set-query {:query-id ~queryid :query ~query :params ~params})] follow-on-reads)]
    (transact! component-or-reconciler tx)
    (p/reindex! reconciler)))

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


