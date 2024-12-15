(ns com.fulcrologic.fulcro.inspect.tools)

(defn- rt [app] (:com.fulcrologic.fulcro.application/runtime-atom app))
(defn registered-tools [app] (get @(rt app) ::tools))
(defn register-tool!
  "Add a tool to Fulcro.

   `tool` is a `(fn [app event])`, where the event is a map that describes some internal operation
   of interest. The application layer or Fulcro itself can send such events, which are meant for external
   tooling such as Fulcro Inspect. "
  [app tool] (swap! (rt app) update ::tools (fnil conj []) tool))

(defn notify!
  "Notify all registered tools that some event of interest has occurred. These notifications are meant for tools
   like Fulcro Inspect, but your application layer or libraries can send additional events that can be consumed by
   any tooling you choose to define.

   event-name can be a string/keyword/symbol. It will be associated onto the event you receive as `:type`. The
   data MUST be a map, and this function will also automatically add the Fulcro application's UUID
   under the key :com.fulcrologic.fulcro.application/id and the application's description as
   :com.fulcrologic.fulcro.application/label"
  [app event-name data]
  (assert (map? data) "data is a map")
  (let [id    (:com.fulcrologic.fulcro.application/id app)
        event (assoc data
                :type event-name
                :com.fulcrologic.fulcro.application/id id
                :com.fulcrologic.fulcro.application/label (or (:com.fulcrologic.fulcro.application/label app) (str id)))]
    (doseq [t (registered-tools app)]
      (t app event))))
