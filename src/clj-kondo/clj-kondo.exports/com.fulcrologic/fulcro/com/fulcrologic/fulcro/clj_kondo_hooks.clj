(ns com.fulcrologic.fulcro.clj-kondo-hooks
  (:require [clj-kondo.hooks-api :as api]))

(defn defmutation
  [{:keys [node]}]
  (let [args          (rest (:children node))
        mutation-name (first args)
        ?docstring    (when (string? (api/sexpr (second args)))
                        (second args))
        args          (if ?docstring
                        (nnext args)
                        (next args))
        params        (first args)
        handlers      (rest args)
        handler-syms  (map (comp first :children) handlers)
        bogus-usage   (api/vector-node (vec handler-syms))
        letfn-node    (api/list-node
                       (list
                        (api/token-node 'letfn)
                        (api/vector-node (vec handlers))
                        bogus-usage))
        new-node      (api/list-node
                       (list
                        (api/token-node 'defn)
                        mutation-name
                        params
                        letfn-node))]
    (doseq [handler handlers]
      (let [hname (some-> handler :children first api/sexpr str)
            argv  (some-> handler :children second)]
        (when-not (= 1 (count (api/sexpr argv)))
          (api/reg-finding! (merge
                             (meta argv)
                             {:message (format "defmutation handler '%s' should be a fn of 1 arg" hname)
                              :type    :clj-kondo.fulcro.defmutation/handler-arity})))))
    {:node new-node}))

(defn >defn
  [{:keys [node]}]
  (let [args       (rest (:children node))
        fn-name    (first args)
        ?docstring (when (string? (api/sexpr (second args)))
                     (second args))
        args       (if ?docstring
                     (nnext args)
                     (next args))
        argv       (first args)
        gspec      (second args)
        body       (nnext args)
        new-node   (api/list-node
                    (list*
                     (api/token-node 'defn)
                     fn-name
                     argv
                     gspec
                     body))]
    (when (not= (count (api/sexpr argv))
                (count (take-while #(not= '=> %) (api/sexpr gspec))))
      (api/reg-finding! (merge (meta gspec)
                               {:message "Guardrail spec does not match function signature"
                                :type    :clj-kondo.fulcro.>defn/signature-mismatch})))
    {:node new-node}))