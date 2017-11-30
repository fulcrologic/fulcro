(ns fulcro-devguide.M15-Routing-UI
  (:require [fulcro.client.routing :as r :refer-macros [defrouter]]
            [fulcro.client.dom :as dom]
            [devcards.core :as dc :refer-macros [defcard defcard-doc]]
            [fulcro.client.core :as fc]
            [fulcro.client.cards :refer [defcard-fulcro]]
            [fulcro.client.data-fetch :as df]
            [fulcro.client.primitives :as prim :refer [defui defsc InitialAppState initial-state]]
            [fulcro.client.mutations :as m]))

(prim/defui ^:once Main
  static prim/InitialAppState
  (initial-state [clz params] {:page :main :label "MAIN"})
  static prim/IQuery
  (query [this] [:page :label])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (dom/div #js {:style #js {:backgroundColor "red"}}
        label))))

(prim/defui ^:once Login
  static prim/InitialAppState
  (initial-state [clz params] {:page :login :label "LOGIN"})
  static prim/IQuery
  (query [this] [:page :label])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (dom/div #js {:style #js {:backgroundColor "green"}}
        label))))

(prim/defui ^:once NewUser
  static prim/InitialAppState
  (initial-state [clz params] {:page :new-user :label "New User"})
  static prim/IQuery
  (query [this] [:page :label])
  Object
  (render [this]
    (let [{:keys [label]} (prim/props this)]
      (dom/div #js {:style #js {:backgroundColor "skyblue"}}
        label))))

(prim/defui ^:once StatusReport
  static prim/InitialAppState
  (initial-state [clz params] {:id :a :page :status-report})
  static prim/IQuery
  (query [this] [:id :page :label])
  Object
  (render [this] (let [{:keys [id]} (prim/props this)]
                   (dom/div #js {:style #js {:backgroundColor "yellow"}}
                     (dom/div nil (str "Status " id))))))

(prim/defui ^:once GraphingReport
  static prim/InitialAppState
  (initial-state [clz params] {:id :a :page :graphing-report})
  static prim/IQuery
  (query [this] [:id :page :label])                         ; make sure you query for everything need by the router's ident function!
  Object
  (render [this] (let [{:keys [id]} (prim/props this)]
                   (dom/div #js {:style #js {:backgroundColor "orange"}}
                     (dom/div nil (str "Graph " id))))))

(defrouter ReportRouter :report-router
  ; This router expects numerous possible status and graph reports. The :id in the props of the report will determine
  ; which specific data set is used for the screen (though the UI of the screen will be either StatusReport or GraphingReport
  ; IMPORTANT: Make sure your components (e.g. StatusReport) query for what ident needs (i.e. in this example
  ; :page and :id at a minimum)
  (ident [this props] [(:page props) (:id props)])
  :status-report StatusReport
  :graphing-report GraphingReport)

(def ui-report-router (prim/factory ReportRouter))

; BIG GOTCHA: Make sure you query for the prop (in this case :page) that the union needs in order to decide. It won't pull it itself!
(prim/defui ^:once ReportsMain
  static prim/InitialAppState
  ; nest the router under any arbitrary key, just be consistent in your query and props extraction.
  (initial-state [clz params] {:page :report :report-router (prim/get-initial-state ReportRouter {})})
  static prim/IQuery
  (query [this] [:page {:report-router (prim/get-query ReportRouter)}])
  Object
  (render [this]
    (dom/div #js {:style #js {:backgroundColor "grey"}}
      ; Screen-specific content to be shown "around" or "above" the subscreen
      "REPORT MAIN SCREEN"
      ; Render the sub-router. You can also def a factory for the router (e.g. ui-report-router)
      (ui-report-router (:report-router (prim/props this))))))


(defrouter TopRouter :top-router
  (ident [this props] [(:page props) :top])
  :main Main
  :login Login
  :new-user NewUser
  :report ReportsMain)

(def ui-top (prim/factory TopRouter))

(def routing-tree
  "A map of route handling instructions. The top key is the handler name of the route which can be
  thought of as the terminal leaf in the UI graph of the screen that should be \"foremost\".

  The value is a vector of routing-instructions to tell the UI routers which ident
  of the route that should be made visible.

  A value in this ident using the `param` namespace will be replaced with the incoming route parameter
  (without the namespace). E.g. the incoming route-param :report-id will replace :param/report-id"
  (r/routing-tree
    (r/make-route :main [(r/router-instruction :top-router [:main :top])])
    (r/make-route :login [(r/router-instruction :top-router [:login :top])])
    (r/make-route :new-user [(r/router-instruction :top-router [:new-user :top])])
    (r/make-route :graph [(r/router-instruction :top-router [:report :top])
                          (r/router-instruction :report-router [:graphing-report :param/report-id])])
    (r/make-route :status [(r/router-instruction :top-router [:report :top])
                           (r/router-instruction :report-router [:status-report :param/report-id])])))

(prim/defui ^:once Root
  static prim/InitialAppState
  ; r/routing-tree-key implies the alias of fulcro.client.routing as r.
  (initial-state [clz params] (merge routing-tree
                                {:top-router (prim/get-initial-state TopRouter {})}))
  static prim/IQuery
  (query [this] [:ui/react-key {:top-router (prim/get-query TopRouter)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key top-router]} (prim/props this)]
      (dom/div #js {:key react-key}
        ; Sample nav mutations
        (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :main})])} "Main") " | "
        (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :new-user})])} "New User") " | "
        (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :login})])} "Login") " | "
        (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :status :route-params {:report-id :a}})])} "Status A") " | "
        (dom/a #js {:onClick #(prim/transact! this `[(r/route-to {:handler :graph :route-params {:report-id :a}})])} "Graph A")
        (ui-top top-router)))))

(defcard-fulcro router-demo
  "# Router Demo

  Background colors are used to show where the screens shown are different, and possibly nested."
  Root
  {}
  {:inspect-data true})

(defcard-doc
  "# Defining Routes

  Defining routes requires just a few steps:

  - Designate the idents of all screens that will be controlled by routers. The first element of the idents MUST be
  unique for a given router, and will identify which screen to render. The second element can be a literal value (keyword or number),
  or a keyword in the namespace `param`. In the latter case, `route-params` will be used during UI route updates
  to set that member to a value derived from the UI interactions.

  - Decide on a flat or tree structure. If you use only a single router, then all of your screens will be set up
  such that only one can be on the screen at a time. If you want to nest routers so that you can control subscreens
  within a given screen you'll need to define that.

  - Give each of the routers in your design an ID. For example :top-router and :report-router, along with an ident
  function that can extract the proper ident from the components it will route to.
  "
  (dc/mkdn-pprint-source Main)
  (dc/mkdn-pprint-source TopRouter)
  "

  in the example above, the ident function of `TopRouter` will be used against the screens (e.g. `Main`) to produce idents
  like `[:main :top]`

  - Give each routable screen in your tree a handler name. For example, if screen A will show a subscreen B or C, then
  B and C will need app-wide unique names. If screen A were also (at times) a leaf (not showing B or C), it should also have
   a handler name.

```
                (top-router w/id :top-router)
                   ----------------------
                  /     /     |          \\
             :main  :login  :new-user    (report router w/id :report-router)
                                               |
                                (reports shared content screen)
                                               |
                                              / \\
                                        :status  :graph
```

  - Define your routing tree. This is a data structure that gives instructions to one-or-more routers that are necessary to
   place the screen from (4) front-and-center. For example, you need to tell the top router and report router in the above
   example to change what they are showing in order to get a `:status` or `:graph` on the screen.
  "
  (dc/mkdn-pprint-source routing-tree)
  "

  - Compose your top-level router into your root, and place the routing tree into the top-level app state (e.g.
  using InitialAppState). The routing tree is considered singleton data and is not needed in the query.
  "
  (dc/mkdn-pprint-source Root)
  "
  - You may place nested routers wherever you like, though they cannot be directly nested in other routers, since
  the parent router needs to use an ident function that is different from the ident of the routers. This should not
  be a problem since the only reason to nest routers is to show some portion of the parent screen while routing to
  a sub-screen. For example, a reports sub-screen can nest using something like this:
  "
  (dc/mkdn-pprint-source ReportRouter)
  (dc/mkdn-pprint-source ReportsMain)
  "
  # Using the Routing Tree

  The routing library includes a Fulcro mutation for placing a specific screen front-and-center. It is namespaced
  to the fulcro.client.routing namespace, and takes and :handler and optional :route-params argument:

  ```
  ; assumes you've aliased fulcro.client.routing to r
  (prim/transact! `[(r/route-to {:handler :main})])
  ```

  ## Combining Routing with Data Management

  Of course you can compose this with other mutations into a single transaction. This is common when you're trying
  to switch to a screen whose data might not yet exist:

  ```
  (prim/transact! `[(app/ensure-report-loaded {:report-id :a}) (r/route-to {:graph :a})])
  ```

  here we're assuming that `ensure-report-loaded` is a mutation that ensures that there is at least placeholder data in
  place (or the UI rendering might look a bit odd or otherwise fail from lack of data). It may also do things like trigger background
  loads that will fufill the graph's needs, something like this:

  ```
  (defmethod m/mutate 'app/ensure-report-loaded [{:keys [state] :as env} k {:keys [report-id]}]
    (let [when-loaded (get-in @state [:reports/by-id report-id :load-time-ms] 0)
          is-missing? (= 0 when-loaded)
          now-ms (.getTime (js/Date.))
          age-ms (- now-ms when-loaded)
          should-be-loaded? (or (too-old? age-ms) is-missing?)]
      ; if missing, put placeholder
      ; if too old, add remote load to Fulcro queue (see data-fetch for remote-load and load-action)
      {:remote (when should-be-loaded? (df/remote-load env))
       :action (fn []
                 (when is-missing? (swap! state add-report-placeholder report-id))
                 (when should-be-loaded? (df/load-action env [:reports/by-id report-id] StatusReport)))}))
  ```

  Additional mutations might do things like garbage collect old data that is not in the view. You may also need to
  trigger renders of things like your main screen with follow-on reads (e.g. of a keyword on the root
  component of your UI). Of course, combining such things into functions adds a nice touch:

  ```
  (defn show-report!
    [component report-id]
    (prim/transact! component `[(app/clear-old-reports)
                              (app/ensure-report-loaded {:report-id ~report-id})
                              (r/route-to {:graph ~report-id})
                              :top-level-key]))
  ```

  which can then be used more cleanly in the UI:

  ```
  (dom/a #js {:onClick #(show-report! this :a)} \"Report A\")
  ```

  ## Mutations with Routing

  In some cases you will find it most convenient to do your routing within a mutation itself. This will let you
  check state, trigger loads, etc. If you trigger loads, then you can also easily defer the routing until the
  load completes. Of course, in that case you may want to do something in the state to cause your UI to indicate
  the routing is in progress.

  There is nothing special about this technique. There are several functions in the `routing` namespace
  that can be used easily within your own mutations:

  - `update-routing-links` - For standard union-based `defrouter`: Takes the state map and a route match (i.e. handler/params) and returns a new state map with the routes updated.
  - `route-to-impl!` - For all kinds of routers (including dynamic): Takes the mutation `env` and a bidi-style match. Works with dynamic routes. Does swaps against app state.
  - `set-route` - Changes the current route on a specific `defrouter` instance. Takes a state map, router ID, and target ident. Used if not using routing trees or dynamic routers.

  # HTML5 Routing

  Hooking HTML5 or hash-based routing up to this is relatively simple using, for example, `pushy` and `bidi`.

  We do not provide direct support for this, since your application will need to make a number of decisions that
  really are local to the specific app:

  - How to map URIs to your leaf screens. If you use bidi then `bidi-match` will return exactly what you need from
  a URI route match (e.g. `{:handler :x :route-params {:p v}}`).

  - How to grab the URI bits you need. For example, `pushy` lets you hook up to HTML5 history events.

  - If a routing decision should be deferred/reversed? E.g. navigation should be denied until a form is saved.

  - How you want to update the URI on routing. You can define your own additional mutation to do this (e.g. via `pushy/set-token!`)
  and possibly compose it into a new mutations with `route-to`. The function `r/update-routing-links` can be used for
  such a composition:

  ```
  ; in some defmethod m/mutate
  (swap! state (fn [m]
                  (-> m
                      (r/update-routing-links { :handler :h :route-params p })
                      (app/your-state-updates)))
  (pushy/set-token! your-uri-interpretation-of-h)
  ```
  ")
