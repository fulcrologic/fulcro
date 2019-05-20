(ns com.fulcrologic.fulcro.specifications.extended-specs
  (:require
    [clojure.spec.alpha :as s])
  #?(:clj
     (:import (clojure.lang Atom))))

(defn atom? [a] (instance? Atom a))

(defn atom-of [content-spec]
  (fn [x]
    (and
      (atom? x)
      (s/valid? content-spec (deref x)))))

