(ns untangled.events)

;; Support for inter-component communication...

(defn trigger 
  "Trigger a custom event on the parent of the current component. Parent components can capture an event from a 
  child component by including a map as the final argument of the call to the child's render function. For example,
  to capture a `:picked` event from a Calendar component:
  
       (cal/Calendar :start-date context { :picked (fn [] ...) })
  
  Then, in the definition of Calendar itself:
  
       (d/button { :onClick (fn [] (trigger context :picked)) } \"Today\")
  
  Note that in most cases you do not need this function, as the op-builder can generate events as it updates the 
  app state:
  
       (op thing-to-do :picked) 
  "
  [context events]
  (doseq [evt events
          listener-map (:event-listeners context)]
    (if-let [listener (get listener-map evt)] (listener))
    )
  )
