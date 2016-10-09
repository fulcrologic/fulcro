(ns untangled.client.augmentation
  (:require
    [untangled.client.impl.util :as utl]
    [clojure.spec :as s]))

(defmulti defui-augmentation (fn [ctx _ _] (:augment/dispatch ctx)))

(s/def ::inject-augment
  (s/cat
    :ast utl/TRUE
    :static (s/? '#{static})
    :protocol symbol?
    :method symbol?
    :body utl/TRUE))

(defn inject-augment [& args]
  (let [{:keys [ast static protocol method body]}
        (utl/conform! ::inject-augment args)]
    ;;TODO: Check protocol & method dont already exist
    (update-in ast [:impls (str protocol)]
      #(-> %
         (assoc
           :protocol protocol)
         (cond-> static
           (assoc :static 'static))
         (assoc-in [:methods (str method)]
           {:name method
            :param-list (second body)
            :body [(last body)]})))))

(s/def ::wrap-augment
  (s/cat
    :ast utl/TRUE
    :protocol symbol?
    :method symbol?
    :wrapper fn?))

(defn wrap-augment [& args]
  (let [{:keys [ast protocol method wrapper]}
        (utl/conform! ::wrap-augment args)]
    ;;TODO: Check protocol & method already exist
    (update-in ast [:impls (str protocol) :methods (str method)]
      (fn [{:as method :keys [body param-list]}]
        (assoc method :body
          (conj (vec (butlast body))
                (wrapper param-list (last body))))))))
