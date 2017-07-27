(ns recipes.autocomplete-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fulcro.server :refer [defquery-root defquery-entity defmutation]]
            [om.next.impl.parser :as op]))

(def airports-txt
  (mapv str/trim
    (-> (io/resource "recipes/airports.txt")
      slurp
      (str/split #"\n"))))

(defn airport-search [^String s]
  (->> airports-txt
    (filter (fn [i] (str/includes? (str/lower-case i) (str/lower-case s))))
    (take 10)
    vec))

(defquery-root :autocomplete/airports
  (value [env {:keys [search]}]
    (airport-search search)))

