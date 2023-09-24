(ns com.fulcrologic.fulcro.cards.data-view-cards
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :refer [table td th tr thead tbody div h2 input button]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def apartments [{:apartment/id    1
                  :apartment/label "Town Place"}
                 {:apartment/id    2
                  :apartment/label "Foobar Apts"}
                 {:apartment/id    3
                  :apartment/label "Fancy Lake Place"}
                 {:apartment/id    4
                  :apartment/label "Happy homes"}
                 {:apartment/id    5
                  :apartment/label "The Place"}
                 {:apartment/id    6
                  :apartment/label "Smallville Apts"}])

(pc/defresolver apartments-resolver [_ _]
  {
   ::pc/output [{:apartments/all [:apartment/id :apartment/label]}]} ;; this would normally list just id and there'd be a seperate apartment resolver
  {:apartments/all apartments})

(def resolvers [apartments-resolver])

(def pathom-parser (p/parser {::p/env     {::p/reader                 [p/map-reader
                                                                       pc/reader2
                                                                       pc/open-ident-reader]
                                           ::pc/mutation-join-globals [:tempids]}
                              ::p/mutate  pc/mutate
                              ::p/plugins [(pc/connect-plugin {::pc/register [resolvers]})
                                           (p/post-process-parser-plugin p/elide-not-found)
                                           p/error-handler-plugin]}))



(def ordered-attributes
  [:apartment/bedrooms
   :apartment/bathrooms
   :apartment/base-price
   :apartment/cleaning-fee
   :apartment/deposit
   :apartment/aspectA
   :apartment/aspectB
   :apartment/aspectC
   :apartment/aspectD
   :apartment/aspectE
   :apartment/aspectF
   :apartment/aspectG
   :apartment/aspectH
   :apartment/aspectI
   :apartment/aspectJ
   :apartment/aspectK
   :apartment/aspectL
   :apartment/aspectM
   :apartment/aspectN
   :apartment/aspectO
   :apartment/aspectP])

(defonce cell-renders (volatile! 0))

(defmutation update-apartment [{:apartment/keys [id]
                                :keys           [attribute value]}]
  (action [{:keys [state]}]
    (log/spy :info @cell-renders)
    (swap! state assoc-in [:apartment/id id attribute] value)))

(defsc Apartment [this props]
  {:ident :apartment/id
   :query (fn [] (into [:apartment/id :apartment/label] ordered-attributes))})

(defsc Cell [this {:cell/keys [attribute target]}]
  {:query [:cell/attribute {:cell/target (comp/get-query Apartment)}]
   :ident (fn []
            ;; Use a compound value for the  id for the ident.
            ;; NOTE: when normalized, the target will be an ident, so we have to be careful here
            (let [id (if (vector? target) (second target) (:apartment/id target))]
              [:cell/id {:apartment/id id
                         :attribute    attribute}]))}
  (let [id (:apartment/id target)
        v  (get target attribute)]
    (vswap! cell-renders inc)
    (td nil                                                 ; if you include props, even nil, you'll get a macro instead of a fn call that is as fast as vanilla react
      (input {:value    (str v)                             ; DOM is always strings
              ;; synchronous transactions (using double !) will re-render ONLY the component
              ;; whose ident matches `this`. This prevents render from root altogether.
              :onChange (fn [evt] (let [new-value (enc/catching (js/parseInt (evt/target-value evt)))]
                                    (comp/transact!! this [(update-apartment {:apartment/id id
                                                                              :attribute    attribute
                                                                              :value        new-value})])))}))))

(def ui-cell (comp/factory Cell {:keyfn :cell/id}))

(defn- row-ident [{:row/keys [cells]}]
  ;; Again, cells could be a vector of maps or idents
  (let [cell (first cells)
        id   (if (vector? cell) (get-in cell [1 :attribute]) (:cell/attribute cell))]
    [:row/id id]))

(defsc Row [this {:row/keys [cells] :as props}]
  {:ident (fn [] (row-ident props))                         ; you could also propagate attribute into row data during normalization if this makes you uncomfortable
   :query [{:row/cells (comp/get-query Cell)}]}
  (let [row-label (some-> cells first :cell/attribute name)]
    (tr nil
      (th nil (str row-label))                              ; TODO: a label map would be nice
      (mapv ui-cell cells))))

(def ui-row (comp/factory Row {:keyfn row-ident}))

(defsc Table [this {:table/keys [rows]}]
  {:ident         (fn [] [:component/id ::Table])
   :query         [{:table/rows (comp/get-query Row)}]
   :initial-state {:table/rows []}}
  (let [row (log/spy :info (first rows))]
    (table nil
      (when row
        (thead nil
          (tr nil
            (th nil "")
            (mapv (fn [{:cell/keys [target]}] (th nil (str (:apartment/label target)))) (:row/cells row)))))
      (tbody nil
        (mapv ui-row rows)))))

(def ui-table (comp/factory Table))

(defmutation mock-load [_]
  (action [{:keys [state]}]
    (swap! state #(reduce (fn [sm apt] (merge/merge-component sm Apartment apt)) % apartments))))

(defmutation create-table-view [_]
  (action [{:keys [state]}]
    (let [apartments (mapv
                       (fn [ident] (log/spy :info (fns/ui->props @state Apartment ident)))
                       (log/spy :info
                         (fns/sort-idents-by @state (:apartments/all @state) :apartment/label)))
          rows       (mapv
                       (fn [a]
                         {:row/cells (mapv (fn [apt]
                                             {:cell/attribute a
                                              :cell/target    apt}) apartments)})
                       ordered-attributes)]
      (swap! state merge/merge-component Table {:table/rows rows}))))

(defsc Root [this {:root/keys [table] :as props}]
  {:query         [{:root/table (comp/get-query Table)}
                   :apartment/id]                           ; access from root to the entire table of apartments...to show count
   :initial-state {:root/table {}}}
  (div nil
    (h2 (str "There are " (count (:apartment/id props)) " apartments loaded."))
    (button
      {:onClick (fn [] (df/load! this :apartments/all Apartment {:post-mutation `create-table-view}))}
      "Load apartments")
    (ui-table table)))

(ws/defcard dynamic-recursive-entity-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       Root
     ::ct.fulcro/app        (let [process-eql (fn [eql] (async/go
                                                          (pathom-parser {} eql)))
                                  remote      (mock-http-server {:parser process-eql})]
                              {:remotes {:remote remote}})}))
