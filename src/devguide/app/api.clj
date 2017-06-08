(ns app.api
  (:require [om.next.server :as om]
            [taoensso.timbre :as timbre]))

(defmulti apimutate om/dispatch)
(defmulti api-read om/dispatch)

;; your entry point for handling mutations. Standard Om mutate handling. All plumbing is taken care of. UNLIKE Om, if you
; return :tempids from your :action, they will take effect on the client automatically without post-processing.
(defmethod apimutate :default [e k p]
  (timbre/error "Unrecognized mutation " k))

(defmethod api-read :default [{:keys [ast query] :as env} dispatch-key params]
  (timbre/error "Unrecognized query for " dispatch-key " : " query))

(defmethod api-read :something [e k p]
  {:value 66})

