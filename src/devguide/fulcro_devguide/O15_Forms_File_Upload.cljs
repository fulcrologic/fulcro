(ns fulcro-devguide.O15-Forms-File-Upload
  (:require
    [om.dom :as dom]
    [devcards.core :as dc :refer-macros [defcard defcard-doc]]
    [om.next :as om :refer [defui]]
    [fulcro.client.cards :refer [defcard-fulcro]]
    [fulcro.client.core :as fc]
    [fulcro.ui.forms :as f]
    [fulcro.client.mutations :as m :refer [defmutation]]
    [fulcro-devguide.N10-Twitter-Bootstrap-CSS :refer [render-example]]
    [goog.events :as events]
    [fulcro.client.network :as net]
    [clojure.string :as str]
    [fulcro.ui.file-upload :as fu :refer [FileUploadInput file-upload-input file-upload-networking]]
    [fulcro.client.logging :as log]
    [fulcro.ui.bootstrap3 :as b]
    [fulcro.client.data-fetch :as df]
    [fulcro.ui.file-upload :as upload])
  (:refer-clojure :exclude [send])
  (:import [goog.net XhrIo EventType]))

(defn field-with-label
  "A non-library helper function, written by you to help lay out your form."
  [comp form name label & params]
  (b/row {}
    (b/col {:xs 2 :htmlFor name} label)
    (b/col {:xs 10} (apply f/form-field comp form name params))))

(defui ^:once FileUploadDemo
  static fc/InitialAppState
  (initial-state [this params]
    (f/build-form this {:db/id 1 :image-uploads (fc/get-initial-state FileUploadInput {:id :image-uploads})}))
  static f/IForm
  (form-spec [this] [(f/id-field :db/id)
                     (file-upload-input :image-uploads)])
  static om/IQuery
  (query [this] [f/form-root-key f/form-key :db/id :text {:image-uploads (om/get-query FileUploadInput)}])
  static om/Ident
  (ident [this props] [:example/by-id (:db/id props)])
  Object
  (render [this]
    (let [{:keys [db/id] :as props} (om/props this)
          onDone     (om/get-computed this :onDone)
          not-valid? (not (f/would-be-valid? props))]
      (dom/div #js {:className "form-horizontal"}
        (field-with-label this props :image-uploads "Image Files:" :accept "image/*" :multiple? true
          :renderFile (fn [file-component]
                        (let [onCancel (om/get-computed file-component :onCancel)
                              {:keys [file/id file/name file/size file/progress file/status] :as props} (om/props file-component)
                              label    (fu/cropped-name name 20)]
                          (dom/li #js {:style #js {:listStyleType "none"} :key (str "file-" id)}
                            (str label " (" size " bytes) ") (b/glyphicon {:size "14pt" :onClick #(onCancel id)} :remove-circle)
                            (dom/br nil)
                            (case status
                              :failed (dom/span nil "FAILED!")
                              :done ""
                              (b/progress-bar {:current progress})))))
          :renderControl (fn [onChange accept multiple?]
                           (let [control-id (str "add-control-" id)
                                 attrs      (cond-> {:onChange (fn [evt] (onChange evt))
                                                     :id       control-id
                                                     :style    #js {:display "none"}
                                                     :value    ""
                                                     :type     "file"}
                                              accept (assoc :accept accept)
                                              multiple? (assoc :multiple "multiple")
                                              :always clj->js)]
                             (dom/label #js {:htmlFor control-id} (b/glyphicon {:className "btn btn-primary"} :plus)
                               (dom/input attrs)))))
        (b/button {:disabled not-valid?
                   :onClick  #(when onDone (onDone))} "Done")))))

(def ui-example (om/factory FileUploadDemo {:keyfn :db/id}))

(defui ^:once Image
  static om/Ident
  (ident [this props] [:image/by-id (:db/id props)])
  static om/IQuery
  (query [this] [:db/id :image/url :ui/fetch-state])
  Object
  (render [this]
    (let [{:keys [image/url]} (om/props this)]
      (b/col {:xs 2}
        (dom/img #js {:width "100%" :src url})))))

(def ui-image (om/factory Image {:keyfn :db/id}))

(defn- load-images [component page]
  (df/load component :server/image-library Image {:params {:page (or page 1)}
                                                  :target [:image-library/by-id 1 :library/images]}))

(defui ^:once ImageLibrary
  static fc/InitialAppState
  (initial-state [c p] {:db/id (:id p) :ui/page 1 :library/images []})
  static om/Ident
  (ident [this props] [:image-library/by-id (:db/id props)])
  static om/IQuery
  (query [this] [:db/id :ui/page {:library/images (om/get-query Image)}])
  Object
  (componentDidMount [this props]
    (let [{:keys [library/images ui/page]} (om/props this)]
      (when (or (map? images) (empty? images)) (load-images this (or page 1)))))
  (render [this]
    (let [{:keys [db/id library/images ui/page]} (om/props this)
          onUpload (om/get-computed this :onUpload)]
      (dom/div nil
        (b/button {:onClick #(when onUpload (onUpload))} "Upload")
        (if (map? images)                                   ; it is a loading marker
          (dom/p nil "Loading...")
          (let [rows (partition-all 6 images)]
            (dom/div nil
              (dom/span nil (str "Page " page))
              (when (not= 1 page)
                (b/button {:onClick (fn []
                                      (m/set-value! this :ui/page (dec page))
                                      (load-images this (dec page)))} "Prior"))
              (when (= 6 (count (last rows)))
                (b/button {:onClick (fn []
                                      (m/set-value! this :ui/page (inc page))
                                      (load-images this (inc page)))} "Next"))

              (map-indexed (fn [idx row]
                             (b/row {}
                               (mapv ui-image row))) rows))))))))

(def ui-image-library (om/factory ImageLibrary))

(defmutation clear-upload-list [ignored]
  (action [{:keys [state]}]
    (swap! state upload/clear-upload-list-impl :image-uploads)))

(defui ^:once ImageLibraryDemo
  static fc/InitialAppState
  (initial-state [this _] {:demo          (fc/get-initial-state FileUploadDemo {:db/id 1})
                           :image-library (fc/get-initial-state ImageLibrary {:id 1})})
  static om/Ident
  (ident [this props] [:demo/by-id :images])
  static om/IQuery
  (query [this] [:ui/show-library?
                 {:image-library (om/get-query ImageLibrary)}
                 {:demo (om/get-query FileUploadDemo)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key ui/show-library? demo image-library]} (om/props this)]
      (if show-library?
        (ui-image-library (om/computed image-library {:onUpload #(m/toggle! this :ui/show-library?)}))
        (ui-example (om/computed demo {:onDone (fn []
                                                 (om/transact! this `[(clear-upload-list {})])
                                                 (m/toggle! this :ui/show-library?))}))))))

(def ui-demo (om/factory ImageLibraryDemo))

(defui ^:once DemoRoot
  static fc/InitialAppState
  (initial-state [this _] {:screen (fc/get-initial-state ImageLibraryDemo {})})
  static om/IQuery
  (query [this] [:ui/react-key {:screen (om/get-query ImageLibraryDemo)}])
  Object
  (render [this]
    (let [{:keys [ui/react-key screen]} (om/props this)]
      (render-example "100%" "230px"
        (dom/div #js {:key react-key}
          ; In an iframe, so embed list style via react
          (dom/style #js {} ".file-upload-list { padding-left: 0; }")
          (ui-demo screen))))))

(defcard-doc
  "
  # Forms – File Upload

  ## Setup

  There are a few steps for setting up a working file upload control:

  1. Install file upload server support in your server's Ring stack and add logic for dealing with
  forms submissions that contain uploaded files.
  2. Run the server
  3. Add file-upload networking as an extra remote in Fulcro Client (requires v1.0.0+, and Om alpha48+)
  4. Load the page through your server (not figwheel).

  This repository includes a script named `run-file-upload-server.sh`. The devcard in this file should be loaded
  from that server (port 8085 by default).

  ## Understanding File Upload

  The lifecycle of the file upload control can be tied to form interactions and submission, or can act as
  a standalone upload system (though you'll still embed it in a form, of sorts). When upload is triggered the file object
  on the client gets an ID remap from your server storage component, and you can easily inject that server component into your
  parsing environment or other server handlers to deal with the result.

  The abstract composition of a file upload into your application takes the following steps:

  - Ensuring that the server's ring middleware will encode file uploads into a temp directory and add them to the request.
  - Adding a network handler for the file uploads to the server.
  - Adding a networking remote to the client to talk to the server.

  ### Customizing the Ring Stack

  If you're using the modular server support for Fulcro, then you can build a stack that contains at least
  the following middleware: transit, API hander, file upload, and wrap-multipart-params. Other bits are also
  commonly useful.

  Here's a sample middleware component for a modular server that has been tested to work (see `upload-server.clj` for the
  complete code):

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
  ```

  ### Adding the File Upload remote:

  The client-side setup is very simple, just add a `:networking` parameter to your client that has a map
  containing the normal remote and a file upload remote:

  ```
  ; You need a reference to the network, because it needs to be told about the reconciler in order to send progress
  ; updates
  (defonce upload-networking (fulcro.ui.file-upload/file-upload-networking))

  (new-fulcro-client
    ; Once started, tell the networking where to find the reconciler
    :started-callback (fn [{:keys [reconciler]}] (fulcro.ui.file-upload/install-reconciler! upload-networking reconciler))
    :networking {:remote      (net/make-fulcro-network \"/api\" :global-error-callback identity)
                 :file-upload upload-networking})
  ```

  ## Customizing the Rendering

  The normal form field rendering is predefined as it is for all form fields, but in the case of this control
  there are a number of elements: the button to add files, along with a mechansim to show the files that have been
  added.

  You can customize how the overall upload UI looks in a few ways.

  ### Changing the UI of the Upload Button

  The default rendering shows an upload button. Once files are selected this button possibly goes away (e.g.
  if `multiple?` is false). The button itself can be customized using the `:renderControl` computed property
  (which can be passed to the ui-file-upload as a computed prop, or through the form field rendering as an
  add-on parameter).

  The function is responsible for hooking up to a HTML file input onChange event, and invoking the
  `upload/add-file` mutation on each file that is to be added.

  The example on this page shows the details.

  ### Changing the UI of the Individual Files

  The file upload control *always* renders the current list of files in a `ul` DOM parent with the
  CSS class `file-upload-list`. By default, it places each file into this list with an `li` element.
  However, this can be customized using the `:renderFile` parameter, which should be a function
  that receives the file component and renders the correct DOM. This function will also be called during upload
  refreshes, and the `:file/progress` in props will indicate progres and `:file/status` will indicate
  if the transfer is still active. The computed props will include an `onCancel` function that you can
  call to cancel the inclusion of the file (i.e. you can hook a call to `onCancel` up to a cancel button
  in your rendering).

  Again, see the code in the demo on this page for details.

  ### Rendering Details Outside of the Control

  `(current-files upload-control)` returns the current file list. You could use this to add a file count
  to a part of the UI that is outside of the control, as long as you've got access to the control's properties
  (e.g. in the parent).

  ### Showing Image Previews

  The file component props can be used to access a low-level `js/File` object of the file you're uploading.
  This can be used to do things like show image previews of images you're uploading.

  Calling `(get-js-file file-props)` will return the `js/File` object of the file.

  From there, you can use regular React DOM tricks (e.g. `:ref`) to do the rest in a custom
  file row rendering.



  # Demo

  The following demo component does custom rendering for both the control and list items. Note that the file uploads
  actually happen progressively as you add them, but using tempids. The submit button submits the form data,
  but the files will already be on the server. The submission is about you recording the files.
  On submit you would persist the record of the files to a database and (optionally) remap the tempids to real ids.
  "
  (dc/mkdn-pprint-source FileUploadDemo))

(defonce upload-networking (file-upload-networking))

(defcard-fulcro form-file-upload
  "
  This card is full-stack, and uses a special server. The separate server is not necessary, but
  it makes it clearer to the reader what is related to file upload. The server-side code is in `upload_server.clj`.

  You can start the server for these demos at a CLJ REPL:

  ```
  $ lein repl
  user=> (run-upload-server)
  ```

  or with the shell script `run-file-upload-server.sh`.

  The server for these examples is on port 8085, so use this page via
  [http://localhost:8085/guide.html#!/fulcro_devguide.O15_Forms_File_Upload](http://localhost:8085/guide.html).
  "
  DemoRoot
  {}
  {:inspect-data false
   :fulcro       {:started-callback (fn [{:keys [reconciler]}] (fu/install-reconciler! upload-networking reconciler))
                  :networking       {:remote      (net/make-fulcro-network "/api" :global-error-callback identity)
                                     :file-upload upload-networking}}})
