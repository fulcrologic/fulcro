(ns fulcro-tutorial.K-Support-Viewer
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

  Basically you just have to write something to handle the `fulcro.client.mutations/send-history` mutation, save the
  data, and return a tempid remapping (optional, since the client itself won't care):

  ```
  (server/defmutation fulcro.client.mutations/send-history [params]
    (action [env]
       ...save the history from params...
       ...send an email to a developer with the saved id?...))
  ```

  See Fulcro TodoMVC for an example.

  ## Using the Support Viewer

  The support viewer is a simple UI that is pre-programmed in the `fulcro.support-viewer` namespace. When started, it
  will issue a load in order to obtain the history you saved in the prior step. It will then run the application
  (which you also have to point to) with that history in a DVR-style playback.

  See Fulcro TodoMVC for an example.

  You can see how simple the
  [client setup](https://github.com/fulcrologic/fulcro-todomvc/blob/master/src/main/fulcro_todomvc/support_viewer.cljs) is here, and
  look at the defmutation for the `send-history` and query for `:support-request` in this file:
  [Server API](https://github.com/fulcrologic/fulcro-todomvc/blob/master/src/main/fulcro_todomvc/api.clj)

  ## Compressible Transactions

  The `compressible-transact!` function support compressing transactions that would be otherwise annoying to step through. It works
  as follows:

  1. Use `compressible-transact!` instead of `transact!`
  2. If more than one adjacent transaction is marked compressible in history then only the *last* of them is kept.

  The built-in mutations that set a value (e.g. m/set-value!) are meant to be used with user inputs and already mark their
  transactions this way. This is quite useful when you don't want to pollute (or overflow) history with keystrokes that
  are not interesting.

  ")


