(ns untangled.events
  (:require [untangled.logging :as logging]))

(defn trigger
  "Trigger custom event(s) on the parent of the current component. Parent components can capture an event from a 
  child component by including a map as the final argument of the call to the child's render function. For example,
  to capture a `:picked` event from a Calendar component:
  
       (cal/Calendar :start-date context { :picked (fn [] ...) })
  
  Then, in the definition of Calendar itself:
  
       (d/button { :onClick (fn [] (trigger context :picked)) } \"Today\")
  
  Note that in most cases you do not need this function, as a context-operation can generate events as it updates the 
  app state:
  
       (let [op (context-operator context do-thing :trigger :picked)]
         (d/button { :onClick op } \"Click me to generate :picked\"))
  "
  [context events]
  (doseq [evt (flatten (list events))
          listener-map (:event-listeners context)]
    (if-let [listener (get listener-map evt)]
      (if (fn? listener)
        (listener evt)
        (logging/log "ERROR: TRIGGERED EVENT HANDLER MUST BE A FUNCTION")))
    )
  )
