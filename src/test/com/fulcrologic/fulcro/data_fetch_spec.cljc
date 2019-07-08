(ns com.fulcrologic.fulcro.data-fetch-spec
  (:require
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [clojure.test :refer [is are]]
    [fulcro-spec.core :refer [specification behavior assertions provided component when-mocking]]
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))


;; TODO: Tests for load finish/failed routines would be nice.
