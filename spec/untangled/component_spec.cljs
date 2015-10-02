(ns untangled.component-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior provided assertions)]
                   )
  (:require
    [untangled.state :as state]
    [untangled.logging :as logging]
    [cljs.test :refer [do-report]]
    smooth-spec.stub
    cljs.pprint
    [untangled.component :as c]
    [untangled.core :as core]
    [untangled.application :as app]
    [untangled.events :as evt])
  )

(defn fake-renderer [id context & args] {:id id :args args})

(specification "List builder"
               (behavior "requires state" (is (thrown-with-msg? js/Error #"context is required" (c/build-list :renderer 1 :list-key 2 :item-key 3))))
               (behavior "requires renderer" (is (thrown-with-msg? js/Error #"renderer is required" (c/build-list :context 1 :list-key 2 :item-key 3))))
               (behavior "requires list-key" (is (thrown-with-msg? js/Error #"list-key is required" (c/build-list :context 1 :renderer 1 :item-key 3))))
               (behavior "requires item-key" (is (thrown-with-msg? js/Error #"item-key is required" (c/build-list :context 1 :renderer 1 :list-key 2))))
               (let [context (state/new-sub-context (state/root-context (core/new-application nil {:items [{:k 1 :v "a"} {:k 2 :v "b"}]})) :top nil)]
                 (behavior "renders lists of items within the state of a component"
                           (provided "render function is called"
                                     (fake-renderer id context listeners) =2x=> :rendered-item

                                     (assertions
                                       (c/build-list :renderer fake-renderer
                                                     :context context
                                                     :list-key :items
                                                     :item-key :k) => [:rendered-item :rendered-item]
                                       )))
                 (behavior "filters items based on a filter function"
                           )
                 (behavior "connects event handlers using a provided event constructor")
                 (behavior "embeds the correct key for each element in the renderer")
                 (behavior "embeds the correct key for each element in the renderer when elements are filtered"))
               )

