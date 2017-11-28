(ns cards.card-utils)

(defn sleep [n] #?(:clj (Thread/sleep n)))

(defn now []
  #?(:clj  (System/currentTimeMillis)
     :cljs (js/Date.)))
