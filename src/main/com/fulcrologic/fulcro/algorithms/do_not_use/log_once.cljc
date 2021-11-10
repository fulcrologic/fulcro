(ns com.fulcrologic.fulcro.algorithms.do-not-use.log-once
  "Some misc. logging functions. These are primarily meant for internal use, and are subject to
  relocation and removal in the future.

  You have been warned. Changes to this ns (or its complete removal)
  will not be considered breaking changes to the library, and no mention of said changes
  will even appear in the changelog."
  (:require
    [taoensso.encore :as encore]
    [taoensso.timbre :as log]))

; TODO Should we limit the size and use a LRU cache or st.? Hopefully not really an issue in a webapp?
; Or implement a DIY TTL so we repeat the error sometimes? (=> {<args> (js/Date.now)})

;(encore/memoize 60000 (constantly nil))

(defn error-once! [& args]
  (log/log! :error :p args #_{:?line (fline &form)}))