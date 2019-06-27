(ns com.fulcrologic.fulcro.inspect.diff
  "Internal algorithms for sending db diffs to Inspect tool."
  (:require [clojure.spec.alpha :as s]))

(defn updates [a b]
  (reduce
    (fn [adds [k v]]
      (let [va (get a k :fulcro.inspect.lib.diff/unset)]
        (if (= v va)
          adds
          (if (and (map? v) (map? va))
            (assoc adds k (updates va v))
            (assoc adds k v)))))
    {}
    b))

(defn removals [a b]
  (reduce
    (fn [rems [k v]]
      (if-let [[_ vb] (find b k)]
        (if (and (map? v) (map? vb) (not= v vb))
          (let [childs (removals v vb)]
            (if (seq childs)
              (conj rems {k childs})
              rems))
          rems)
        (conj rems (cond-> k (map? k) (assoc :fulcro.inspect.lib.diff/key? true)))))
    []
    a))

(defn diff [a b]
  {:fulcro.inspect.lib.diff/updates  (updates a b)
   :fulcro.inspect.lib.diff/removals (removals a b)})

(defn deep-merge [x y]
  (if (and (map? x) (map? y))
    (merge-with deep-merge x y)
    y))

(defn patch-updates [x {:fulcro.inspect.lib.diff/keys [updates]}]
  (merge-with deep-merge x updates))

(defn patch-removals [x {:fulcro.inspect.lib.diff/keys [removals]}]
  (reduce
    (fn [final rem]
      (cond
        (:fulcro.inspect.lib.diff/key? rem)
        (dissoc final (dissoc rem :fulcro.inspect.lib.diff/key?))

        (map? rem)
        (let [[k v] (first rem)]
          (update final k #(patch-removals % {:fulcro.inspect.lib.diff/removals v})))

        :else
        (dissoc final rem)))
    x
    removals))

(defn patch [x diff]
  (-> x
      (patch-updates diff)
      (patch-removals diff)))
