(ns quiescent-model.component)

(defn- extract-docstr
  [[docstr? & forms]]
  (if (string? docstr?)
    [docstr? forms]
    ["" (cons docstr? forms)]))

(defn- extract-opts
  ([forms] (extract-opts forms {}))
  ([[k v & forms] opts]
   (if (keyword? k)
     (extract-opts forms (assoc opts k v))
     [opts (concat [k v] forms)])))

(defmacro defscomponent
  "Creates a state-managed ReactJS component with the given name, a docstring (optional), any number of
  option->value pairs, an argument vector and any number of forms body, which will be
  used as the rendering function.

  For example:

        (defscomponent Widget
          'A Widget'
          :keyfn #(...)
          :on-render #(...)
          [data app-state-atom op-builder]
          (some-child-components)
          )

  Defs a function that takes a global ID for the component instance and the overall app state in which ID keyed
  by that data exists:

        (Widget \"my-id\" app-state-atom)

  The renderer you supply will be given two arguments: the data for the instance and an op-builder function. The
  former is your components current state in the application. The op-builder is a function that can build
  and update function for changing the widgets state.

  Your widget morphing functions will mostly be pure functions that take your instance data, and return an evolved form
  of that data:

       (let [set-data-to-3 (fn [my-widget] (assoc my-widget :data 3))]

  You can use op-builder to generate functions that will work as event handlers for you component. Simply pass
  it a functon that can take your component state and evolve it to the new state:

           (let [evt-handler (op-builder set-data-to-3)]

  Note, using partial application (or lambdas) makes it possible to create event handlers that do more dynamic things
  based on the things that might vary in some way.

       (let [set-data (fn [new-value my-widget] (assoc my-widget :data new-value))
             set-to-3 (op-builder (partial set-data 3))
            ]
            (d/button { :onClick set-to-3 } ...)

  This allows you to write you component (from a data perspective) in a pure functional form. All of the supporting
  functions that evolve the state over time are simply pure functions, and the state of the component can be modelled
  with simple persistent data structures.

  The placement of that data in the app-state is abstracted away by using unique IDs, such that your component
  construction need not be aware of the overall application state, yet that state is accessible from a central location.

  This maintains *local reasoning* about the component operation, *separate local reasoning* about the rendering,
  and no boilerplate for the connection between the two along with the benefits common to centralized state: trivial
  undo, more transparent debugging, no *hidden* state, persistent data strucutres, and referential transparency for
  the vast majority of the system.
  "
  [name & forms]
  (let [[docstr forms] (extract-docstr forms)
        [options forms] (extract-opts forms)
        [argvec & body] forms
        op-builder '(fn [op & rest]
                      (quiescent-model.state/path-operator react-args op rest)
                      )
        let-forms (interleave argvec ['react-data 'react-args op-builder])
        ]
    ; Create plumbing to an underlying quiescent component
    `(let [real-handler# (quiescent.core/component (fn [~(symbol "react-data") ~(symbol "react-args")]
                                                     (let [~@let-forms]
                                                       ~@body
                                                       )
                                                     ) ~options)]
       ;; Def the quiescent construction function so users can create instances of the component that "close over"
       ;; the plumbing that extracts the application state and passes it to the real handler.
       (def ~name ~docstr
         (fn [id# context# & event-handlers#]
           (let [new-context# (if (= "scope" (namespace id#))
                                (quiescent-model.state/new-scope context# id# event-handlers#)
                                (quiescent-model.state/in-context context# id# event-handlers#))
                 data# (quiescent-model.state/context-data new-context#)]
             (real-handler# data# new-context#)
             ))))))

