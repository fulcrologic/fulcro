(ns recipes.tabbed-interface-server
  (:require [om.next.server :as om]
            [om.next.impl.parser :as op]
            [cards.server-api :as api]
            [taoensso.timbre :as timbre]
            [untangled.easy-server :as core]))

; This is the only thing we wrote for the server...just return some value so we can
; see it really talked to the server for this query.
(defmethod api/server-read :all-settings [env dispatch-key params]
  {:value [{:id 1 :value "Gorgon"}
           {:id 2 :value "Thraser"}
           {:id 3 :value "Under"}]})
