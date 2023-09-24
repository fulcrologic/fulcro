(ns com.fulcrologic.fulcro.cards.data-view-cards
  "A demonstration of pivoting a table. This example shows one way of dealing with a UI/data layout mismatch that
   might lead to rendering slowness if you just rendered it via raw fns (which is a good, simple option for smaller
   numbers of components).

   The idea is that we want to compare apartments where the apartment names are the cols, and the row names are the aspect
   of the apartment (number of bedrooms)...but the apartments are normalized by their ID.

   The key realization is that an ident can have a compound ID, so we can generate view components (Cell) that have
   and ident tied to the position in the table [:cell/id (apt id, apt attribute)]. We could also use an ident like
   [apt-attribute apt-id]. As long as the cell has a unique ident, we can use sync transact to force a synchronous
   re-render of JUST that cell.

   So, we have Table/Row/Cell classes that deal with the dataified structure of the normalized view, and the ultimate cell
   *does* query for the apartment used by that cell. This ensures that if the apartment is updated OUTSIDE of the cell, the
   cell will still refresh properly."
  (:require
    [cljs.core.async :as async]
    [com.fulcrologic.fulcro.algorithms.merge :as merge]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.dom :refer [table td th tr thead tbody div h2 input button]]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [nubank.workspaces.card-types.fulcro3 :as ct.fulcro]
    [nubank.workspaces.core :as ws]
    [taoensso.encore :as enc]
    [taoensso.timbre :as log]))

(def apartments [{:apartment/id       1
                  :apartment/bedrooms 2
                  :apartment/label    "Town Place"}
                 {:apartment/id       2
                  :apartment/bedrooms 1
                  :apartment/label    "Foobar Apts"}
                 {:apartment/id       3
                  :apartment/bedrooms 2
                  :apartment/label    "Fancy Lake Place"}
                 {:apartment/id       4
                  :apartment/bedrooms 2
                  :apartment/label    "Happy homes"}
                 {:apartment/id       5
                  :apartment/bedrooms 4
                  :apartment/label    "The Place"}
                 {:apartment/id       6
                  :apartment/bedrooms 1
                  :apartment/label    "Smallville Apts"}])

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
  (let [row (first rows)]
    (table nil
      (when row
        (thead nil
          (tr nil
            (th nil "")
            (mapv (fn [{:cell/keys [target]}] (th nil (str (:apartment/label target)))) (:row/cells row)))))
      (tbody nil
        (mapv ui-row rows)))))

(def ui-table (comp/factory Table))

(defmutation set-all-bedrooms
  "Demonstrate that external modifications affect table content by setting the number of bedrooms on all apartments"
  [{:keys [n]}]
  (action [{:keys [state]}]
    (let [apartments (vals (:apartment/id @state))]
      (swap! state
        #(reduce
           (fn [sm {:apartment/keys [id]}]
             (assoc-in sm [:apartment/id id :apartment/bedrooms] n))
           %
           apartments)))))

(defmutation create-table-view
  "Mutation. Converts all of the apartments that were loaded into a table view."
  [_]
  (action [{:keys [state]}]
    (let [apartments (mapv
                       (fn [ident] (fns/ui->props @state Apartment ident))
                       (fns/sort-idents-by @state (:apartments/all @state) :apartment/label))
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
    (button
      {:onClick (fn [] (comp/transact! this [(set-all-bedrooms {:n 3})]))}
      "Set all apartments to 3 bedroom")
    (ui-table table)))

(ws/defcard dynamic-recursive-entity-card
  (ct.fulcro/fulcro-card
    {::ct.fulcro/wrap-root? false
     ::ct.fulcro/root       Root
     ::ct.fulcro/app        (let [process-eql (fn [eql] (async/go
                                                          (pathom-parser {} eql)))
                                  remote      (mock-http-server {:parser process-eql})]
                              {:remotes {:remote remote}})}))
