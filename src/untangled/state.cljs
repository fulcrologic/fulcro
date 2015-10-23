(ns untangled.state
  (:require [untangled.events :as evt]
            cljs.pprint
            [clojure.set :refer [union]]
            [untangled.logging :as logging]
            ))

(defn- find-first [pred coll] (first (filter pred coll)))

(defn checked-index [items index id-keyword value]
  (let [index-valid? (> (count items) index)
        proposed-item (if index-valid? (get items index) nil)
        ]
    (cond (and proposed-item
               (= value (get proposed-item id-keyword))) index
          :otherwise (->> (map-indexed vector items) (find-first #(= value (id-keyword (second %)))) (first))
          )
    )
  )

(defn resolve-data-path [state path-seq]
  (reduce (fn [real-path path-ele]
            (if (sequential? path-ele)
              (do
                (if (not= 4 (count path-ele))
                  (logging/log "ERROR: VECTOR BASED DATA ACCESS MUST HAVE A 4-TUPLE KEY")
                  (let [vector-key (first path-ele)
                        state-vector (get-in state (conj real-path vector-key))
                        lookup-function (second path-ele)
                        target-value (nth path-ele 2)
                        proposed-index (nth path-ele 3)
                        index (checked-index state-vector proposed-index lookup-function target-value)
                        ]
                    (if index
                      (conj real-path vector-key index)
                      (do
                        (logging/log "ERROR: NO ITEM FOUND AT DATA PATH")
                        (cljs.pprint/pprint path-seq)
                        real-path
                        )
                      )
                    )))
              (conj real-path path-ele)
              )
            )
          [] path-seq))

(defn event-reason [evt]
  (let [e (some-> evt (.-nativeEvent))]
    (if (instance? js/Event e)
      (let [typ (.-type e)]
        (cond-> {:kind :browser-event :type typ}
                (= "input" typ) (merge {
                                        :react-id    (some-> (.-target e) (.-attributes) (.getNamedItem "data-reactid") (.-value))
                                        :input-value (some-> (.-target e) (.-value))
                                        })
                (= "click" typ) (merge {
                                        :x            (.-x e)
                                        :y            (.-y e)
                                        :client-x     (.-clientX e)
                                        :client-y     (.-clientY e)
                                        :screen-x     (.-screenX e)
                                        :screen-y     (.-screenY e)
                                        :alt          (.-altKey e)
                                        :ctrl         (.-ctrlKey e)
                                        :meta         (.-metaKey e)
                                        :shift        (.-shiftKey e)
                                        :mouse-button (.-button e)
                                        })))
      nil)))






