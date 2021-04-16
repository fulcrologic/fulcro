(ns com.fulcrologic.fulcro.networking.file-upload
  "Client-side middleware that can be used with HTTP remotes so that mutations can attach file uploads to mutation
   parameters."
  (:require
    [edn-query-language.core :as eql]
    [com.fulcrologic.guardrails.core :refer [>defn => >def]]
    [com.fulcrologic.fulcro.algorithms.transit :as t]
    [com.fulcrologic.fulcro.networking.http-remote :as http]
    [taoensso.timbre :as log]))

(defn new-upload
  "Create a new upload object from a string name and a js object (Blob, ArrayBuffer, or File). The resulting map is
  safe to store in app state.

  See `attach-uploads`."
  [name content]
  {:file/name    name
   :file/content (with-meta {} {:js-value content})})

(defn evt->uploads
  "Converts a file input onChange event into a sequence upload objects that are compatible with `attach-uploads`."
  [file-input-change-event]
  (let [js-file-list (.. file-input-change-event -target -files)]
    (mapv (fn [file-idx]
            (let [js-file (.item js-file-list file-idx)
                  name    (.-name js-file)]
              (new-upload name js-file)))
      (range (.-length js-file-list)))))

(defn attach-uploads
  "Attach js Blob or ArrayBuffer objects to the `params`. This requires that you use `http-remote` and that you
   also install `wrap-file-upload` middleware. If you use js/File objects then the filenames of those objects
   will be available to the mutations on the server.

   Example usage:

   ```
   (let [uploads [(file-upload/new-upload \"test\" some-js-file)
                  (file-upload/new-upload \"other\" other-js-file)]]
     (comp/transact! this [(some-mutation (attach-uploads {} uploads))]))
   ```

   If you are using a browser file input, you can use `evt->uploads`:

   ```
   (dom/input {:type \"file\"
               :multiple true
               :onChange (fn [evt]
                           (let [uploads (file-upload/evt->uploads evt)]
                             (comp/transact! this [(some-mutation (file-upload/attach-uploads {} uploads))])))})
   ```
   "
  [params objects-to-upload]
  (assoc params ::uploads objects-to-upload))

(defn- has-uploads? [req]
  (let [mutations            (some-> req :body eql/query->ast :children)
        mutation-with-upload (some (fn [{:keys [params]}]
                                     (contains? params ::uploads)) mutations)]
    (boolean mutation-with-upload)))

(defn- js-value->uploadable-object
  "Coerce the js object into a blob to ensure it can be uploaded."
  [v]
  (if (instance? js/Blob v)
    v
    (js/Blob. #js [v])))

(defn wrap-file-upload
  "Adds support for attaching uploads to the parameters of any mutation.

   `transit-options` - A map of options to be included when converting the mutation and params for transmission. See
                       `transit/transit-clj->str`. Use this to extend the transit support. This is necessary because
                       the regular request middleware will not be used to send transactions that include file uploads,
                       so any extensions to transit must be done in both places.

   NOTE: This middleware acts as the end of the chain when it detects the need for a file upload, and rewrites the body,
   method, and clears any content-type header. As such, it should be used in the middleware so that it will be executed
   first:

   ```
   (def client-middleware
     (->
       (net/wrap-fulcro-request)
       (file-upload/wrap-file-upload)
       ...))
   ```
   "
  ([handler]
   (wrap-file-upload handler {}))
  ([handler transit-options]
   (fn [req]
     (if (has-uploads? req)
       (try
         (let [[body response-type] (http/desired-response-type req)
               ast                  (eql/query->ast body)
               ast-to-send          (update ast :children #(mapv (fn [n] (update n :params dissoc ::uploads)) %))
               txn                  (eql/ast->query ast-to-send)
               form                 (js/FormData.)]
           (.append form "upload-transaction" (t/transit-clj->str txn (assoc transit-options :metadata? false)))
           (doseq [{:keys [dispatch-key params]} (:children ast)]
             (when-let [uploads (::uploads params)]
               (doseq [{:file/keys [name content]} uploads]
                 (let [name-with-mutation (str dispatch-key "|" name)
                       js-value           (-> content meta :js-value)
                       content            (some-> js-value js-value->uploadable-object)]
                   (.append form "files" content name-with-mutation)))))
           (-> req
             (assoc :body form :method :post :response-type response-type)
             (update :headers dissoc "Content-Type")))
         (catch :default e
           (log/error e "Exception while converting mutation with file uploads. See https://book.fulcrologic.com/#err-fu-mut-convert-exc")
           {:body nil
            :method :post}))
       (handler req)))))
