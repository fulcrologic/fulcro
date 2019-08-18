(ns com.fulcrologic.fulcro.macros.defsc-spec
  (:require
    [com.fulcrologic.fulcro.components :as defsc]
    [fulcro-spec.core :refer [assertions specification component]]
    [clojure.test :refer [deftest is]]
    [com.fulcrologic.fulcro.algorithms.do-not-use :as util])
  (:import (clojure.lang ExceptionInfo)))

(declare =>)

(deftest ^:focus destructured-keys-test
  (assertions
    "Finds the correct keys for arbitrary destructuring"
    (util/destructured-keys '{:keys [a b]}) => #{:a :b}
    (util/destructured-keys '{:some-ns/keys [a b]}) => #{:some-ns/a :some-ns/b}
    (util/destructured-keys '{:x/keys  [a b]
                               :y/keys [n m]}) => #{:x/a :x/b :y/n :y/m}
    (util/destructured-keys '{:y/keys [n m]
                               boo    :gobble/that}) => #{:y/n :y/m :gobble/that}
    (util/destructured-keys '{:keys   [:a.b/n db/id]
                               ::keys [x]
                               }) => #{:a.b/n :db/id ::x}))

(deftest defsc-macro-helpers-test
  (component "build-render form"
    (assertions
      "emits a list of forms for the render itself"
      (#'defsc/build-render 'Boo 'this {:keys ['a]} {:keys ['onSelect]} nil '((dom/div nil "Hello")))
      => `(~'fn ~'render-Boo [~'this]
            (com.fulcrologic.fulcro.components/wrapped-render ~'this
              (fn []
                (let [{:keys [~'a]} (com.fulcrologic.fulcro.components/props ~'this)
                      {:keys [~'onSelect]} (com.fulcrologic.fulcro.components/get-computed ~'this)]
                  (~'dom/div nil "Hello")))))
      "all arguments after props are optional"
      (#'defsc/build-render 'Boo 'this {:keys ['a]} nil nil '((dom/div nil "Hello")))
      => `(~'fn ~'render-Boo [~'this]
            (com.fulcrologic.fulcro.components/wrapped-render ~'this
              (fn []
                (let [{:keys [~'a]} (com.fulcrologic.fulcro.components/props ~'this)]
                  (~'dom/div nil "Hello")))))
      "destructuring of css is allowed"
      (#'defsc/build-render 'Boo 'this {:keys ['a]} {:keys ['onSelect]} '{:keys [my-class]} '((dom/div nil "Hello")))
      => `(~'fn ~'render-Boo [~'this]
            (com.fulcrologic.fulcro.components/wrapped-render ~'this
              (fn []
                (let [{:keys [~'a]} (com.fulcrologic.fulcro.components/props ~'this)
                      {:keys [~'onSelect]} (com.fulcrologic.fulcro.components/get-computed ~'this)
                      ~'{:keys [my-class]} (com.fulcrologic.fulcro.components/get-extra-props ~'this)]
                  (~'dom/div nil "Hello")))))))
  (component "build-query-forms"
    (is (thrown-with-msg? ExceptionInfo #"defsc X: .person/nme.* was destructured"
          (#'defsc/build-query-forms nil 'X 'this '{:keys [db/id person/nme person/job]}
            {:template '[:db/id :person/name {:person/job (defsc/get-query Job)}]}))
      "Verifies the propargs matches queries data when not a symbol")
    (assertions
      "Support a method form"
      (#'defsc/build-query-forms nil 'X 'this 'props {:method '(fn [] [:db/id])})
      => '(fn query* [this] [:db/id])
      "Uses symbol from external-looking scope in output"
      (#'defsc/build-query-forms nil 'X 'that 'props {:method '(query [] [:db/id])})
      => '(fn query* [that] [:db/id])
      "Honors the symbol for this that is defined by defsc"
      (#'defsc/build-query-forms nil 'X 'that 'props {:template '[:db/id]})
      => '(fn query* [that] [:db/id])
      "Composes properties and joins into a proper query expression as a list of defui forms"
      (#'defsc/build-query-forms nil 'X 'this 'props {:template '[:db/id :person/name {:person/job (defsc/get-query Job)} {:person/settings (defsc/get-query Settings)}]})
      => `(~'fn ~'query* [~'this] [:db/id :person/name {:person/job (~'defsc/get-query ~'Job)} {:person/settings (~'defsc/get-query ~'Settings)}])))
  (component "build-ident form"
    (is (thrown-with-msg? ExceptionInfo #"The ID property " (#'defsc/build-ident nil 't 'p {:template [:TABLE/by-id :id]} #{})) "Throws if the query/ident template don't match")
    (is (thrown-with-msg? ExceptionInfo #"The table/id :id of " (#'defsc/build-ident nil 't 'p {:keyword :id} #{})) "Throws if the keyword isn't found in the query")
    (assertions
      "Generates nothing when there is no table"
      (#'defsc/build-ident nil 't 'p nil #{}) => nil
      (#'defsc/build-ident nil 't 'p nil #{:boo}) => nil
      "Can use a simple keyword for both table and id"
      (#'defsc/build-ident nil 't '{:keys [a b c]} {:keyword :person/id} #{:person/id})
      => '(fn ident* [_ props] [:person/id (:person/id props)])
      "Can use a ident method to build the defui forms"
      (#'defsc/build-ident nil 't 'p {:method '(fn [] [:x :id])} #{})
      => '(fn ident* [t p] [:x :id])
      "Can include destructuring in props"
      (#'defsc/build-ident nil 't '{:keys [a b c]} {:method '(fn [] [:x :id])} #{})
      => '(fn ident* [t {:keys [a b c]}] [:x :id])
      "Can use a vector template to generate defui forms"
      (#'defsc/build-ident nil 't 'p {:template [:TABLE/by-id :id]} #{:id})
      => '(fn ident* [this props] [:TABLE/by-id (:id props)])))
  (component "build-initial-state"
    (is (thrown-with-msg? ExceptionInfo #"defsc S: Illegal parameters to :initial-state"
          (#'defsc/build-initial-state nil 'S {:template {:child '(get-initial-state P {})}} #{:child}
            '{:template [{:child (defsc/get-query S)}]})) "Throws an error in template mode if any of the values are calls to get-initial-state")
    (is (thrown-with-msg? ExceptionInfo #"When query is a method, initial state MUST"
          (#'defsc/build-initial-state nil 'S {:template {:x 1}} #{} {:method '(fn [t] [])}))
      "If the query is a method, the initial state must be as well")
    (is (thrown-with-msg? ExceptionInfo #"Initial state includes keys"
          (#'defsc/build-initial-state nil 'S {:template {:x 1}} #{} {:template [:x]}))
      "In template mode: Disallows initial state to contain items that are not in the query")
    (assertions
      "Generates nothing when there is entry"
      (#'defsc/build-initial-state nil 'S nil #{} {:template []}) => nil
      "Can build initial state from a method"
      (#'defsc/build-initial-state nil 'S {:method '(fn [p] {:x 1})} #{} {:template []}) =>
      '(fn build-raw-initial-state* [p] {:x 1})
      "Can build initial state from a template"
      (#'defsc/build-initial-state nil 'S {:template {}} #{} {:template []}) =>
      '(fn build-initial-state* [params] (com.fulcrologic.fulcro.components/make-state-map {} {} params))
      "Allows any state in initial-state method form, independent of the query form"
      (#'defsc/build-initial-state nil 'S {:method '(fn [p] {:x 1 :y 2})} #{} {:tempate []})
      => '(fn build-raw-initial-state* [p] {:x 1 :y 2})
      (#'defsc/build-initial-state nil 'S {:method '(initial-state [p] {:x 1 :y 2})} #{} {:method '(query [t] [])})
      => '(fn build-raw-initial-state* [p] {:x 1 :y 2})
      "Generates proper state parameters to make-state-map when data is available"
      (#'defsc/build-initial-state nil 'S {:template {:x 1}} #{:x} {:template [:x]})
      => '(fn build-initial-state* [params]
            (com.fulcrologic.fulcro.components/make-state-map {:x 1} {} params))))
  (component "replace-and-validate-fn"
    (is (thrown-with-msg? ExceptionInfo #"Invalid arity for nm"
          (#'defsc/replace-and-validate-fn nil 'nm ['a] 2 '(fn [p] ...)))
      "Throws an exception if there are too few arguments")
    (assertions
      "Replaces the first symbol in a method/lambda form"
      (#'defsc/replace-and-validate-fn nil 'nm [] 0 '(fn [] ...)) => '(fn nm [] ...)
      "Allows correct number of args"
      (#'defsc/replace-and-validate-fn nil 'nm ['this] 1 '(fn [x] ...)) => '(fn nm [this x] ...)
      "Allows too many args"
      (#'defsc/replace-and-validate-fn nil 'nm ['this] 1 '(fn [x y z] ...)) => '(fn nm [this x y z] ...)
      "Prepends the additional arguments to the front of the argument list"
      (#'defsc/replace-and-validate-fn nil 'nm ['that 'other-thing] 3 '(fn [x y z] ...)) => '(fn nm [that other-thing x y z] ...))))

