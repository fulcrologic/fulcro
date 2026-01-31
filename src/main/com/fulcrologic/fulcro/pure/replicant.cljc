(ns com.fulcrologic.fulcro.pure.replicant
  "Replicant-specific hiccup transformation.

   Replicant expects:
   - Event handlers under {:on {:click fn}} not {:onClick fn}
   - :class instead of :className
   - :for instead of :htmlFor

   Use element->replicant-hiccup to convert Element trees to Replicant-compatible hiccup.

   Example:
   ```clojure
   (require '[com.fulcrologic.fulcro.pure.dom :as dom])
   (require '[com.fulcrologic.fulcro.pure.replicant :as replicant])

   (def elem (dom/div {:className \"container\" :onClick #(println \"clicked\")}
               (dom/label {:htmlFor \"email\"} \"Email:\")))

   (replicant/element->replicant-hiccup elem)
   ;; => [:div {:class \"container\" :on {:click #fn}}
   ;;      [:label {:for \"email\"} \"Email:\"]]
   ```"
  #?(:cljs (:require-macros [com.fulcrologic.fulcro.pure.replicant :refer [defsc]]))
  (:require
    [com.fulcrologic.fulcro.algorithms.tx-processing.synchronous-tx-processing :as stx]
    [com.fulcrologic.fulcro.pure.hiccup :as hic]
    [com.fulcrologic.fulcro.pure.dom :as dom]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.fulcrologic.fulcro.raw.components :as rc]
    #?(:cljs [goog.dom :as gdom])
    [replicant.dom :as r]
    [taoensso.timbre :as log]))

(def ^:dynamic *app* nil)
(def ^:dynamic *parent* nil)
(def ^:dynamic *shared* nil)

(defn- on-event-key?
  "Returns true if k is a React-style event handler key like :onClick, :onChange"
  [k]
  (and (keyword? k)
    (let [n (name k)]
      (and (str/starts-with? n "on")
        (> (count n) 2)
        #?(:clj  (Character/isUpperCase ^char (nth n 2))
           :cljs (= (nth n 2) (.toUpperCase (nth n 2))))))))

(defn- react-event->replicant-event
  "Convert :onClick to :click, :onChange to :change, etc."
  [k]
  (let [n (name k)]
    (keyword (str/lower-case (subs n 2)))))

(defn transform-attrs
  "Transform React-style attrs to Replicant-style.

   Changes:
   - {:onClick fn :onChange fn} -> {:on {:click fn :change fn}}
   - {:className \"x\"} -> {:class \"x\"}
   - {:htmlFor \"y\"} -> {:for \"y\"}

   Example:
   ```clojure
   (transform-attrs {:onClick #(println \"clicked\") :className \"btn\"})
   ;; => {:on {:click #fn} :class \"btn\"}
   ```"
  [attrs]
  (when attrs
    (let [event-handlers (transient {})
          other-attrs    (transient {})]
      ;; Separate event handlers from other attrs
      (doseq [[k v] attrs]
        (cond
          (on-event-key? k)
          (assoc! event-handlers (react-event->replicant-event k) v)

          (= k :className)
          (assoc! other-attrs :class v)

          (= k :htmlFor)
          (assoc! other-attrs :for v)

          ;; Skip :ref as Replicant doesn't use it the same way
          (= k :ref)
          nil

          :else
          (assoc! other-attrs k v)))
      (let [events (persistent! event-handlers)
            attrs  (persistent! other-attrs)]
        (cond-> attrs
          (seq events) (assoc :on events))))))

(defn transform-hiccup
  "Recursively transform hiccup tree attrs from React-style to Replicant-style.

   Walks the entire tree and transforms attributes at each level.

   Example:
   ```clojure
   (transform-hiccup [:div {:className \"outer\"}
                       [:button {:onClick handler} \"Click\"]])
   ;; => [:div {:class \"outer\"}
   ;;      [:button {:on {:click handler}} \"Click\"]]
   ```"
  [hiccup]
  (cond
    (nil? hiccup) nil

    (hic/element? hiccup)
    (let [tag          (hic/element-tag hiccup)
          attrs        (hic/element-attrs hiccup)
          children     (hic/element-children hiccup)
          new-attrs    (transform-attrs attrs)
          new-children (mapv transform-hiccup children)]
      (into [tag (or new-attrs {})] new-children))

    (vector? hiccup)
    (mapv transform-hiccup hiccup)

    ;; Strings, numbers, etc. pass through unchanged
    :else hiccup))

(defn element->replicant-hiccup
  "Convert a pure/dom Element tree to Replicant-compatible hiccup.

   This is the main entry point for converting Fulcro pure DOM elements
   to a format that Replicant can render.

   The conversion:
   1. Converts Element records to standard hiccup via hic/element->hiccup
   2. Transforms React-style attributes to Replicant-style

   Example:
   ```clojure
   (def app-element
     (dom/div {:className \"app\"}
       (dom/button {:id \"submit\"
                    :onClick #(transact! ...)}
         \"Submit\")))

   (element->replicant-hiccup app-element)
   ;; => [:div {:class \"app\"}
   ;;      [:button {:id \"submit\" :on {:click #fn}}
   ;;        \"Submit\"]]
   ```"
  [element-tree]
  (-> element-tree
    hic/element->hiccup
    transform-hiccup))

(defn rendered-tree->replicant-hiccup
  "Alias for element->replicant-hiccup for consistency with naming conventions."
  [element-tree]
  (element->replicant-hiccup element-tree))

#?(:clj
   (defmacro defsc [sym args options & body]
     (let [nspc (if (boolean (:ns &env)) (-> &env :ns :name str) (name (ns-name *ns*)))
           fqkw (keyword (str nspc) (name sym))]
       `(def ~sym
          (rc/configure-anonymous-component!
            (fn ~args ~@body)
            (merge ~options
              {:componentName ~fqkw}))))))

(defn factory
  ([cls _ignored]
   (factory cls))
  ([cls]
   (fn [props & children]
     (let [this #js {:fulcro$isComponent true
                     :fulcro$class       cls
                     :props              #js {:fulcro$value props
                                              :fulcro$app   *app*
                                              :children     children}}]
       (cls this props)))))

(defsc Foo [this props]
  {:query (fn [] [:foo/id :foo/name])
   :ident (fn [this props] [:foo/id (:foo/id props)])}
  (js/console.log this)
  (js/console.log props)
  (dom/div :.x (:foo/name props)))

(def ui-foo (factory Foo))

(defn set-root!
  "Set a root class to use on the app. Doing so allows much of the API to work before mounting the app."
  ([app root {:keys [initialize-state?]}]
   (swap! (::runtime-atom app) assoc ::root-class root)
   (when initialize-state?
     (rapp/initialize-state! app root))))

(defn dom-element [id-or-node]
  #?(:cljs
     (if (string? id-or-node) (gdom/getElement id-or-node) id-or-node)
     :clj :none))

(defn mount! [app root-class dom-node]
  (let [dom-node (dom-element dom-node)
        {:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom config]} app
        {:com.fulcrologic.fulcro.application/keys [app-root]} @runtime-atom
        {:keys [client-did-mount client-will-mount]} config]
    (swap! runtime-atom assoc
      :com.fulcrologic.fulcro.application/mount-node dom-node
      :com.fulcrologic.fulcro.application/root-factory (factory root-class)
      :com.fulcrologic.fulcro.application/root-class root-class)
    (if (boolean app-root)
      (rapp/render! app {:force-root? true})
      (do
        (swap! state-atom #(merge {:fulcro.inspect.core/app-id (rc/component-name root-class)} %))
        (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/root-class root-class)
        (rapp/initialize-state! app root-class)
        (when client-will-mount (client-will-mount app))
        (when client-did-mount (client-did-mount app))
        (rapp/render! app)))))

(defn render! [app _]
  (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom state-atom]} app
        {:com.fulcrologic.fulcro.application/keys [root-factory root-class mount-node]} @runtime-atom
        r!       (log/spy :info (ah/app-algorithm app :render-root!))
        app-root (when root-factory
                   (let [state-map  @state-atom
                         root-query (rc/get-query root-class state-map)
                         root-props (fdn/db->tree root-query state-map state-map)]
                     (binding [*app*    app
                               *parent* nil
                               *shared* (rc/shared app)]
                       (r! (root-factory root-props) mount-node))))]
    (swap! runtime-atom assoc :com.fulcrologic.fulcro.application/app-root app-root)
    app-root))

(defn fulcro-replicant-app [options]
  (stx/with-synchronous-transactions
    (rapp/fulcro-app (merge
                       {:refresh-component! nil
                        :optimized-render!  render!
                        :core-render!       (fn [app {:keys [root-props-changed?] :as options}]
                                              (let [{:com.fulcrologic.fulcro.application/keys [runtime-atom]} app
                                                    {:com.fulcrologic.fulcro.application/keys [root-class]} (some-> runtime-atom deref)]
                                                (when root-class
                                                  (let [optimized-render! (ah/app-algorithm app :optimized-render!)
                                                        shared-props      (get @runtime-atom :com.fulcrologic.fulcro.application/shared-props)]
                                                    (binding [*app*    app
                                                              *shared* shared-props]
                                                      (if optimized-render!
                                                        (optimized-render! app (merge options {:root-props-changed? root-props-changed?}))
                                                        (log/debug "Render skipped. No optimized render is configured.")))))))
                        :render-root!       (fn [element-tree dom-node]
                                              (r/render dom-node
                                                (rendered-tree->replicant-hiccup element-tree)))}
                       options))))

(comment
  (rc/component-options Foo)
  (rc/get-query Foo)
  (Foo {})
  (let [ui-tree (binding [*app* {:sampleapp 1}]
                  (ui-foo {:foo/id 1 :foo/name "Tom"}))]
    (rendered-tree->replicant-hiccup
      ui-tree)))
