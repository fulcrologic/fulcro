(ns untangled.events
  (:require [untangled.logging :as logging]))

(defn trigger
  "Trigger custom event on the parent of the current component. Parent components can capture an event from a
  child component by including a map as the final argument of the call to the child's render function. For example,
  to capture a `:picked` event from a Calendar component:
  
       (cal/Calendar :start-date context { :picked (fn [] ...) })
  
  Then, in the definition of Calendar itself:
  
       (d/button { :onClick (fn [] (trigger context :picked)) } \"Today\")
  
  Note that in most cases you do not need this function, as a context-operation can generate events as it updates the 
  app state:
  
       (let [op (context-operator context do-thing :trigger :picked)]
         (d/button { :onClick op } \"Click me to generate :picked\"))
         
  You may indicate that the event should bubble to all parents by including `:bubble true` as a named parameter.
  "
  [context event & {:keys [bubble data] :or {bubble false data nil}}]
  (doseq [listener-map (if bubble (:untangled.state/event-listeners context) (list (last (:untangled.state/event-listeners context))))]
    (if-let [listener (get listener-map event)]
      (if (fn? listener)
        (listener event data)
        (logging/log "ERROR: TRIGGERED EVENT HANDLER MUST BE A FUNCTION")))
    )
  )

(defn enter-key?
  "Return true if a DOM event was the enter key."
  [evt]
  (= 13 (.-keyCode evt)))

(defn escape-key?
  "Return true if a DOM event was the escape key."
  [evt]
  (= 27 (.-keyCode evt)))

(defn text-value
  "Returns the text value from an input change event."
  [evt]
  (try
    (.-value (.-target evt))
    (catch js/Object e (logging/warn "Event had no target when trying to pull text"))
    )
  )
