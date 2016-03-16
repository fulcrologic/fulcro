(ns untangled.client.impl.util)

(defn deep-merge [& xs]
  "Merges nested maps without overwriting existing keys."
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn log-app-state
  "Helper for logging the app-state. Pass in a state map and either top-level keys, data-paths (like get-in), or both."
  [app-state & keys-and-paths]
  (cljs.pprint/pprint
    (letfn [(make-path [location]
              (if (sequential? location) location [location]))
            (process-location [acc location]
              (let [path (make-path location)]
                (assoc-in acc path (get-in app-state path))))]

      (condp = (count keys-and-paths)
        0 app-state
        1 (get-in app-state (make-path (first keys-and-paths)))
        (reduce process-location {} keys-and-paths)))))
