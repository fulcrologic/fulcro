(ns untangled.websockets.transit-packer
  (:require [om.transit :as ot]
            [taoensso.sente.packers.transit :as st]
            [om.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  #?(:clj (:import [com.cognitect.transit ReadHandler]
                   [om.tempid TempId])))


(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  #?(:clj (st/->TransitPacker :json
                              {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                                           write (merge write))}
                              {:handlers (cond-> {"om/id" (reify
                                                            ReadHandler
                                                            (fromRep [_ id] (TempId. id)))}
                                           read (merge read))})
     :cljs (st/->TransitPacker :json
                               {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                                            write (merge write))}
                               {:handlers (cond-> {"om/id" (fn [id] (tempid/tempid id))}
                                            read (merge read))})))
