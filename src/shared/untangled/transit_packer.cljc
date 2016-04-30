(ns untangled.transit-packer
  (:require [om.transit :as ot]
            [taoensso.sente.packers.transit :as st]
            [om.tempid :as tempid #?@(:cljs [:refer [TempId]])])
  #?(:clj (:import [com.cognitect.transit ReadHandler]
                   [om.tempid TempId])))


(def packer
  "A json packer for use with sente."
  #?(:clj (st/->TransitPacker :json
            {:handlers {TempId (ot/->TempIdHandler)}}
            {:handlers {"om/id" (reify
                                  ReadHandler
                                  (fromRep [_ id] (TempId. id)))}})
     :cljs (st/->TransitPacker :json
             {:handlers {TempId (ot/->TempIdHandler)}}
             {:handlers {"om/id" (fn [id] (tempid/tempid id))}})))
