(ns fulcro.client.impl.protocols)

(defprotocol IIndexer
  (indexes [this] "Get the indexes out of the indexer")
  (index-root [this x] "Index the entire root query")
  (index-component! [this component] "Add the given active UI component to the index")
  (drop-component! [this component] "Drop the given UI component from the index")
  (ref-for [this component] "Get the ident for the given component (UNIMPLEMENTED AT PRESENT)")
  (key->components [this k] "Find all components that query for the given keyword or ident."))

(defprotocol IReconciler
  (tick! [this] "Cause the current basis time to advance")
  (basis-t [this])
  (add-root! [reconciler root-class target options])
  (remove-root! [reconciler target])
  (schedule-render! [reconciler] "Schedule a render if one is not already scheduled.")
  (schedule-sends! [reconciler] "Schedule a network interaction.")
  (queue! [reconciler ks] [reconciler ks remote] "Add the given ks to the given remote queue of things to be re-rendered. If remote is nil, add to the local UI queue")
  (queue-sends! [reconciler sends] "Add the given map of remote->query sends to the queue of things to be sent")
  (reindex! [reconciler] "Reindex the active UI")
  (reconcile! [reconciler] [reconciler remote] "Bring the UI up-to-date with respect to data changes in the given queue. If remote is nil, use local UI queue.")
  (send! [reconciler] "Send the actual queued network traffic to remotes"))

#?(:clj
   (defprotocol IReactDOMElement
     (^String -render-to-string [this react-id ^StringBuilder sb] "renders a DOM node to string.")))

#?(:clj
   (defprotocol IReactComponent
     (-render [this] "must return a valid ReactDOMElement.")))

#?(:clj
   (defprotocol IReactLifecycle
     (shouldComponentUpdate [this next-props next-state])
     (initLocalState [this])
     (componentWillReceiveProps [this next-props])
     (componentWillUpdate [this next-props next-state])
     (componentDidUpdate [this prev-props prev-state])
     (componentWillMount [this])
     (componentDidMount [this])
     (componentWillUnmount [this])
     (render [this])))

(defprotocol ITxIntercept
  (tx-intercept [c tx]
    "An optional protocol that component may implement to intercept child
     transactions."))

