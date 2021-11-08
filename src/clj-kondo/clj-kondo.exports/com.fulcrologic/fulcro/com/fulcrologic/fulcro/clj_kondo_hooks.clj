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

(def =>? #{'=> :ret})
(def |? #{'| :st})

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
    ;; gspec: [arg-specs* (| arg-preds+)? => ret-spec (| fn-preds+)? (<- generator-fn)?]
    (if (not= 1 (count (filter =>? (api/sexpr gspec))))
      (api/reg-finding! (merge (meta gspec)
                          {:message (str "Gspec requires exactly one `=>` or `:ret`")
                           :type    :clj-kondo.fulcro.>defn/invalid-gspec}))
      (let [p (partition-by (comp not =>? api/sexpr) (:children gspec))
            [arg [=>] [ret-spec & _output]] (if (-> p ffirst api/sexpr =>?)
                                              (cons [] p) ; arg-specs might be empty
                                              p)
            [arg-specs [| & arg-preds]] (split-with (comp not |? api/sexpr) arg)]

        (when-not ret-spec
          (println =>)
          (api/reg-finding! (merge (meta =>)
                              {:message "Missing return spec."
                               :type    :clj-kondo.fulcro.>defn/invalid-gspec})))

        ;; (| arg-preds+)?
        (when (and | (empty? arg-preds))
          (api/reg-finding! (merge (meta |)
                              {:message "Missing argument predicates after |."
                               :type    :clj-kondo.fulcro.>defn/invalid-gspec})))


        (let [len-argv (count (remove #{'&} (api/sexpr argv))) ; [a & more] => 2 arguments
              arg-difference (- (count arg-specs) len-argv)]
          (when (not (zero? arg-difference))
            (let [too-many-specs? (pos? arg-difference)]
              (api/reg-finding! (merge
                                  (meta (if too-many-specs?
                                          (nth arg-specs (+ len-argv arg-difference -1)) ; first excess spec
                                          gspec)) ; The gspec is wrong, not the surplus argument.
                                  {:message (str "Guardrail spec does not match function signature. "
                                              "Too " (if too-many-specs? "many" "few") " specs.")
                                   :type    :clj-kondo.fulcro.>defn/invalid-gspec})))))))
    {:node new-node}))