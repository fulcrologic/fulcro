(ns untangled.client.defui-augment)

(defmulti defui-augment (fn [ctx _ _] (:augment/dispatch ctx)))
