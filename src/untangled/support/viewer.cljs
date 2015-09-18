(ns untangled.support.viewer
  (:require [untangled.component :as c :include-macros true]
            [untangled.core :as core]
            [cljs.reader :refer [register-tag-parser!]]
            [untangled.state :as state]
            [untangled.i18n :refer-macros [tr trf]]
            [cljs.reader :as r]
            [untangled.history :as h])
  )

;; A history playback utility for support to diagnose issues

(defrecord SeekPosition [time point-in-time])

(defn dbg [v] (cljs.pprint/pprint v) v)

(defn seek-positions [history] (mapv (fn [point-in-time] (SeekPosition. (-> point-in-time :app-state :time) point-in-time)) (:entries history)))
(defn current-app-state [viewer] (:top (:app-state (:point-in-time (nth (-> viewer :seek-positions) (:current-position viewer))))))

(defn make-viewer [renderer history]
  {
   :on-top           false
   :current-position 0                                      ; most recent...incremented numbers go back in time
   :renderer         renderer
   :seek-positions   (seek-positions history)
   })

(defn at-beginning [viewer] (= (inc (:current-position viewer)) (count (:seek-positions viewer))))
(defn at-end [viewer] (= (:current-position viewer) 0))

(defn previous-frame
  "Move to the previous 'frame' of application state"
  [viewer]
  (if (not (at-beginning viewer))
    (update viewer :current-position inc)
    viewer))

(defn next-frame
  "Move to the next 'frame' of application state"
  [viewer]
  (if (not (at-end viewer))
    (update viewer :current-position dec)
    viewer))

(defn current-time [viewer] (get-in viewer [:seek-positions (:current-position viewer) :time]))
(defn toggle-position [viewer] (update viewer :on-top not))

(declare HistoryPlayer)

(c/defscomponent HistoryPlayer
                 "A component to playback history"
                 [viewer context]
                 (let [op (state/op-builder context)
                       application (core/new-application (:renderer viewer) (current-app-state viewer) :view-only true)]
                   (c/div {:className "app"}
                          (core/Root (current-app-state viewer) application)
                          (c/div {:className (str "clearfix vcr-controls" (if (:on-top viewer) " top" " bottom"))}
                                 (c/button {:className "reposition" :onClick (op toggle-position)} (trf "Move to {position}" :position (if (:on-top viewer) (tr "Bottom") (tr "Top"))))
                                 (c/div {:className "container"}
                                        (c/button {:className "pull-left btn btn-default" :onClick (op previous-frame)} (c/span {:className "glyphicon glyphicon-step-backward"} ""))
                                        (c/div {:className "status-area pull-left"}
                                               (c/span {:className "current-position"} (trf "{when, date, long} {when, time, medium}" :when (current-time viewer)))
                                               (c/br {})
                                               (if (at-beginning viewer) (c/span {:className "current-position"} (tr "Beginning of recording")))
                                               (if (at-end viewer) (c/span {:className "current-position"} (tr "End of recording")))
                                               )
                                        (c/button {:className "pull-right btn btn-default" :onClick (op next-frame)} (c/span {:className "glyphicon glyphicon-step-forward"} "")))
                                 )))
                 )

(defonce snapshot-uri "/application/snapshot")

(register-tag-parser! "untangled.history.History" h/map->History)
(register-tag-parser! "untangled.history.PointInTime" h/map->PointInTime)

(defn snapshot
  "Record the given application's state in local storage in preparation for sending a viewable support case."
  [application]
  (.setItem js/localStorage snapshot-uri (pr-str @(:history application))))

(defn get-snapshot
  "Pull the most recent application snapshot from local storage"
  []
  (r/read-string (.getItem js/localStorage snapshot-uri)))
