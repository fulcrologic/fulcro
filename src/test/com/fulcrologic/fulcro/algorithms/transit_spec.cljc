(ns com.fulcrologic.fulcro.algorithms.transit-spec
  (:require
    [com.fulcrologic.fulcro.algorithms.transit :as ft]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    #?@(:cljs
        [[cljs.reader :as reader]
         [com.cognitect.transit.types :as ty]])
    [cognitect.transit :as t]
    [clojure.test :refer [are deftest is]]
    [fulcro-spec.core :refer [specification assertions]]
    [taoensso.timbre :as log]))

(declare =>)

(deftype Cruft [v])
(deftype Nested [cruft])

(ft/install-type-handler! (ft/type-handler Cruft "test/cruft" (fn [^Cruft c] (.-v c)) #(Cruft. %)))
(ft/install-type-handler! (ft/type-handler Nested "test/nested" (fn [^Nested n] (.-cruft n)) #(Nested. %)))

(specification "transit-clj->str and str->clj"
  (let [meta-rtrip (ft/transit-str->clj (ft/transit-clj->str (with-meta {:k (with-meta [] {:y 2})} {:x 1})))]
    (assertions
      "Encode clojure data structures to strings"
      (string? (ft/transit-clj->str {})) => true
      (string? (ft/transit-clj->str [])) => true
      (string? (ft/transit-clj->str 1)) => true
      (string? (ft/transit-clj->str 22M)) => true
      (string? (ft/transit-clj->str #{1 2 3})) => true)
    (assertions
      "Can decode encodings"
      (ft/transit-str->clj (ft/transit-clj->str {:a 1})) => {:a 1}
      (ft/transit-str->clj (ft/transit-clj->str #{:a 1})) => #{:a 1}
      (ft/transit-str->clj (ft/transit-clj->str "Hi")) => "Hi")
    (assertions "Preserves metadata"
      (meta meta-rtrip) => {:x 1}
      (-> meta-rtrip :k meta) => {:y 2})
    (assertions
      "Registry auto-includes tempid support"
      (tempid/tempid? (ft/transit-str->clj (ft/transit-clj->str (tempid/tempid)))) => true
      "Automatically uses the global type registry"
      (.-v ^Cruft (ft/transit-str->clj (ft/transit-clj->str (Cruft. 42)))) => 42)))

(comment

  ;; Notes: Any dumb reader can read ALL POSSIBLE transit-encoded things (everything eventually ends up in a grounded type),
  ;; but it just puts them into a generic TaggedValue, which any dumb old writer can turn back into a proper stream. Therefore,
  ;; middlemen (like Inspect) can leverage that to interop without having to understand the actual data.
  #?(:cljs
     (defn tolerant-reader
       "Create a transit reader that tolerates any incoming thing.

       - `opts`: (optional) options to pass to `cognitect.transit/reader` (such as data type handlers)."
       []
       (t/reader :json {})))

  (def test-writer #?(:cljs (ft/writer)))
  (def dumb-writer #?(:cljs (t/writer :json {})))

  (let [encoded (t/write test-writer [1 (Nested. (Cruft. 43))])
        decoded (t/read (tolerant-reader) encoded)]
    [encoded
     (t/write dumb-writer decoded)])

  (reader/register-tag-parser! 'test/cruft (fn [v] (ty/taggedValue "test/cruft" v)))
  (t/write dumb-writer (reader/read-string "#test/cruft 42")))

