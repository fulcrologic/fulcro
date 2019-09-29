(ns com.fulcrologic.fulcro.algorithms.transit-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [cognitect.transit :as t]
    [clojure.test :refer [are deftest is]]
    [fulcro-spec.core :refer [specification assertions]]))

(specification "transit-clj->str and str->clj"
  (let [meta-rtrip (ft/transit-str->clj (ft/transit-clj->str (with-meta {:k (with-meta [] {:y 2})} {:x 1})))]
    (assertions
      "Encode clojure data structures to strings"
      (string? (ft/transit-clj->str {})) => true
      (string? (ft/transit-clj->str [])) => true
      (string? (ft/transit-clj->str 1)) => true
      (string? (ft/transit-clj->str 22M)) => true
      (string? (ft/transit-clj->str #{1 2 3})) => true
      "Can decode encodings"
      (ft/transit-str->clj (ft/transit-clj->str {:a 1})) => {:a 1}
      (ft/transit-str->clj (ft/transit-clj->str #{:a 1})) => #{:a 1}
      (ft/transit-str->clj (ft/transit-clj->str "Hi")) => "Hi"
      "Preserves metadata"
      (meta meta-rtrip) => {:x 1}
      (-> meta-rtrip :k meta) => {:y 2})))
