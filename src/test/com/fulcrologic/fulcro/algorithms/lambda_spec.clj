(ns com.fulcrologic.fulcro.algorithms.lambda-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.lambda :as lambda]
    [fulcro-spec.core :refer [=> assertions specification]]))

;; =============================================================================
;; fn-arities Tests
;; =============================================================================

(specification "fn-arities"
  (assertions
    "Detects single arity function"
    (:variadic? (lambda/fn-arities (fn [x] x))) => false
    (:arities (lambda/fn-arities (fn [x] x))) => #{1}

    "Detects arity-0 function"
    (:arities (lambda/fn-arities (fn [] :zero))) => #{0}

    "Detects arity-2 function"
    (:arities (lambda/fn-arities (fn [x y] (+ x y)))) => #{2}

    "Detects multiple arities"
    (:arities (lambda/fn-arities (fn ([x] x) ([x y] (+ x y))))) => #{1 2}

    "Detects three arities"
    (:arities (lambda/fn-arities (fn ([] 0) ([x] x) ([x y] (+ x y))))) => #{0 1 2}

    "Detects variadic function"
    (:variadic? (lambda/fn-arities (fn [x & more] x))) => true

    "Returns min-arity for variadic"
    (:min-arity (lambda/fn-arities (fn [x & more] x))) => 1

    "Variadic with no required args"
    (:min-arity (lambda/fn-arities (fn [& args] args))) => 0))

;; =============================================================================
;; ->arity-tolerant Tests
;; =============================================================================

(specification "->arity-tolerant with nil"
  (assertions
    "Returns nil for nil input"
    (lambda/->arity-tolerant nil) => nil))

(specification "->arity-tolerant with variadic functions"
  (let [f       (fn [& args] (count args))
        wrapped (lambda/->arity-tolerant f)]
    (assertions
      "Passes through variadic functions unchanged"
      (wrapped) => 0
      (wrapped 1) => 1
      (wrapped 1 2 3) => 3
      (wrapped 1 2 3 4 5) => 5)))

(specification "->arity-tolerant with fixed arity functions"
  (let [f       (fn [x] (* x 2))
        wrapped (lambda/->arity-tolerant f)]
    (assertions
      "Calls with exact arity works"
      (wrapped 5) => 10

      "Extra arguments are dropped"
      (wrapped 5 :extra) => 10
      (wrapped 5 :extra :args :ignored) => 10)))

(specification "->arity-tolerant with zero-arity functions"
  (let [call-count (atom 0)
        f          (fn [] (swap! call-count inc))
        wrapped    (lambda/->arity-tolerant f)]
    (wrapped {:type "click"})                               ; Called with an event like JS would
    (assertions
      "Zero-arity function can be called with extra args"
      @call-count => 1)))

(specification "->arity-tolerant with multi-arity functions"
  (let [f       (fn
                  ([x] :one)
                  ([x y] :two)
                  ([x y z] :three))
        wrapped (lambda/->arity-tolerant f)]
    (assertions
      "Selects arity-1"
      (wrapped 1) => :one

      "Selects arity-2"
      (wrapped 1 2) => :two

      "Selects arity-3"
      (wrapped 1 2 3) => :three

      "Extra args dropped, uses highest matching arity"
      (wrapped 1 2 3 4 5) => :three)))

(specification "->arity-tolerant selects best matching arity"
  (let [f       (fn
                  ([x] :one)
                  ([x y z] :three))                         ; Gap at arity-2
        wrapped (lambda/->arity-tolerant f)]
    (assertions
      "Uses arity-1 when called with 1 arg"
      (wrapped 1) => :one

      "Uses arity-1 when called with 2 args (no arity-2)"
      (wrapped 1 2) => :one

      "Uses arity-3 when called with 3 args"
      (wrapped 1 2 3) => :three

      "Uses arity-3 when called with more args"
      (wrapped 1 2 3 4) => :three)))

(specification "->arity-tolerant throws when no suitable arity"
  (let [f       (fn [x y] (+ x y))                          ; Requires at least 2 args
        wrapped (lambda/->arity-tolerant f)]
    (assertions
      "Throws when called with too few args"
      (wrapped) =throws=> clojure.lang.ExceptionInfo
      (wrapped 1) =throws=> clojure.lang.ExceptionInfo)))

(specification "->arity-tolerant preserves return values"
  (let [f       (fn [x] {:result x :computed (* x 2)})
        wrapped (lambda/->arity-tolerant f)]
    (assertions
      "Returns the function's return value"
      (wrapped 5) => {:result 5 :computed 10}
      (wrapped 5 :ignored) => {:result 5 :computed 10})))

(specification "->arity-tolerant with side effects"
  (let [state   (atom [])
        f       (fn [x] (swap! state conj x))
        wrapped (lambda/->arity-tolerant f)]
    (wrapped :a)
    (wrapped :b :ignored)
    (wrapped :c :ignored :also-ignored)
    (assertions
      "Side effects occur correctly"
      @state => [:a :b :c])))
