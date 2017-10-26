(ns fulcro-devguide.O09-Forms-Predefined-Fields
  (:require
    [fulcro.client.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [fulcro.client.primitives :as om :refer [defui]]
    [fulcro.client.cards :refer [fulcro-app]]
    [fulcro.client.core :as fc]
    [fulcro.ui.forms :as f]
    [fulcro.ui.elements :as ele]
    [fulcro.client.mutations :refer [defmutation]]
    [goog.crypt.base64 :refer [encodeByteArray]]
    [fulcro.ui.elements :as e]
    [cognitect.transit :as ct]
    [goog.events :as events]
    [fulcro.transit :as t]
    [clojure.string :as str]
    [fulcro.ui.file-upload :refer [FileUploadInput file-upload-input file-upload-networking]]
    [fulcro.client.logging :as log]
    [fulcro.client.network :as net]
    [fulcro.ui.bootstrap3 :as b])
  (:refer-clojure :exclude [send])
  (:import [goog.net XhrIo EventType]))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  [comp form name label & params]
  (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
    (dom/label #js {:className "col-sm-2" :htmlFor name} label)
    (dom/div #js {:className "col-sm-10"} (apply f/form-field comp form name params))))

(defui ^:once KitchenSink
  static fc/InitialAppState
  (initial-state [this params] (f/build-form this {:db/id 1 :short-story (fc/get-initial-state FileUploadInput {:id :story})}))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/text-input :text)
                     (f/integer-input :number)
                     (f/dropdown-input :mood [(f/option :happy "Happy") (f/option :sad "Sad")])
                     (f/checkbox-input :done?)
                     (f/radio-input :rating #{1 2 3 4 5})
                     (f/textarea-input :essay)
                     (file-upload-input :short-story)])
  static om/IQuery
  (query [this] [f/form-root-key f/form-key :db/id :text :number :mood :done? :rating :essay
                 {:short-story (om/get-query FileUploadInput)}])
  static om/Ident
  (ident [this props] [:sink/by-id (:db/id props)])
  Object
  (render [this]
    (let [props      (om/props this)
          not-valid? (not (f/would-be-valid? props))]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this props :text "Text:")
        (field-with-label this props :number "Number:")
        (field-with-label this props :mood "Mood:")
        (field-with-label this props :done? "Done:")
        (field-with-label this props :essay "Essay:" :rows 10 :maxLength 100)
        (dom/div #js {:className "form-group"}
          (dom/label #js {:className "col-sm-2" :htmlFor :rating} "Rating:")
          (dom/div #js {:className "col-sm-10"}
            (f/form-field this props :rating :choice 1 :label 1)
            (f/form-field this props :rating :choice 2 :label 2)
            (f/form-field this props :rating :choice 3 :label 3)
            (f/form-field this props :rating :choice 4 :label 4)
            (f/form-field this props :rating :choice 5 :label 5)))
        (field-with-label this props :short-story "Story (PDF):" :accept "application/pdf" :multiple? true)
        (b/button {:disabled not-valid?
                   :onClick  #(f/commit-to-entity! this :remote true)} "Submit")))))

(def ui-sink (om/factory KitchenSink {:keyfn :db/id}))

(defui ^:once CommitRoot
  static fc/InitialAppState
  (initial-state [this _] {:sink (fc/initial-state KitchenSink {:db/id 1})})
  static om/IQuery
  (query [this] [:ui/react-key
                 {:sink (om/get-query KitchenSink)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key sink]} (om/props this)]
      (dom/div #js {:key react-key}
        (ui-sink sink)))))

(defcard-doc
  "# Forms – All Built-in Field Types

  In order for this example to work (submission and file upload) you must start the dev upload server:

  - Start a REPL
  ```
  (require 'fulcro-devguide.upload-server)
  (fulcro-devguide.upload-server/go)
  ```
  - Connect to this page by changing the port on the URL to the one reported by the server.

  Please see the separate section on file uploads for instructions on setting up and using the file upload
  component.

  "
  (dc/mkdn-pprint-source KitchenSink))

(defcard form-changes
  (fulcro-app CommitRoot
    :networking {:remote      (net/make-fulcro-network "/api" :global-error-callback identity)
                 :file-upload (file-upload-networking)})
  {}
  {:inspect-data false})

