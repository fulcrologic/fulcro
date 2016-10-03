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

### Ring Handler Injection

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
