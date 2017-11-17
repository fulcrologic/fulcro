(ns fulcro.websockets.transit-packer
  (:require [fulcro.transit :as ot]
            [taoensso.sente.packers.transit :as st]
            [fulcro.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  #?(:clj (:import [com.cognitect.transit ReadHandler]
                   [fulcro.tempid TempId])))


(defn make-packer
  "Returns a json packer for use with sente."
  [{:keys [read write]}]
  #?(:clj (st/->TransitPacker :json
                              {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                                           write (merge write))}
                              {:handlers (cond-> {"fulcro/tempid" (reify
                                                            ReadHandler
                                                            (fromRep [_ id] (TempId. id)))}
                                           read (merge read))})
     :cljs (st/->TransitPacker :json
                               {:handlers (cond-> {TempId (ot/->TempIdHandler)}
                                            write (merge write))}
                               {:handlers (cond-> {"fulcro/tempid" (fn [id] (tempid/tempid id))}
                                            read (merge read))})))
