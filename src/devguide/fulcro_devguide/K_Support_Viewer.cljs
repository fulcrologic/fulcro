(ns fulcro-devguide.K-Support-Viewer
  (:require [devcards.core :as dc :include-macros true :refer-macros [defcard-doc]]))

(defcard-doc
  "# Support Viewer

  Fulcro automatically tracks the sequence of steps in the UI, including what transactions ran to move your
  application from state to state. This enables you to do time travel for things like error handling, but
  also allows you to serialize the history and send it to your servers for debugging sessions against
  real user interactions!

  The viewer is already written for you, and is in the `fulcro.support-viewer` namespace. There are a few things you
  have to do in order to make it work.

  ## Sending a Support Request

  There is a built-in mutation that can do this called `fulcro.client.mutations/send-history`. It can accept anything
  as parameters, and will send the support request to `:remote`. All you have to do is run it via UI:

  ```
  (transact! this `[(m/send-history {:support-id (om/tempid)})])
  ```

  ## Storing the Support Request

  TODO...for now, see todomvc

  ## Using the Support Viewer

  TODO...for now, see todomvc

  ## Compressible Transactions

  The `compressible-transact!` function support compressing transactions that would be otherwise annoying to step through. It works
  as follows:

  1. Use `compressible-transact!` instead of `transact!`
  2. If more than one adjacent transaction is marked compressible in history then only the *last* of them is kept.

  The built-in mutations that set a value (e.g. m/set-value!) are meant to be used with user inputs and already mark their
  transactions this way. This is quite useful when you don't want to pollute (or overflow) history with keystrokes that
  are not interesting.

  ")


