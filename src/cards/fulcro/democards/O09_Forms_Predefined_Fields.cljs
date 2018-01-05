(ns fulcro.democards.O09-Forms-Predefined-Fields
  (:require
    [fulcro.client.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard-doc]]
    [fulcro.client.primitives :as prim :refer [defui defsc]]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client :as fc]
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
    [fulcro.democards.N10-Twitter-Bootstrap-CSS :refer [render-example]]
    [fulcro.client.network :as net]
    [fulcro.ui.bootstrap3 :as b]
    [fulcro.ui.file-upload :as fu])
  (:refer-clojure :exclude [send])
  (:import [goog.net XhrIo EventType]))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  [comp form name label & params]
  (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
    (dom/label #js {:className "col-sm-2" :htmlFor name} label)
    (dom/div #js {:className "col-sm-10"} (apply f/form-field comp form name params))))

(defui ^:once KitchenSink
  static prim/InitialAppState
  (initial-state [this params] (f/build-form this {:db/id 1 :short-story (prim/get-initial-state FileUploadInput {:id :story})}))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (f/text-input :text)
                     (f/integer-input :number)
                     (f/dropdown-input :mood [(f/option :happy "Happy") (f/option :sad "Sad")])
                     (f/checkbox-input :done?)
                     (f/radio-input :rating #{1 2 3 4 5})
                     (f/textarea-input :essay)
                     (file-upload-input :short-story)])
  static prim/IQuery
  (query [this] [f/form-root-key f/form-key :db/id :text :number :mood :done? :rating :essay
                 {:short-story (prim/get-query FileUploadInput)}])
  static prim/Ident
  (ident [this props] [:sink/by-id (:db/id props)])
  Object
  (render [this]
    (let [props      (prim/props this)
          not-valid? (not (f/would-be-valid? props))]
      (b/container-fluid nil
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
                     :onClick  #(f/commit-to-entity! this :remote true)} "Submit"))))))

(def ui-sink (prim/factory KitchenSink {:keyfn :db/id}))

(defui ^:once CommitRoot
  static prim/InitialAppState
  (initial-state [this _] {:sink (prim/get-initial-state KitchenSink {:db/id 1})})
  static prim/IQuery
  (query [this] [{:sink (prim/get-query KitchenSink)}])
  Object
  (render [this]
    (let [{:keys [sink]} (prim/props this)]
      (render-example "800px" "600px"
        (ui-sink sink)))))

(defcard-doc
  "# Forms – All Built-in Field Types

  In order for this example to work (submission and file upload) you must start the dev upload server:

  ```
  # lein repl
  user=> (run-upload-server)
  ```
  - Connect to this page by changing the port on the URL to the one reported by the server.

  Please see the separate section on file uploads for instructions on setting up and using the file upload
  component.

  "
  (dc/mkdn-pprint-source KitchenSink))

(defonce upload-networking (file-upload-networking))

(defcard-fulcro form-changes
  CommitRoot
  {}
  {:fulcro       {
                  :started-callback (fn [{:keys [reconciler]}] (fu/install-reconciler! upload-networking reconciler))
                  :networking       {:remote      (net/make-fulcro-network "/api" :global-error-callback identity)
                                     :file-upload upload-networking}}
   :inspect-data false})

