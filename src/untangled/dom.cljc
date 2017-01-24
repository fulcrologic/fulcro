(ns untangled.dom
  (:require [clojure.string :as str]
            [om.next :as om]
            [untangled.client.logging :as log]
            [om.next.protocols :as omp]))

(defn unique-key
  "Get a unique string-based key. Never returns the same value."
  []
  (let [s #?(:clj  (System/currentTimeMillis)
             :cljs (system-time))]
    (str s)))

(defn force-render
  "Re-render components. If only a reconciler is supplied then it forces a full DOM re-render by updating the :ui/react-key
  in app state and forcing Om to re-render the entire DOM, which only works properly if you query
  for :ui/react-key in your Root render component and add that as the react :key to your top-level element.

  If you supply an additional vector of keywords and idents then it will ask Om to rerender only those components that mention
  those things in their queries."
  ([reconciler keywords]
   (omp/queue! reconciler keywords)
   (omp/schedule-render! reconciler))
  ([reconciler]
   (let [app-state (om/app-state reconciler)]
     (do
       (swap! app-state assoc :ui/react-key (unique-key))
       (om/force-root-render! reconciler)))))

(defn append-class
  "Append a CSS class. Given a component and a local state key or keys, to be passed to `om/get-state`,
  returns a function that takes the `state-value` to test, a `default-class-string`,
  and optionaol `:when-true` and `:when-false`. The values `:when-false` and `when-true`
  are appended to `default-class-string` after the test against `state-value`.

  Parameters:
  `component`: The component to pass to `om/get-state`.
  `local-state-key`: The key or keys to pass to `om/get-state`."
  [component local-state-key]
  (fn [state-key default-class-string & {:keys [when-true when-false]
                                         :or   {when-true "active" when-false ""}}]
    (let [append-string (if (= state-key (om/get-state component local-state-key))
                          when-true
                          when-false)]
      (str default-class-string " " append-string))))

(defn toggle-class
  "Adds the 'visible' CSS class and removes the 'hidden' class to the pre-supplied class string based on the truthiness
  of the value in data at key.

  Parameters:
  `data`: A map containing the component's state.
  `key`: A key within `data`.
  `always-classes`: A string that has the CSS classes to always return in the returned string.

  Optional named parameters:

  `:when-true v` : This string to add when the key's value is true. Defaults to \"active\".
  `:when-false v` : The string to add when the key's value is false. Defaults to \"\".
  "
  [data key always-classes & {:keys [when-true when-false]
                              :or   {when-true "active" when-false ""}}]
  (if (get data key)
    (str/join " " [always-classes when-true])
    (str/join " " [always-classes when-false])))

(defn text-value
  "Returns the text value from an input change event."
  [evt]
  (try
    (.-value (.-target evt))
    (catch #?(:clj Exception :cljs js/Object) e (log/warn "Event had no target when trying to pull text"))))
