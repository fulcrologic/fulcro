(ns fulcro-todomvc.server
  (:require
    [clojure.core.async :as async]
    [clojure.tools.namespace.repl :as tools-ns]
    [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
    [com.fulcrologic.fulcro.server.api-middleware :as fmw :refer [not-found-handler wrap-api]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [fulcro-todomvc.custom-types :as custom-types]
    [immutant.web :as web]
    [ring.middleware.content-type :refer [wrap-content-type]]
    [ring.middleware.not-modified :refer [wrap-not-modified]]
    [ring.middleware.resource :refer [wrap-resource]]
    [taoensso.timbre :as log]))

(def item-db (atom {1 {:item/id       1
                       :item/label    "Item 1"
                       :item/complete false}
                    2 {:item/id       2
                       :item/label    "Item 2"
                       :item/complete false}
                    3 {:item/id       3
                       :item/label    "Item 3"
                       :item/complete false}}))

(pc/defmutation todo-new-item [env {:keys [id list-id text]}]
  {::pc/sym    `fulcro-todomvc.api/todo-new-item
   ::pc/params [:list-id :id :text]
   ::pc/output [:item/id]}
  (log/info "New item on server")
  (let [new-id (tempid/uuid)]
    (swap! item-db assoc new-id {:item/id new-id :item/label text :item/complete false})
    {:tempids {id new-id}
     :item/id new-id}))

(pc/defmutation todo-check [env {:keys [id list-id]}]
  {::pc/sym    `fulcro-todomvc.api/todo-check
   ::pc/params [:list-id :id]
   ::pc/output []}
  (log/info "Checked item" id)
  (swap! item-db assoc-in [id :item/complete] true)
  {})

(pc/defmutation todo-uncheck [env {:keys [id list-id]}]
  {::pc/sym    `fulcro-todomvc.api/todo-uncheck
   ::pc/params [:list-id :id]
   ::pc/output []}
  (log/info "Unchecked item" id)
  (swap! item-db assoc-in [id :item/complete] false)
  {})

(pc/defmutation commit-label-change [env {:keys [id list-id text]}]
  {::pc/sym    `fulcro-todomvc.api/commit-label-change
   ::pc/params [:list-id :id :text]
   ::pc/output []}
  (log/info "Set item label text of" id "to" text)
  (swap! item-db assoc-in [id :item/label] text)
  {})

(pc/defmutation todo-delete-item [env {:keys [id list-id]}]
  {::pc/sym    `fulcro-todomvc.api/todo-delete-item
   ::pc/params [:list-id :id]
   ::pc/output []}
  (log/info "Deleted item" id)
  (swap! item-db dissoc id)
  {})

(defn- to-all-todos [db f]
  (into {}
    (map (fn [[id todo]]
           [id (f todo)]))
    db))

(pc/defmutation todo-check-all [env {:keys [list-id]}]
  {::pc/sym    `fulcro-todomvc.api/todo-check-all
   ::pc/params [:list-id]
   ::pc/output []}
  (log/info "Checked all items")
  (swap! item-db to-all-todos #(assoc % :item/complete true))
  {})

(pc/defmutation todo-uncheck-all [env {:keys [list-id]}]
  {::pc/sym    `fulcro-todomvc.api/todo-uncheck-all
   ::pc/params [:list-id]
   ::pc/output []}
  (log/info "Unchecked all items")
  (swap! item-db to-all-todos #(assoc % :item/complete false))
  {})

(pc/defmutation todo-clear-complete [env {:keys [list-id]}]
  {::pc/sym    `fulcro-todomvc.api/todo-clear-complete
   ::pc/params [:list-id]
   ::pc/output []}
  (log/info "Cleared completed items")
  (swap! item-db (fn [db] (into {} (remove #(-> % val :item/complete)) db)))
  {})

(pc/defmutation store-point [env {:keys [p]}]
  {::pc/sym    `fulcro-todomvc.api/store-point
   ::pc/params [:p]
   ::pc/output []}
  (log/spy :info "Store point" [(type p) p])
  {:p p})

;; How to go from :person/id to that person's details
(pc/defresolver list-resolver [env params]
  {::pc/input  #{:list/id}
   ::pc/output [:list/title {:list/items [:item/id]}]}
  ;; normally you'd pull the person from the db, and satisfy the listed
  ;; outputs. For demo, we just always return the same person details.
  {:list/title "The List"
   :list/items (into []
                 (sort-by :item/label (vals @item-db)))})

;; how to go from :address/id to address details.
(pc/defresolver item-resolver [env {:keys [item/id] :as params}]
  {::pc/input  #{:item/id}
   ::pc/output [:item/complete :item/label]}
  (get @item-db id))

;; define a list with our resolvers
(def my-resolvers [list-resolver item-resolver
                   todo-new-item commit-label-change todo-delete-item
                   todo-check todo-uncheck
                   todo-check-all todo-uncheck-all
                   todo-clear-complete
                   store-point])

;; setup for a given connect system
(def parser
  (p/parser
    {::p/env     {::p/reader                 [p/map-reader
                                              pc/reader2
                                              pc/open-ident-reader]
                  ::pc/mutation-join-globals [:tempids]}
     ::p/mutate  pc/mutate
     ::p/plugins [(pc/connect-plugin {::pc/register my-resolvers})
                  (p/post-process-parser-plugin p/elide-not-found)
                  p/error-handler-plugin]}))

(def middleware (-> not-found-handler
                  (wrap-api {:uri    "/api"
                             :parser (fn [query] (parser {} query))})
                  (fmw/wrap-transit-params)
                  (fmw/wrap-transit-response)
                  (wrap-resource "public")
                  wrap-content-type
                  wrap-not-modified))

(def server (atom nil))

(defn http-server []
  (let [result (web/run middleware {:host "0.0.0.0"
                                    :port 8080})]
    (custom-types/install!)
    (reset! server result)
    (fn [] (web/stop result))))

(comment

  (http-server)
  (web/stop @server)
  (tools-ns/refresh-all)

  (async/<!! (parser {} `[(fulcro-todomvc.api/todo-new-item {:id 2 :text "Hello"})]))

  @item-db

  )
