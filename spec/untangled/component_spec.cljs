(ns untangled.component-spec
  (:require-macros [cljs.test :refer (is deftest testing)]
                   [smooth-spec.core :refer (specification behavior component provided assertions)]
                   )
  (:require
    [untangled.logging :as logging]
    [cljs.test :refer [do-report]]
    smooth-spec.stub
    cljs.pprint
    [untangled.component :as c :include-macros true]
    [untangled.core :as core]
    [untangled.events :as evt]
    [untangled.state :as state]
    [untangled.application :as a]
    [goog.dom :as dom])
  )

;(enable-console-print!)
;(defn dbg [x] (cljs.pprint/pprint x) x)

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



(defn make-container []
  {:title     "Hi there"
   :order     :ascending
   :filter-by identity
   :items     {:k1  {:key :k1 :value "Hi 1" :rank 1}
               :k9  {:key :k9 :value "Hi nine" :rank 9}
               :k29 {:key :k29 :value "OHAI twenty-nine" :rank 29}
               :k5  {:key :k5 :value "Hi 5" :rank 5}}})

(c/defscomponent
  Item
  "an item"
  :keyfn :key
  [data context]
  (c/div {:className "item"} (:value data)))

(c/defscomponent
  Container
  "A container"
  [data context]
  (c/div {:className "container"}
         (c/span {} (:title data))
         "stuff to do..."
         (c/render-mapped-list Item data context :items :rank
                               :filter-fn (:filter-by data)
                               :comparator (case (:order data)
                                             :ascending compare
                                             :descending (comp - compare)
                                             compare))))

;; "Borrowed" from Jozef Wagner:  https://groups.google.com/forum/#!topic/clojure/unHrE3amqNs
(defn nodelist->seq
  "Converts nodelist to (not lazy) seq."
  [nl]
  (let [result-seq (map #(.item nl %) (range (.-length nl)))]
    (doall result-seq)))

(defn gather-items []
  (nodelist->seq (dom/getElementsByClass "item")))

(defn contents-of-items
  ([] (contents-of-items (gather-items)))
  ([items] (map #(.-textContent %) items)))

(specification "Container component"
               (component "via render-mapped-list"
                          (let [renderer #(Container %1 %2)
                                app-state (make-container)
                                new-cont (core/new-application renderer app-state :target "test-app")
                                context (a/top-context new-cont)
                                transact! (partial state/transact! context)]
                            (behavior "renders sorted values in a depth-one nested map"
                                      (dom/removeChildren (dom/getElement "test-app"))
                                      (a/render new-cont)
                                      (is (= (contents-of-items)
                                             ["Hi 1"
                                              "Hi 5"
                                              "Hi nine"
                                              "OHAI twenty-nine"])))
                            (behavior "supports changing sort order **Non-deterministic unless used in test mode**"
                                      (transact! (fn [cont] (assoc cont :order :descending)))
                                      (is (= (contents-of-items)
                                             ["OHAI twenty-nine"
                                              "Hi nine"
                                              "Hi 5"
                                              "Hi 1"]))
                                      ; Return to the beginning state.
                                      (transact! (fn [cont] (assoc cont :order :ascending))))
                            (behavior "supports changing filter **Non-deterministic unless used in test mode**"
                                      (transact! (fn [cont] (assoc cont
                                                              :filter-by #(< 4 (count (:value %))))))
                                      (is (= (contents-of-items)
                                             ["Hi nine"
                                              "OHAI twenty-nine"]))
                                      ; Return to the beginning state.
                                      (transact! (fn [cont] (assoc cont :filter-by identity)))))))
