(ns com.fulcrologic.fulcro.inspect.devtool-api
  "These are declarations of the remote mutations that are callable on the Fulcro Inspect Dev tool. Internal use.

   They are declared in the Fulcro project so the internals can be connected to the Inspect Devtool without having
   the dev tool be a release build requirement."
  #?(:cljs (:require-macros com.fulcrologic.fulcro.inspect.devtool-api))
  (:require
    [com.fulcrologic.fulcro.mutations :as m]))

#?(:clj
   (defmacro remote-mutations [& syms]
     (let [declarations (mapv
                          (fn [sym]
                            `(m/defmutation ~sym [_#]
                               (~'devtool-remote [_env#] true)))
                          syms)]
       `(do
          ~@declarations))))

(com.fulcrologic.fulcro.inspect.devtool-api/remote-mutations
  app-started
  db-changed
  send-started
  send-finished
  send-failed
  focus-target
  optimistic-action
  statechart-event)
