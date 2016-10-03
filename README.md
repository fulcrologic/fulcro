# Untangled Server

This is a library that adds server-side support for Untangled. See the TodoMVC application or the Untangled
Tutorial for details.

[![Clojars
Project](https://img.shields.io/clojars/v/navis/untangled-server.svg)](https://clojars.org/navis/untangled-server)

Release: [![Master](https://api.travis-ci.org/untangled-web/untangled-server.svg?branch=master)](https://github.com/untangled-web/untangled-server/tree/master)

Snapshot: [![SNAPSHOT](https://api.travis-ci.org/untangled-web/untangled-server.svg?branch=develop)](https://github.com/untangled-web/untangled-server/tree/develop)

## Features

- Configurable configuration component that supports: a defaults.edn file, a path to a config edn file for merging into the defaults, and support for environmental variable access.
- A pluggable ring handler middleware stack for injecting your own middleware as needed.
- Provides access to the underlying [stuartsierra component system](https://github.com/stuartsierra/component) for injecting your own components.
- Lets you write your own api routes using a thin wrapper around [bidi](https://github.com/juxt/bidi)

### Configuration

First, you need to have `defaults.edn` file in your `:resources-path`, this will always be the base configuration data, so make sure the default values are safe for production.
Next you will need to set your `:config-path` in `untangled-make-server` to be a reasonable value for development and potentially production. Note: this can be parameterized, so you can do things like have different paths in development and production.

Example [system.clj](https://github.com/untangled-web/untangled-todomvc/blob/master/src/server/todomvc/system.clj#L15) & [defaults.edn](https://github.com/untangled-web/untangled-todomvc/blob/master/resources/config/defaults.edn) from untangled-todomvc.

### Component Injection

To inject components into the untangled-server's system, add a `:components` map to `make-untangled-server` with keys being the name of the component in your `:parser-injections`, and the value being the component you created with `defrecord` or some function to inject depencies with `component/using`.
Eg:
```
(declare build-hooks)

(defrecord Database [conn]
  component/Lifecycle
  (start [this] ...)
  (stop [this] ...))
  
(core/make-untangled-server
  ...
  :components {:db (map->Database {})
               :hooks (build-hooks)})
```
see [Ring Handler Injection](https://github.com/untangled-web/untangled-server/tree/feature/documentation#ring-handler-injection) for the `build-hooks` implementation

### Ring Handler Injection

NOTE: May be subject to change/improvement

There are two locations in untangled-server's pre-built handler stack, [pre-hook](https://github.com/untangled-web/untangled-server/blob/8dba26aafe36a5f0dab36d0dc89a98f43212df1d/src/untangled/server/impl/components/handler.clj#L176) and [fallback-hook](https://github.com/untangled-web/untangled-server/blob/8dba26aafe36a5f0dab36d0dc89a98f43212df1d/src/untangled/server/impl/components/handler.clj#L170), that are made publically accessible.
The first step is to create a component that depends (`component/using`) on the `:handler`, and then on start to get and set the desired hook.
```
(defrecord Hooks [handler]
  component/Lifecycle
  (start [this]
    (let [pre-hook (h/get-pre-hook handler)]
      (h/set-pre-hook! handler
        (comp
          ... your-wrap-handlers-here ...
          pre-hook 
          ...or-here...)))))
(defn build-hooks []
  (component/using
    (map->Hooks {})
    [:handler]))
```

### API Routes

Simply add an :extra-routes map to `make-untangled-server` with keys `:routes` and `:handlers`.
`:routes` contains a [bidi](https://github.com/juxt/bidi) mapping from url route to a key in the `:handlers` map.
`:handlers` is a mapping from handler key (from `:routes`) to a function (fn [env match] ... res)

Eg:
```
(make-untangled-server
  :extra-routes 
  {:routes ["" {"/store-file" :store-file}]
   :handlers {:store-file (fn [env match] (store-file (:db env) (get-in env [:request :body]))))})
```

## License

The MIT License (MIT) Copyright © 2016 NAVIS
