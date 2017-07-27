(ns cards.autocomplete-cards
  (:require
    [devcards.core :as dc :include-macros true]
    [om.next :as om :refer [defui]]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [om.dom :as dom]
    [fulcro.client.core :as fc]
    [fulcro.client.mutations :as m]
    [fulcro.client.data-fetch :as df]))

(dc/defcard-doc
  "# Autocomplete

  A fairly common desire in user interfaces is to try to help the user complete an input by querying the server
  or possible completions. Like many of the demos, the UI for this example is intentionally very bare-bones
  so that we can primarily concentrate on the data-flow that you'll want to use to achieve the effect.

  Typically you will want to trigger autocomplete on a time interval (e.g. using `goog.functions/debounce`)
  or after some number of characters have been entered into the field. We're going to implement it in the
  following way:

  - The autocomplete query will trigger when the input goes from having 2 characters of input to 3.
  - The autocomplete suggestion list will clear if length goes below 3
  - The user must use the mouse to select the desired completion (we're not handling kb events)
  ")

(defui ^:once CompletionList
  Object
  (render [this]
    (let [{:keys [values onValueSelect]} (om/props this)]
      (dom/ul nil
        (map (fn [v]
               (dom/li #js {:key v}
                 (dom/a #js {:onClick #(onValueSelect v)} v))) values)))))

(def ui-completion-list (om/factory CompletionList))

(defn trigger-suggestions? [old-value new-value]
  (and (= 2 (.-length old-value)) (= 3 (.-length new-value))))

(defn autocomplete-ident [id-or-props]
  (if (map? id-or-props)
    [:autocomplete/by-id (:db/id id-or-props)]
    [:autocomplete/by-id id-or-props]))

(defui ^:once Autocomplete
  static om/IQuery
  (query [this] [:db/id                                     ; the component's ID
                 :autocomplete/suggestions                  ; the current completion suggestions
                 :autocomplete/value])                      ; the current user-entered value
  static om/Ident
  (ident [this props] (autocomplete-ident props))
  static fc/InitialAppState
  (initial-state [c {:keys [id]}] {:db/id id :autocomplete/suggestions [] :autocomplete/value ""})
  Object
  (render [this]
    (let [{:keys [db/id autocomplete/suggestions autocomplete/value]} (om/props this)
          field-id (str "autocomplete-" id)
          onSelect (or (om/get-computed this :onSelect) identity)]
      (dom/div nil
        (dom/label #js {:htmlFor field-id} "Airport: ")
        (dom/input #js {:id       field-id
                        :onChange (fn [evt]
                                    (let [new-value (.. evt -target -value)]
                                      (when (trigger-suggestions? value new-value)
                                        (df/load this :autocomplete/airports nil
                                          {:params {:search new-value}
                                           :target (conj (autocomplete-ident id) :autocomplete/suggestions)}))
                                      (m/set-string! this :autocomplete/value :value new-value)
                                      ))})
        (when (and (vector? suggestions) (seq suggestions))
          (ui-completion-list {:values suggestions :onValueSelect onSelect}))))))

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

(defcard-fulcro autocomplete
  AutocompleteRoot
  {}
  {:inspect-data true})
