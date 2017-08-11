(ns recipes.tabbed-interface-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [fulcro.server :refer [defquery-root defquery-entity defmutation server-mutate]]
            [taoensso.timbre :as timbre]
            [fulcro.easy-server :as core]))

; This is the only thing we wrote for the server...just return some value so we can
; see it really talked to the server for this query.
(defquery-root :all-settings
  (value [env params]
    [{:id 1 :value "Gorgon"}
     {:id 2 :value "Thraser"}
     {:id 3 :value "Under"}]))
