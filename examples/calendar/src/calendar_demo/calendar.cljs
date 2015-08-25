(ns calendar-demo.calendar
  (:require
    [quiescent-model.state :as state]
    [quiescent-model.events :as evt]
    [quiescent.core :as q :include-macros true]
    [quiescent.dom :as d]
    cljs.pprint
    )
  (:require-macros [quiescent-model.component :as c])
  )

(declare weeks-of-interest)

(defn initial-calendar
  ([] (initial-calendar (js/Date.)))
  ([starting-js-date]
   (let [
         month (+ 1 (.getMonth starting-js-date))
         day (.getDate starting-js-date)
         year (.getFullYear starting-js-date)
         ]
     {
      :month            month
      :day              day
      :year             year
      :weeks            (weeks-of-interest month year)
      :overlay-visible? false
      }))
  )

(defonce ms-in-a-day 86400000)
(defn- prior-day [dt] (js/Date. (- (.getTime dt) ms-in-a-day)))
(defn- next-day [dt] (js/Date. (+ (.getTime dt) ms-in-a-day)))
(defn- weeks-of-interest
  "Returns a sequence of weeks (each of which contains 7 days) that should be included on a sunday-aligned calendar.
  The weeks are simple lists. The days are javascript Date objects. Their position in the week list indicates their
  day of the week (first position is sunday)."
  [month year]
  (let [zero-based-month (- month 1)
        first-day-of-month (js/Date. year zero-based-month 1 1 0 0 0)
        all-prior-days (iterate prior-day first-day-of-month)
        prior-sunday (first (drop-while #(not= 0 (.getDay %)) all-prior-days))
        all-weeks-from-prior-sunday (partition 7 (iterate next-day prior-sunday))
        contains-this-month? (fn [week] (some #(= zero-based-month (.getMonth %)) week))
        all-weeks-from-starting-sunday (drop-while (comp not contains-this-month?) all-weeks-from-prior-sunday)
        ]
    (take-while contains-this-month? all-weeks-from-starting-sunday)
    )
  )


;; Pure calendar operations
(defn displayed-date [calendar] (str (:month calendar) "/" (:day calendar) "/" (:year calendar)))

(defn set-date [new-dt calendar]
  (let [is-js-date? (= js/Date (type new-dt))
        month (if is-js-date? (+ 1 (.getMonth new-dt)) (:month new-dt))
        day (if is-js-date? (.getDate new-dt) (:day new-dt))
        year (if is-js-date? (.getFullYear new-dt) (:year new-dt))
        ]
    (assoc
      (merge calendar {:month month :day day :year year})
      :weeks (weeks-of-interest month year)
      )))

(defn next-month [calendar]
  (let [this-month (:month calendar)
        next-month (if (= this-month 12) 1 (+ 1 this-month))
        this-year (:year calendar)
        year (if (= 1 next-month) (+ 1 this-year) this-year)
        ]
    (set-date {:month next-month :day 1 :year year} calendar)
    )
  )

(defn prior-month [calendar]
  (let [this-month (:month calendar)
        prior-month (if (= this-month 1) 12 (- this-month 1))
        this-year (:year calendar)
        year (if (= 12 prior-month) (- this-year 1) this-year)
        ]
    (set-date {:month prior-month :day 1 :year year} calendar)
    )
  )

(defn toggle-calendar-overlay [calendar] (update calendar :overlay-visible? not))

(c/defscomponent Calendar
                 "A Calendar"
                 [calendar-data context]
                 (let [op (state/op-builder context)
                       overlay-visible? (:overlay-visible? calendar-data)
                       move-to-next-month (op next-month)
                       move-to-prior-month (op prior-month)
                       toggle-days (op toggle-calendar-overlay)
                       set-date-to (fn [dt] (op (partial set-date dt) :picked))
                       days-of-week ["Su" "M" "T" "W" "Th" "F" "Sa"]
                       ]
                   (d/div {:className "calendar"}
                          (d/div {:className "title"}
                                 (d/button {:className "control"
                                            :onClick   move-to-prior-month
                                            } "Previous")
                                 (d/span {:className "current"
                                          :onClick   toggle-days
                                          } (displayed-date calendar-data))
                                 (d/button {:className "control"
                                            :onClick   move-to-next-month
                                            } "Next")
                                 (d/button {:className "control"
                                            :onClick   (set-date-to (js/Date.))
                                            } "Today")
                                 )
                          (d/div {:className (if overlay-visible? "overlay" "hidden")}
                                 (for [label days-of-week]
                                   (d/span {:className "day"} label)
                                   )
                                 (for [week (:weeks calendar-data)]
                                   (d/div {:className "week"}
                                          (for [day week]
                                            (d/span {
                                                     :className "day"
                                                     :onClick   (set-date-to day)
                                                     } (.getDate day))
                                            )
                                          )
                                   )
                                 )
                          ))
                 )
