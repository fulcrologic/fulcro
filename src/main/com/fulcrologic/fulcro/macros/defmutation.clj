(ns com.fulcrologic.fulcro.macros.defmutation
  (:require
    [clojure.spec.alpha :as s]
    [cljs.analyzer :as ana]
    [clojure.string :as str]
    [com.fulcrologic.fulcro.algorithms.application-helpers :as ah])
  (:import (clojure.lang IFn)))

(s/def ::handler (s/cat
                   :handler-name symbol?
                   :handler-args (fn [a] (and (vector? a) (= 1 (count a))))
                   :handler-body (s/+ (constantly true))))

(s/def ::mutation-args (s/cat
                         :sym symbol?
                         :doc (s/? string?)
                         :arglist (fn [a] (and (vector? a) (= 1 (count a))))
                         :sections (s/* (s/or :handler ::handler))))

(defn defmutation* [macro-env args]
  (let [conform!       (fn [element spec value]
                         (when-not (s/valid? spec value)
                           (throw (ana/error macro-env (str "Syntax error in " element ": " (s/explain-str spec value)))))
                         (s/conform spec value))
        {:keys [sym doc arglist sections]} (conform! "defmutation" ::mutation-args args)
        fqsym          (if (namespace sym)
                         sym
                         (symbol (name (ns-name *ns*)) (name sym)))
        handlers       (reduce (fn [acc [_ {:keys [handler-name handler-args handler-body]}]]
                                 (let [action? (str/ends-with? (str handler-name) "action")
                                       ]
                                   (into acc
                                     [(keyword (name handler-name))
                                      (if action?
                                        `(fn ~handler-name ~handler-args ~@handler-body nil)
                                        `(fn ~handler-name ~handler-args ~@handler-body))])))
                         []
                         sections)
        ks             (into #{} (filter keyword?) handlers)
        result-action? (contains? ks :result-action)
        env-symbol     'fulcro-mutation-env-symbol
        method-map     (if result-action?
                         `{~(first handlers) ~@(rest handlers)}
                         `{~(first handlers) ~@(rest handlers)
                           :result-action    (fn [env#]
                                               (when-let [default-action# (ah/app-algorithm (:app env#) :default-result-action)]
                                                 (default-action# env#)))})
        doc            (or doc "")
        multimethod    `(defmethod com.fulcrologic.fulcro.mutations/mutate '~fqsym [~env-symbol]
                          (let [~(first arglist) (-> ~env-symbol :ast :params)]
                            ~method-map))]
    (if (= fqsym sym)
      multimethod
      `(do
         ~multimethod
         (def ~(with-meta sym {:doc doc}) (com.fulcrologic.fulcro.mutations/->Mutation '~fqsym))))))

(defmacro defmutation [& args] (defmutation* &env args))
