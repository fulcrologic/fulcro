(ns com.fulcrologic.fulcro.application-spec
  (:require
    [fulcro-spec.core :refer [specification provided! when-mocking! assertions 
                              behavior when-mocking component 
                              => =check=> =fn=>]]
    [fulcro-spec.check :as _]
    [clojure.spec.alpha :as s]
    [clojure.walk :as walk]
    [edn-query-language.core :as eql]
    [com.fulcrologic.fulcro.specs]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.routing.dynamic-routing :as dr]
    [com.fulcrologic.fulcro.ui-state-machines :as uism]
    [com.fulcrologic.fulcro.application :as app :refer [fulcro-app]]
    [com.fulcrologic.fulcro.raw.application :as rapp]
    [com.wsscode.pathom.core :as p]
    [clojure.test :refer [is are deftest]]
    [clojure.set :as set]))

(deftest application-constructor
  (let [app (app/fulcro-app)]
    (assertions
      "Conforms to the application spec"
      (s/explain-data ::app/app app) => nil)))

(specification "Static extensible configuration"
  (let [app (app/fulcro-app {:external-config {::x 1}})]
    (assertions
      "Allows arbitrary k-v pairs to be added to the static config"
      (comp/external-config app ::x) => 1)))

(specification "Default query elision"
  (behavior "Removes ui-namespaced keys that are in props, joins, idents, and mutation joins"
    (are [query result]
      (= result (remove #{:com.wsscode.pathom.core/errors} 
                        (eql/ast->query (app/default-global-eql-transform (eql/query->ast query)))))
      [:ui/name :x]  [:x]
      '[(:ui/name {:b 2}) :x] [:x]
      [{:ui/name [:b]} :x] [:x]
      [[:ui/name 42] :x] [:x]
      [{[:ui/name 42] [:t]} :x] [:x]
      [:ui/name :x :ui/adsf-b] [:x]))
  (behavior "Removes ui and fulcro keys from mutation join queries, but NOT mutation params"
    (are [query result] ; FIXME fails due to added :tempids
      (= result (eql/ast->query (app/default-global-eql-transform (eql/query->ast query))))
      [{'(f {:ui/param 1 :com.fulcrologic.fulcro/param 42}) [:ui/a :b {:com.fulcrologic.fulcro.core/boo [:y]}]}]
      [{'(f {:ui/param 1 :com.fulcrologic.fulcro/param 42}) [:b]}]))
  (behavior "Removes items that are namespaced to Fulcro itself"
    (are [query result]
      (= result (remove #{:com.wsscode.pathom.core/errors} 
                        (eql/ast->query (app/default-global-eql-transform (eql/query->ast query)))))
      [{[::uism/asm-id 42] [:y]} :x] [:x]
      [::uism/asm-id :x] [:x]
      [{::uism/asm-id [:y]} :x] [:x]
      [::dr/id ::dr/current-route [::uism/asm-id '_] :x] [:x])))

(defn sort-nested 
  "Sort keywords in vectors anywhere in the query to simplify comparison"
  [query]
  (letfn [(compare-any [x y]
            (if (every? keyword? [x y])
              (compare x y)
              (if (keyword? x) -1 1)))]
    (walk/postwalk
      #(cond-> %
         (vector? %) (->> (sort compare-any) vec))
      query)))

(defn transform-query [query]
  (-> query
      eql/query->ast
      rapp/default-global-eql-transform
      eql/ast->query))
(-> (eql/query->ast '[{(my-mutation) [:x]}]) :children first)
(defn superset?*
  "Takes an `expected` set, and will check that `actual` contains at least all the elements in it.
   Eg:
   ```
   ; PASSES
   #{:a :b} =check=> (superset?* #{:a})

   ; FAILS
   #{:a} =check=> (superset?* #{:a :b})
   ```"
  [expected]
  ;(assert-is! `superset?* set? expected)
  (_/and*
    (_/is?* seqable?)
    (_/checker [actual]
      (when-not (set/subset? expected (set actual))
        {:actual {:extra-values (set/difference expected (set actual))}
         :expected `(~'superset?* ~expected)
         :message "Found values missing from the set"}))))

;; (letfn [(transform [query] (sort-nested (transform-query query)))]
;;   (transform '[(mutation6)]))

(specification "Default EQL transform additions"
  (behavior "Adds ::p/errors to all queries BUT not mutations"
    (letfn [(transform [query] (sort-nested (transform-query query)))]
      (assertions
        (transform [:a]) => [:a ::p/errors]
        (transform [{:d [:e]}]) =check=> (superset?* #{::p/errors {:d [:e]}})
        ;; Do not add ::p/errors
        (transform '[(mutation1)]) =fn=> (fn [act] (not (some #{::p/errors} act)))
        (transform '[(mutation2 {:b 1})]) =fn=> (fn [act] (not (some #{::p/errors} act)))
        (transform '[{(mutation3) [:c]}]) =fn=> (fn [act] (not (some #{::p/errors} act)))))
    #_(are [query result] (= result (sort-nested (transform-query query)))
      ;[:a] [:a ::p/errors]
      ;[{:d [:e]}] [::p/errors {:d [:e]}]
      ; FIXME For mutations, do we need `[::p/errors (mutation1)]` or `[{(mutation1) [::p/errors]}]`?
      ; FIXME try 2 mutations: [(mut1) (mut2)] - what do we expect?!
      ; NOTE: [(mut1)] returns {mut1 {::p/reader-error "some text"}} automatically
      '[(mutation1)] '[::p/errors (mutation1)] ; FIXME fails b/c there is also :tempids
      '[(mutation2 {:b 1})] '[::p/errors (mutation2 {:b 1})] ; FIXME fails b/c there is also :tempids
      '[{(mutation3) [:c]}] '[::p/errors {(mutation3) [:c]}])) ; FIXME fails b/c there is also :tempids
  (behavior "Adds :tempids to (only) mutation joins"
    (are [query result] (= result (remove keyword? (sort-nested (transform-query query))))
      '[(mutation4 {:b 1})] '[{(mutation4 {:b 1}) [:tempids *]}]
      '[{(mutation5) [:c]}] '[{(mutation5) [:c :tempids]}])
    (behavior "Preserves Pathom's behavior of returning the whole mutation's output if the user hasn't asked for anything in particular"
      (are [query result] (= result (sort-nested (transform-query query)))
        '[(mutation6)] '[{(mutation6) [:tempids *]}]))))


(comment
  (in-ns (.getName *ns*))
  ;; Make sure the REPL runner is loaded
  (require 'fulcro-spec.reporters.repl)
  ;; Run all tests in current ns
  (fulcro-spec.reporters.repl/run-tests)


  )