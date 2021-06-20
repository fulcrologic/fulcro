(ns com.fulcrologic.fulcro.raw.components-spec
  (:require
    [com.fulcrologic.fulcro.raw.components :as rc]
    [com.fulcrologic.fulcro.components :refer [defsc]]
    [fulcro-spec.core :refer [specification assertions behavior component =>]]))

(defsc Product [_ _]
  {:query [:product/id :product/name]
   :ident :product/id})

(specification "nc"
  (component "Basic usage"
    (let [item-c    (rc/nc [:item/id {:item/product [:product/id :product/name]}])
          product-c (rc/get-subquery-component item-c [:item/product])]
      (assertions
        "Generates a component class"
        (rc/component-class? item-c) => true
        "Generates ident function for top"
        (rc/get-ident item-c {:item/id 1}) => [:item/id 1]
        "Generates ident for subcomponent"
        (rc/get-ident product-c {:product/id 2}) => [:product/id 2]
        "Generates correct queries"
        (rc/get-query item-c) => [:item/id {:item/product [:product/id :product/name]}]
        (rc/get-query product-c) => [:product/id :product/name])))
  (component "Recursive usage"
    (let [product-c        (rc/nc [:product/id :product/name]
                             {:componentName ::Sample})
          item-c           (rc/nc [:item/id {:item/product (rc/get-query product-c)}])
          nested-product-c (rc/get-subquery-component item-c [:item/product])]
      (assertions
        "Includes named components in the registry"
        (rc/registry-key->class ::Sample) => product-c
        "Nested component is the one we find through the query"
        nested-product-c => product-c)))
  (component "Composition with normal components"
    (let [item-c           (rc/nc [:item/id {:item/product (rc/get-query Product)}])
          nested-product-c (rc/get-subquery-component item-c [:item/product])]
      (assertions
        "Uses the component in the nested location"
        (rc/registry-key->class ::Product) => nested-product-c
        "Resulting query is correct"
        (rc/get-query item-c) => [:item/id {:item/product [:product/id :product/name]}]))))
