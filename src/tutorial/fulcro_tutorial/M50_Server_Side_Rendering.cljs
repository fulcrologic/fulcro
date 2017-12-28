(ns fulcro-tutorial.M50-Server-Side-Rendering
  (:require [devcards.core :as dc :refer-macros [defcard-doc]]))

(defcard-doc
  "# Server-side Rendering

   Single-page applications have a couple of concerns when it comes to how they behave on the internet at large.

   The first is size. It is not unusual for a SPA to be several megabytes in size. This means that users on
   slow networks may wait a while before they see anything useful. Network speeds are continually on the rise
   (from less than 1kbps in the 80's to an average of about 10Mbps today). This is becoming less and less of an issue,
   and if this is your only concern, then it might be poor accounting to complicate your application just to shave
   a few ms off the initial load. After all, with proper serving you can get their browser to cache the js file
   for all but the first load of your site.

   The second more important concern is SEO. If you have pages on your application that do not require login and you
   would like to have in search engines, then a blank HTML page with a javascript file isn't going to cut it.

   Fortunately, Fulcro has you covered! Server-side rendering not only works well in Fulcro, the central mechanisms of
   Fulcro (a client-side database with mutations) and the fact that you're writing in one language actually make
   server-side rendering shockingly simple, even for pages that require data from a server-side store! After all,
   all of the functions and UI component queries needed to normalize the tree that you already know how to generate on
   your server can be in CLJC files and can be used without writing anything new!

   ## Recommendations

   In order to get the most out of your code base when supporting server-side rendering, here are some general recommendations
   about how to write you application. These are pretty much what we recommend for all applications, but they're particularly
   important for SSR:

   ### Use Initial State on Components That Appear on Client Start

   This is true for every application. We always encourage it. It helps with refactoring, initial startup, etc. When
   doing server-side rendering you won't need this initial state on the client (the server will push a built-up state);
   however, the server does need the base minimum database on which it will build the state for the page that will
   be rendered.

   IMPORTANT: For SSR you will *move* initial state from *just* your `Root` node to a function. The reason for this
   is that Fulcro ignores explicit application state at startup if it can find initial state on the root node, and
   we want to force state into the client.

   This will become clearer when we get to examples.

   ### Write Mutations via Helper Functions on State Maps

   When writing a mutation, first write a helper function. Perhaps end it's name with `-impl` or even `*`. The
   helper function should take the application database state map (*not* atom) and any arguments needed to accomplish the
   task. It should then return the updated state map:

   ```
   ; in a CLJC file!
   (defn todo-set-checked-impl [state-map todo-id checked?]
      (assoc-in state-map [:todo/by-id todo-id :item/checked?] checked?))
   ```

   Then write your mutation using that function:

   ```
   ; CLJS-only, either separate file or conditionally in CLJC
   (defmutation todo-set-checked [{:keys [id checked?]}]
     (action [{:keys [state]}]
       (swap! state todo-set-checked-impl id checked?)))
   ```

   This has a very positive effect on your code: composition and re-use!

   ```
   ; composition
   (defmutation do-things [params]
     (action [{:keys [state]}]
       (swap! state (fn [s] (-> s
                                (do-thing-1-impl)
                                (do-thing-2-impl)
                                ...))))
   ```

   Of course composition is re-use, but now that your client db mutation implementation is available in clj and cljs you
   can use it to initialize state for your server-side render!

   NOTE: The new mutation `^:intern` support also gives you a way to get a function that applies the mutations actions
   on a state atom.

   ### Use HTML5 Routing

   You have to be able to detect the different pages via URL, and your application also needs to respond to them. As a
   result you will need to make all of the pages that are capable of server-side also have some distinct URI representation.

   ## Overall Structure

   Here's how to structure your application to support SSR:

   1. Follow the above recommendations for the base code
   2. The server will use the client initial app state plus a sequence of mutation implementations to build a normalized database
   3. The server will serve the same basic HTML page (from code) that has the following:
       - The CSS and other head-related stuff and the div on which your app will mount.
       - A div with the ID of your SPA's target mount, which will contain the HTML of the server-rendered application state
       - A script at the top that embeds the normalized db as a transit-encoded string on `js/window`.
       - A script tag at the bottom that loads your client SPA code
   4. The client will look for initial state via a var set on `js/window` (transit-encoded string) and start
   5. The client will do an initial render, which will cause react to hook up to the existing DOM

   ### Building the App State on the Server

   The `fulcro.server-render` namespace has a function called `build-initial-state` that takes the root component
   and an initial state tree. It normalizes this and plugs in any union branch data that was not in the tree itself
   (by walking the query and looking for components that have initial state and are union branches). It returns
   a *normalized* client application db that would be what you'd have on the client at initial startup if you'd
   started the client normally.

   So, now all you need to do is run any combination of operations on that map to bring it to the proper state. Here's the
   super-cool thing: your renderer is pure! It will render exactly what the state says the application is in!

   ```
   (let [base-state (ssr/build-initial-state (my-app/get-initial-state) my-app/Root)
         user       (get-current-user (:session req))
         user-ident (util/get-ident my-app/User user)]
      (-> base-state
        (todo-check-item-impl 3 true) ; some combo of mutation impls
        (assoc :current-user user-ident) ; put normalized user into root
        (assoc-in user-ident user)))
   ```

   So now you've got the client-side db on the server. Now all you need to do is pre-render it, and also
   get this generated state to the client!

   ### Rendering with Initial State

   Of course, the whole point is to pre-render the page. Now that you have a complete client database this is
   trivial:

   ```
   (let [props                (prim/db->tree (prim/get-query app/Root normalized-db normalized-db)
         root-factory         (prim/factory app/Root)]
     (dom/render-to-str (root-factory props)))
   ```

   will generate a string that contains the current HTML rendering of that database state!

   ### Send The Completed Package!

   Now, while you have the correct initial look, you will still need to get this database state into the client.
   While you could technically try loading your UI's initial state, it would make the UI flicker because when React
   mounts it needs to see the exact DOM that is already there. So, you must pass the server-side generated-database
   as initial-state to your client.
   The function `fulcro.server-render/initial-state->script-tag` will give you a `<script>` tag that includes
   a string-encoded EDN data structure (using transit).

   We now combine what we learned about generating the application's rendering with this to get the overall
   response from the server:

   ```
   (defn top-html [normalized-db root-component-class]
     (let [props                (db->tree (get-query root-component-class) normalized-db normalized-db)
           root-factory         (factory root-component-class)
           app-html             (dom/render-to-str (root-factory props))
           initial-state-script (ssr/initial-state->script-tag normalized-db)]
       (str \"<!DOCTYPE) html>\\n\"
         \"<html lang='en'>\\n\"
         \"<head>\\n\"
         \"<meta charset='UTF-8'>\\n\"
         \"<meta name='viewport' content='width=device-width, initial-scale=1'>\\n\"
         initial-state-script
         \"<title>Home Page</title>\\n\"
         \"</head>\\n\"
         \"<body>\\n\"
         \"<div id='app'>\"
         app-html
         \"</div>\\n\"
         \"<script src='js/app.js' type='text/javascript'></script>\\n\"
         \"</body>\\n\"
         \"</html>\\n\")))
   ```

   Now let's move on to the client.

   ### Client-Side – Use Initial State

   When creating your client, you will now be explicit about initial state and use a helper function (provided)
   to decode the server-sent state:

   ```
   (defonce app (atom (fc/new-fulcro-client :initial-state (fulcro.server-render/get-SSR-initial-state))))
   ```

   Of course, you could use `:started-callback` to do various other bits (like start your HTML5 routing), but this
   completes the essential pattern. No other client modifications need to be made!

   ## A Complete Working Example

   There is a complete working example of these techniques (including the HTML5 routing) in the
   [fulcro-template](https://github.com/fulcrologic/fulcro-template).

   - [Server-side logic](https://github.com/fulcrologic/fulcro-template/blob/master/src/main/fulcro_template/server.clj)
   - [HTML5 Routing](https://github.com/fulcrologic/fulcro-template/blob/master/src/main/fulcro_template/ui/html5_routing.cljc)
   - [Client-side Initial State Generation and Modifications](https://github.com/fulcrologic/fulcro-template/blob/master/src/main/fulcro_template/ui/root.cljc) (see `initial-app-state-tree`)
   - [Client Start-up](https://github.com/fulcrologic/fulcro-template/blob/master/src/main/fulcro_template/client.cljs) Note this example tolerates a failure of the server to send
   initial state, so it runs initial startup steps if it detects that.

  ## True Isomorphic Support with Nashorn

  If you use external Javascript libraries of React components then you have two choices. One: write wrappers in cljs
  that can output something reasonable for them in SSR, and them make cljc wrappers that use the JS when on the client,
  and your placeholder dom on the server. This can work very well, and is the recommended approach.

  If you're willing to work just a little bit harder then you can maintain a true isomorphic application using Java
  8 and the Nashorn ECMA scripting engine.

  Some notes:

  1. Server-side use of the compiled Javascript works best with advanced-optimized builds. Figwheel and other development
  builds of the javascript won't work right, and we've even seen problems with builds that don't use advanced optimizations.
  2. (1) means that your SSR is going to be hard to work with if you're in dev (figwheel) mode. The recommendation is that
  you test SSR as a separate step (where you can have an auto incremental build with advanced compilation). Figwheel hot loads
  code updates anyhow, so not having SSR when working in development mode isn't really a big loss.

  There is a `nashorn` branch on the github `fulcro-template` project that demonstrates the setup and rendering code.

  The basics are:

  - Start a Nashorn instance
  - Run a polyfill that defines console and global
  - Run the compiled script of your program to define everything
  - Use a render to string call to get the initial HTML output

  The polyfill is just:
  ```
  var global = this;

  var console = {};
  console.debug = print;
  console.warn = print;
  console.error = print;
  console.log = print;
  var usingNashorn = true;
  ```

  Client main (which does the mount) becomes:

  ```
  (when-not (exists? js/usingNashorn)
    (reset! app (core/mount @app root/Root \"app\")))
  ```

  Server rendering (client-side cljs) is:

  ```
  (def ui-root (prim/factory root/Root))

  (defn ^:export server-render [props-str]
    ; incoming data will come from JVM as transit-stringified EDN
    (if-let [props (some-> props-str util/transit-str->clj)]
      (js/ReactDOMServer.renderToString (ui-root props))
      (js/ReactDOMServer.renderToString (ui-root (prim/get-initial-state root/Root nil)))))
  ```

  And the server-side code holds a script engine in an atom or something (a component would be best), and
  accomplishes the rendering of a given tree of UI props via:

  ```
  (defn ^String nashorn-render
  [props]
  (try
    (start-nashorn)
    (let [string-props  (util/transit-clj->str props)
          script-engine ^NashornScriptEngine @nashorn
          ; look up the object that holds the defs of the server-render function (using js naming)
          namespc       (.eval script-engine \"namespace.of.server_render\")
          ; invoke the server-render function, and pass in a transit-stringified EDN version of props
          ; invokeMethod is varargs, which is what the into-array is about
          result        (.invokeMethod script-engine namespc \"server_render\" (into-array [string-props]))
          html          (String/valueOf result)]
      html)
    (catch ScriptException e
      (timbre/debug \"Server-side render failed. This is an expected error when not running from a production build with adv optimizations.\")
      (timbre/trace \"Rendering exception:\" e))))
  ```

  ")
