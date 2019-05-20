(ns com.fulcrologic.fulcro.macros.defmutation
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.fulcro.algorithms.helpers :as util :refer [join-key join-value join?]]
    [cljs.analyzer :as ana]
    [clojure.string :as str])
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

(comment
  (s/conform ::mutation-args '(user/boo [params]
                                (action [env] (swap! state))))

  )
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
                                 (let [action? (str/ends-with? (str handler-name) "action")]
                                   (into acc
                                     (if action?
                                       [(keyword (name handler-name)) `(fn ~handler-name ~handler-args
                                                                         ~@handler-body
                                                                         nil)]
                                       [(keyword (name handler-name)) `(fn ~handler-name ~handler-args ~@handler-body)]))))
                         []
                         sections)
        ks             (into #{} (filter keyword?) handlers)
        result-action? (contains? ks :result-action)
        env-symbol     'fulcro-mutation-env-symbol
        method-map     (if result-action?
                         `{~(first handlers) ~@(rest handlers)}
                         `{~(first handlers) ~@(rest handlers)
                           :result-action    (fn [~'env] (com.fulcrologic.fulcro.mutations/default-result-action ~'env))})
        doc            (or doc "")
        multimethod    `(defmethod com.fulcrologic.fulcro.mutations/mutate '~fqsym [~env-symbol]
                          (let [~(first arglist) (-> ~env-symbol :ast :params)]
                            ~method-map))]
    (if (= fqsym sym)
      multimethod
      `(def ~(with-meta sym {:doc doc})
         (do
           ~multimethod
           (com.fulcrologic.fulcro.mutations/->Mutation ~fqsym))))))


