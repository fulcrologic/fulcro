(ns com.fulcrologic.fulcro.inspect.diff
  (:require [clojure.spec.alpha :as s]))

(s/def ::updates map?)
(s/def ::removals vector?)

(defn updates [a b]
  (reduce
    (fn [adds [k v]]
      (let [va (get a k ::unset)]
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
        (conj rems (cond-> k (map? k) (assoc ::key? true)))))
    []
    a))

(defn diff [a b]
  {::updates  (updates a b)
   ::removals (removals a b)})

(defn deep-merge [x y]
  (if (and (map? x) (map? y))
    (merge-with deep-merge x y)
    y))

(defn patch-updates [x {::keys [updates]}]
  (merge-with deep-merge x updates))

(defn patch-removals [x {::keys [removals]}]
  (reduce
    (fn [final rem]
      (cond
        (::key? rem)
        (dissoc final (dissoc rem ::key?))

        (map? rem)
        (let [[k v] (first rem)]
          (update final k #(patch-removals % {::removals v})))

        :else
        (dissoc final rem)))
    x
    removals))

(defn patch [x diff]
  (-> x
      (patch-updates diff)
      (patch-removals diff)))
