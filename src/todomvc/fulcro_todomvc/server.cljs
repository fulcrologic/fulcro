(ns fulcro-todomvc.server
  (:require
    [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]
    [clojure.core.async :as async]
    [com.fulcrologic.fulcro.algorithms.misc :as util]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]
    [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pretend server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  (let [new-id (util/uuid)]
    (swap! item-db assoc new-id {:item/id new-id :item/label text :item/complete false})
    {:tempids {id new-id}
     :item/id new-id}))

;; How to go from :person/id to that person's details
(pc/defresolver list-resolver [env params]
  {::pc/input  #{:list/id}
   ::pc/output [:list/title {:list/items [:item/id]}]}
  ;; normally you'd pull the person from the db, and satisfy the listed
  ;; outputs. For demo, we just always return the same person details.
  {:list/title "The List"
   :list/items [{:item/id 1} {:item/id 2} {:item/id 3}]})

;; how to go from :address/id to address details.
(pc/defresolver item-resolver [env {:keys [item/id] :as params}]
  {::pc/input  #{:item/id}
   ::pc/output [:item/complete :item/label]}
  (get @item-db id))

;; define a list with our resolvers
(def my-resolvers [list-resolver item-resolver todo-new-item])

;; setup for a given connect system
(def parser
  (p/parallel-parser
    {::p/env     {::p/reader [p/map-reader
                              pc/parallel-reader
                              pc/open-ident-reader
                              p/env-placeholder-reader]}
     ::p/mutate  pc/mutate-async
     ::p/plugins [(pc/connect-plugin {::pc/register my-resolvers})
                  (p/post-process-parser-plugin p/elide-not-found)
                  p/error-handler-plugin]}))


