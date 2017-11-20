(ns recipes.cascading-dropdown-server
  (:require
    [fulcro.ui.bootstrap3 :as bs]
    [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]))

(defquery-root :models
  (value [env {:keys [make]}]
    (Thread/sleep 2000)
    (case make
      :ford [(bs/dropdown-item :escort "Escort")
             (bs/dropdown-item :F-150 "F-150")]
      :honda [(bs/dropdown-item :civic "Civic")
              (bs/dropdown-item :accort "Accord")])))
