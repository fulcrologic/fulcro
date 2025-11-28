# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Fulcro is a library for building data-driven full-stack applications for web, native, and desktop (via Electron). It uses React and is written in Clojure and ClojureScript. The codebase is primarily CLJC (cross-compiled Clojure/ClojureScript) to maximize code sharing between JVM and JavaScript environments.

## Architecture Overview

### Core Namespaces

- **`com.fulcrologic.fulcro.application`** - Application creation, lifecycle, and state management
- **`com.fulcrologic.fulcro.components`** - Component definition, queries, and props management
- **`com.fulcrologic.fulcro.mutations`** - Transaction and mutation primitives
- **`com.fulcrologic.fulcro.data_fetch`** - Data loading and remote interaction

### Algorithm Modules (`algorithms/`)

The `com.fulcrologic.fulcro.algorithms` namespace contains the core algorithms that power Fulcro:

- **`normalize.cljc`** - Converts tree-structured data into normalized form (indexed by ident)
- **`denormalize.cljc`** - Reconstructs tree-structured props from normalized state
- **`merge.cljc`** - Merges server responses into client state
- **`indexing.cljc`** - Maintains indexes of components by class and ident
- **`tx_processing.cljc`** - Transaction queue and processing orchestration
- **`tx_processing/synchronous_tx_processing.cljc`** - Synchronous transaction processor (for testing)
- **`data_targeting.cljc`** - Helpers for targeting where data gets placed in app state
- **`form_state.cljc`** - Form state management and validation support
- **`tempid.cljc`** - Temporary ID generation and remapping

### DOM Rendering

- **`dom.cljs`** - ClojureScript DOM factory functions (React wrappers)
- **`dom.clj`** - Clojure macro definitions for CLJS DOM factories
- **`dom-server.clj`** - Server-side rendering (SSR) DOM factories. Produces `Element` records that can be:
  - Rendered to HTML strings via `render-to-str` (for SSR)
  - Converted to hiccup via `render-to-hiccup` (for headless testing)

### Headless Infrastructure (`headless/`)

The codebase includes a comprehensive headless framework at `com.fulcrologic.fulcro.headless` that enables synchronous execution of Fulcro applications on the JVM. This is particularly useful for testing.

**Key insight**: The headless framework uses `dom-server` for rendering, which means your CLJC files can use the same `dom-server` namespace for both SSR and headless testing:

```clojure
(ns app.ui
  (:require
    #?(:clj [com.fulcrologic.fulcro.dom-server :as dom]
       :cljs [com.fulcrologic.fulcro.dom :as dom])))
```

Key capabilities:
- **Synchronous transaction processing** - All operations complete before returning
- **Render frame capture** - Inspect before/after state with `render-frame!` and `last-frame`
- **Hiccup DOM rendering** - Preserved event handlers for interaction via `click-on!`, `type-into!`
- **Hiccup tree utilities** - `find-element-by-id`, `element-text`, `hiccup-attrs`, etc.
- **Controlled execution** - Step through transaction phases
- **Backend integration** - Ring handlers and Pathom parsers via loopback remotes

Main namespaces:
- `com.fulcrologic.fulcro.headless` - Core headless app creation, interaction, and hiccup utilities
- `com.fulcrologic.fulcro.headless.loopback-remotes` - Loopback remotes (Pathom, Ring, mock) that execute locally
- `com.fulcrologic.fulcro.headless.timers` - Mock timer control for deterministic testing

### Networking (`networking/`)

- **`http_remote.cljs`** - Standard HTTP remote for browser environments
- **`mock_server_remote.cljs`** - Mock server for browser that emulates a server.
