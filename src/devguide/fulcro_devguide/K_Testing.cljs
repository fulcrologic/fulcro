(ns fulcro-devguide.K-Testing
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard-doc]]))

(defcard-doc
  "# Testing

  The Fulcro ecosystem has an external library for great BDD. The macros wrap clojure/cljs test, so that you may
  use *any of the features of that core library*. The specification DSL makes it much easier to read the
  tests, and also includes a number of useful features:

  - Outline rendering to a browser (client and server tests)
  - Left-to-right assertions
  - More readable output, such as data structure comparisons on failure (with diff notation as well)
  - Real-time refresh of tests on save (client and server)
  - Seeing test results in any number of browsers at once
  - Mocking of normal functions, including native javascript (but as expected: not macros or inline functions)
      - Mocking verifies call sequence and call count
      - Mocks can easily verify arguments received
      - Mocks can simulate timelines for CSP logic

  A sample test looks like this, and you can read many examples in all of the Fulcro projects:

  ```
  (specification \"some-function\"
    (assertions
      \"does a wiggly bit\"
      (some-function) => :wiggly
      \"accepts a widget to wobble\"
      (some-function :widget) => :wobble
      \"but it remains standing even in an earthquake\"
      (some-function :earthquake) => :standing))
  ```

  would render as:

  ```
  - some-function
    - does a wiggly bit
    - accepts a widget to wobble
    ...
  ```

  and would show failures inline to this outline.

  See Fulcro Spec for detailed instructions.
  ")


