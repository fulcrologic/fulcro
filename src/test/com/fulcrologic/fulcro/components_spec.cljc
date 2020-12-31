(ns com.fulcrologic.fulcro.components-spec
  (:require
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    #?(:clj  [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])
    #?(:cljs [goog.object :as gobj])
    [fulcro-spec.core :refer [specification assertions behavior component]]))

(defsc A [this props]
  {:ident         :person/id
   :query         [:person/id :person/name]
   :initial-state {:person/id 1 :person/name "Tony"}
   :extra-data    42}
  (dom/div "TODO"))

(defsc AWithHooks [this props]
  {:ident         :person/id
   :query         [:person/id :person/name]
   :initial-state {:person/id 1 :person/name "Tony"}
   :extra-data    42
   :use-hooks?    true}
  (dom/div "TODO"))

(defsc B [this props]
  {:ident         :person/id
   :query         [:person/id :person/name]
   :initial-state (fn [{:keys [id]}] {:person/id id :person/name "Tony"})
   :extra-data    42}
  (dom/div "TODO"))

(def ui-a (comp/factory A))

(specification "Component basics"
  (let [ui-a (comp/factory A)]
    (assertions
      "Supports arbitrary option data"
      (-> A comp/component-options :extra-data) => 42
      "Component class can be detected"
      (comp/component-class? A) => true
      (comp/component-class? (ui-a {})) => false
      "The component name is available from the class"
      (comp/component-name A) => (str `A)
      "The registry key is available from the class"
      (comp/class->registry-key A) => ::A
      "Can be used to obtain the ident"
      (comp/get-ident A {:person/id 4}) => [:person/id 4]
      "Can be used to obtain the query"
      (comp/get-query A) => [:person/id :person/name]
      (comp/get-query AWithHooks) => [:person/id :person/name]
      "Initial state"
      (comp/get-initial-state A) => {:person/name "Tony" :person/id 1}
      (comp/get-initial-state B {:id 22}) => {:person/name "Tony" :person/id 22}
      "The class is available in the registry using a symbol or keyword"
      (comp/registry-key->class ::A) => A
      (comp/registry-key->class `A) => A)))

(specification "computed props"
  (assertions
    "Can be added and extracted on map-based props"
    (comp/get-computed (comp/computed {} {:x 1})) => {:x 1}
    "Can be added and extracted on vector props"
    (comp/get-computed (comp/computed [] {:x 1})) => {:x 1}))

(specification "newer-props"
  (assertions
    "Returns the first props if neither are timestamped"
    (comp/newer-props {:a 1} {:a 2}) => {:a 1}
    "Returns the second props if the first are nil"
    (comp/newer-props nil {:a 2}) => {:a 2}
    "Returns the newer props if both have times"
    (comp/newer-props (fdn/with-time {:a 1} 1) (fdn/with-time {:a 2} 2)) => {:a 2}
    (comp/newer-props (fdn/with-time {:a 1} 2) (fdn/with-time {:a 2} 1)) => {:a 1}
    "Returns the second props if the times are the same"
    (comp/newer-props (fdn/with-time {:a 1} 1) (fdn/with-time {:a 2} 1)) => {:a 2}))

(specification "classname->class"
  (assertions
    "Returns from registry under fq keyword"
    (nil? (comp/registry-key->class ::A)) => false
    "Returns from registry under fq symbol"
    (nil? (comp/registry-key->class `A)) => false))

(specification "react-type"
  (assertions
    "Returns the class when passed an instance"
    #?(:clj  (comp/react-type (ui-a {}))
       :cljs (comp/react-type (A.))) => A))

(specification "wrap-update-extra-props"
  (let [wrapper       (comp/wrap-update-extra-props (fn [_ p] (assoc p :X 1)))
        updated-props (wrapper A #js {})]
    (assertions
      "Places extra props in raw props at :fulcro$extra_props"
      (comp/isoget updated-props :fulcro$extra_props) => {:X 1})))
