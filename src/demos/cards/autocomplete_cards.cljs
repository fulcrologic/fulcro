(ns cards.autocomplete-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [om.next :as om :refer [defui]]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [goog.functions :as gf]
    [om.dom :as dom]
    [fulcro.client.core :as fc]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro.client.data-fetch :as df]
    [clojure.string :as str]))

(defn autocomplete-ident
  "Returns the ident for an autocomplete control. Can be passed a map of props, or a raw ID."
  [id-or-props]
  (if (map? id-or-props)
    [:autocomplete/by-id (:db/id id-or-props)]
    [:autocomplete/by-id id-or-props]))

(defui ^:once CompletionList
  Object
  (render [this]
    (let [{:keys [values onValueSelect]} (om/props this)]
      (dom/ul nil
        (map (fn [v]
               (dom/li #js {:key v}
                 (dom/a #js {:href "javascript:void(0)" :onClick #(onValueSelect v)} v))) values)))))

(def ui-completion-list (om/factory CompletionList))

(defmutation populate-loaded-suggestions
  "Fulcro mutation: Autocomplete suggestions are loaded in a non-visible property to "
  [{:keys [id]}]
  (action [{:keys [state]}]
    (let [autocomplete-path (autocomplete-ident id)
          source-path       (conj autocomplete-path :autocomplete/loaded-suggestions)
          target-path       (conj autocomplete-path :autocomplete/suggestions)]
      (swap! state assoc-in target-path (get-in @state source-path)))))

(def get-suggestions
  "(get-suggestions comp search id)"
  (gf/debounce
    (fn [comp new-value id]
      (df/load comp :autocomplete/airports nil
        {:params               {:search new-value}
         :marker               false
         :post-mutation        `populate-loaded-suggestions
         :post-mutation-params {:id id}
         :target               (conj (autocomplete-ident id) :autocomplete/loaded-suggestions)})) 500))

(defui ^:once Autocomplete
  static om/IQuery
  (query [this] [:db/id                                     ; the component's ID
                 :autocomplete/loaded-suggestions           ; A place to do the loading, so we can prevent flicker in the UI
                 :autocomplete/suggestions                  ; the current completion suggestions
                 :autocomplete/value])                      ; the current user-entered value
  static om/Ident
  (ident [this props] (autocomplete-ident props))
  static fc/InitialAppState
  (initial-state [c {:keys [id]}] {:db/id id :autocomplete/suggestions [] :autocomplete/value ""})
  Object
  (render [this]
    (let [{:keys [db/id autocomplete/suggestions autocomplete/value]} (om/props this)
          field-id             (str "autocomplete-" id)     ; for html label/input association
          ;; server gives us a few, and as they type we need to filter it further for display as they type.
          filtered-suggestions (when (vector? suggestions)
                                 (filter #(str/includes? (str/lower-case %) (str/lower-case value)) suggestions))
          ; We want to not show the list if they've chosen something valid
          exact-match?         (and (= 1 (count filtered-suggestions)) (= value (first filtered-suggestions)))
          ; When they select an item, we place it's value in the input
          onSelect             (fn [v] (m/set-string! this :autocomplete/value :value v))]
      (dom/div #js {:style #js {:height "600px"}}
        (dom/label #js {:htmlFor field-id} "Airport: ")
        (dom/input #js {:id       field-id
                        :value    value
                        :onChange (fn [evt]
                                    (let [new-value (.. evt -target -value)]
                                      ; we avoid even looking for help until they've typed a couple of letters
                                      (if (>= (.-length new-value) 2)
                                        (get-suggestions this new-value id)
                                        ; if they shrink the value too much, clear suggestions
                                        (m/set-value! this :autocomplete/suggestions []))
                                      ; always update the input itself (controlled)
                                      (m/set-string! this :autocomplete/value :value new-value)))})
        ; show the completion list when it exists and isn't just exactly what they've chosen
        (when (and (vector? suggestions) (seq suggestions) (not exact-match?))
          (ui-completion-list {:values filtered-suggestions :onValueSelect onSelect}))))))

(def ui-autocomplete (om/factory Autocomplete))

(defui ^:once AutocompleteRoot
  static fc/InitialAppState
  (initial-state [c p] {:airport-input (fc/get-initial-state Autocomplete {:id :airports})})
  static om/IQuery
  (query [this] [:ui/react-key {:airport-input (om/get-query Autocomplete)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key airport-input]} (om/props this)]
      (dom/div #js {:key react-key}
        (dom/h4 nil "Airport Autocomplete")
        (ui-autocomplete airport-input)))))



(dc/defcard-doc
  "# Autocomplete

  A fairly common desire in user interfaces is to try to help the user complete an input by querying the server
  or possible completions. Like many of the demos, the UI for this example is intentionally very bare-bones
  so that we can primarily concentrate on the data-flow that you'll want to use to achieve the effect.

  Typically you will want to trigger autocomplete on a time interval (e.g. using `goog.functions/debounce`)
  or after some number of characters have been entered into the field. We're going to implement it in the
  following way:

  - The autocomplete query will trigger when the input has at least 2 characters of input.
  - The server will be asked for 10 suggestions, and will update on a debounced interval.
  - The autocomplete suggestion list will clear if length goes below 2
  - The user must use the mouse to select the desired completion (we're not handling kb events)

  ## Basic Operation

  The basic idea is as follows:

  - Make a component that has isolated state, so you can have more than one
  - Decide when to trigger the server query
  - Use load, but target it for a place that is not on the UI
     - Allows the UI to continue displaying old list while new load is in progress
     - Use a post-mutation to move the finished load into place

  ## The Server Query

  For our server we have a simple list of all of the airports in the world that have 3-letter codes. Our
  sever just grabs 10 that match your search string:

  ```
  (defn airport-search [^String s]
    (->> airports-txt
      (filter (fn [i] (str/includes? (str/lower-case i) (str/lower-case s))))
      (take 10)
      vec))

  (defquery-root :autocomplete/airports
    (value [env {:keys [search]}]
      (airport-search search)))
  ```

  ## The UI and Post Mutation

  We create a helper function so we don't have to manually generate the ident for autocomplete wherever we need it:
  "
  (dc/mkdn-pprint-source autocomplete-ident)
  "
  We use Closure's debounce to generate a function that will not bash the server too hard. Load's will run at most
  once every 500ms. Notice that the server query itself is for airport suggestions, and we use the `:target` option
  to place the results in our autocomplete's field:
  "
  (dc/mkdn-pprint-source get-suggestions)
  "
  Notice also that when we trigger the load it goes into the auto-complete widget's `:autocomplete/loaded-suggestions` field.
  The UI renders the `:autocomplete/suggestions`. We do this so there is no flicker on the UI while loading, but
  at the end of the load we need to update the suggestions. We do this by running this post mutation:

  ```
  (defmutation populate-loaded-suggestions
    \"Fulcro mutation: Autocomplete suggestions are loaded in a non-visible property to prevent flicker.
      This copies it over to the visible area\"
    [{:keys [id]}]
    (action [{:keys [state]}]
      (let [autocomplete-path (autocomplete-ident id)
            source-path       (conj autocomplete-path :autocomplete/loaded-suggestions)
            target-path       (conj autocomplete-path :autocomplete/suggestions)]
        (swap! state assoc-in target-path (get-in @state source-path)))))
  ```

  ### The List of Matches

  The list is a React component, but only for organization. It is not a stateful component at all and receives all
  data from the parent:
  "
  (dc/mkdn-pprint-source CompletionList)
  (dc/mkdn-pprint-source ui-completion-list)
  "### The Autocomplete Control

  This is where most of the magic happens. Read the comments within the source for details:
  "
  (dc/mkdn-pprint-source Autocomplete)
  (dc/mkdn-pprint-source ui-autocomplete)
  "### An Example

  The following is a root component, and then a devcard that demos the live result. This is a full-stack example
  so make sure you're running the demo server (see the Introduction page of the demos).
  "
  (dc/mkdn-pprint-source AutocompleteRoot))

(defcard-fulcro autocomplete
  AutocompleteRoot
  {}
  {:inspect-data false})
