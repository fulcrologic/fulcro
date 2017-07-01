(ns untangled-devguide.O15-Forms-File-Upload
  (:require
    [om.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [om.next :as om :refer [defui]]
    [untangled.client.cards :refer [untangled-app]]
    [untangled.client.core :as uc]
    [untangled.ui.forms :as f]
    [untangled.client.mutations :refer [defmutation]]
    [goog.events :as events]
    [untangled.client.network :as net]
    [clojure.string :as str]
    [untangled.ui.file-upload :refer [FileUploadInput file-upload-input file-upload-networking]]
    [untangled.client.logging :as log]
    [untangled.ui.bootstrap3 :as b])
  (:refer-clojure :exclude [send])
  (:import [goog.net XhrIo EventType]))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  [comp form name label & params]
  (dom/div #js {:className (str "form-group" (if (f/invalid? form name) " has-error" ""))}
    (dom/label #js {:className "col-sm-2" :htmlFor name} label)
    (dom/div #js {:className "col-sm-10"} (apply f/form-field comp form name params))))

(defui ^:once FileUploadDemo
  static uc/InitialAppState
  (initial-state [this params]
    (f/build-form this {:db/id 1 :short-story (uc/get-initial-state FileUploadInput {:id :story})}))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (file-upload-input :short-story)])
  static om/IQuery
  (query [this] [f/form-root-key f/form-key :db/id :text {:short-story (om/get-query FileUploadInput)}])
  static om/Ident
  (ident [this props] [:example/by-id (:db/id props)])
  Object
  (render [this]
    (let [props      (om/props this)
          not-valid? (not (f/would-be-valid? props))]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this props :short-story "Story (PDF):" :accept "application/pdf" :multiple? true)
        (b/button {:disabled   not-valid?
                      :onClick #(f/commit-to-entity! this :remote true)} "Submit")))))

(def ui-example (om/factory FileUploadDemo {:keyfn :db/id}))

(defui ^:once CommitRoot
  static uc/InitialAppState
  (initial-state [this _] {:demo (uc/initial-state FileUploadDemo {:db/id 1})})
  static om/IQuery
  (query [this] [:ui/react-key
                 {:demo (om/get-query FileUploadDemo)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key demo]} (om/props this)]
      (dom/div #js {:key react-key}
        (ui-example demo)))))

(defcard-doc
  "# Forms – File Upload

  SEE: upload_server.clj and the devcards in this file for an example.

  There are a few steps for setting up a working file upload control:

  1. Install file upload server support in your server's Ring stack and add logic for dealing with
  forms submissions that contain uploaded files.
  2. Run the server
  3. Add file-upload networking as an extra remote in Untangled Client (requires v0.8.1+, and Om alpha48+)
  4. Load the page through your server (not figwheel).

  This repository includes a script named `run-file-upload-server.sh`. The devcard in this file should be loaded
  from that server (port 8085 by default).

  ## Understanding File Upload

  The lifecycle of the file upload control is meant to be tied to form interactions and submission. You can use
  the file upload without forms, but in that case you'll need to write some mutation code that you trigger to
  tell your server what the file upload is for.

  The abstract composition of a file upload into your application takes the following steps:



  ### Customizing the Ring Stack

  If you're using the modular server support for Untangled, then you can build a stack that contains at least
  the following middleware: transit, API hander, file upload, and wrap-multipart-params. Other bits are also
  commonly useful. Here's a sample middleware component that has been tested to work:

  TODO: Show how to inject this into the normal API handler so you can access the files on form submission...

  TODO: Finish upload handler (needs metadata and storage plugin, like image upload...probably just use that)

  TODO: For now, just look at upload-server namespace in dev source directory.

  ```
  (defrecord CustomMiddleware [middleware api-handler]
    component/Lifecycle
    (stop [this] (dissoc this :middleware))
    (start [this]
      (assoc this :middleware
                  (-> not-found
                    (MIDDLEWARE api-handler) ; Normal API handler
                    wrap-file-upload ; REMOTE HANDLER (needs transit for response)
                    middleware/wrap-transit-params
                    middleware/wrap-transit-response
                    (wrap-resource \"public\")
                    wrap-content-type
                    wrap-not-modified
                    wrap-params
                    wrap-multipart-params ; TURN UPLOADS INTO DISK FILES
                    wrap-gzip))))

  ### Adding UC File Upload remote

  The client-side setup is very simple, just add a `:networking` parameter to your client that has a map
  containing the normal remote and a file upload remote:

  ```
  (new-untangled-client
    :networking {:remote      (net/make-untangled-network \"/api\" :global-error-callback identity)
                 :file-upload (untangled.ui.file-upload/file-upload-networking)})
  ```

  ## Customizing the Rendering

  You can customize how the overall upload UI looks in a few ways.

  ### Changing the UI of the Upload Button

  The default rendering shows an upload button. Once files are selected this button possibly goes away (e.g.
  if `multiple?` is false). The button itself can be customized using the `:renderControl` computed property
  (which can be passed to the ui-file-upload as a computed prop, or through the form field rendering as an
  add-on parameter).

  The function is responsible for hooking up to a HTML file input onChange event, and invoking the
  `upload/add-file` mutation on each file that is to be added.

  ### Changing the UI of the Individual Files

  The file upload control *always* renders the current list of files in a `ul` DOM parent. Each
  file in this list can be customized using the `:renderFile` parameter, which should be a function
  that receives the file component and renders the correct DOM. This function will be called during upload
  refreshes, and the `:file/progress` in props will indicate progres and `:file/status` will indicate
  if the transfer is still active. The computed props will include an `onCancel` function that you can
  call to cancel the inclusion of the file (i.e. you can hook a call to `onCancel` up to a cancel button
  in your rendering).

  ```
  (defn render-a-file [file-comp]
    (let [onCancel   (om/get-computed file-comp :onCancel)
          {:keys [file/id file/name file/size file/progress file/status] :as props} (om/props file-comp)]
      (dom/li #js {:key (str \"file-\" id)} (str label \" (\" size \" bytes) \")
        (case status
          :failed (dom/span nil \"FAILED!\")
          :done (dom/span nil \"Ready.\")
          (dom/span nil \"Sending...\" progress \"%\"))
        (e/ui-icon {:onClick #(onCancel id)
                    :glyph   :cancel}))))))
  ```

  ### Rendering Details Outside of the Control

  `(current-files upload-control)` returns the current file list. You could use this to add a file count
  to a part of the UI that is outside of the control, as long as you've got access to the control's properties
  (e.g. in the parent).

  ### Showing Image Previews

  The file component props can be used to access a low-level `js/File` object of the file you're uploading.
  This can be used to do things like show image previews of images you're uploading.

  Calling `(get-js-file file-props)` will return the `js/File` object of the file.

  From there, you can use regular React DOM tricks (e.g. `:ref`) to do the rest in a custom
  file row rendering:

  TODO: TEST THIS AND REFINE IT!!!

  (defn render-a-file [file-comp]
    (let [onCancel   (om/get-computed file-comp :onCancel)
          {:keys [file/id file/name file/size file/progress file/status] :as props} (om/props file-comp)
          js-file (get-js-file props)]
      (dom/li #js {:key (str \"file-\" id)} (str label \" (\" size \" bytes) \")
        (case status
          :failed (dom/span nil \"FAILED!\")
          :done (dom/span nil \"Ready.\")
          (dom/span nil \"Sending...\" progress \"%\"))
        (dom/img #js {:width \"100px\" :ref (fn [c] (.setFile c js-file))})
        (e/ui-icon {:onClick #(onCancel id)
                    :glyph   :cancel}))))))

  "
  (dc/mkdn-pprint-source FileUploadDemo))

(defcard form-file-upload
  (untangled-app CommitRoot
    :networking {:remote      (net/make-untangled-network "/api" :global-error-callback identity)
                 :file-upload (file-upload-networking)})
  {}
  {:inspect-data false})
