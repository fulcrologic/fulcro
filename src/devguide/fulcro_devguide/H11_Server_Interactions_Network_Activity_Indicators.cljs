(ns fulcro-devguide.H11-Server-Interactions-Network-Activity-Indicators
  (:require-macros [cljs.test :refer [is]])
  (:require [fulcro.client.primitives :as prim :refer-macros [defui]]
            [fulcro.client.dom :as dom]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.mutations :as m]
            [fulcro.client.core :as fc]))

(defcard-doc
  "
  ### Global network activity marker

  Fulcro will automatically maintain a global network activity marker at the top level of the app state under the
  keyword `:ui/loading-data`. This key will have a `true` value when there are network requests awaiting a response from
  the server, and will have a `false` value when there are no network requests in process.

  You can access this marker from any component that composes to root by including a link in your component's query:

  ```
  (defui Item ... )
  (def ui-item (prim/factory Item {:keyfn :id}))

  (defui List
    static prim/IQuery (query [this] [:id :title {:items (prim/get-query Item)} [:ui/loading-data '_]]
    ...
    (render [this]
      (let [{:keys [title items ui/loading-data]} (prim/props this)]
        (if (and loading-data (empty? items))
          (dom/div nil \"Loading...\")
          (dom/div nil
            (dom/h1 nil title)
            (map ui-item items))))
  ```
  Because the global loading marker is at the top level of the application state, do not use the keyword as a follow-on
  read to mutations because it may unnecessarily trigger a re-render of the entire application.

")
