# Project Status

## Status

The most current information on status is available in the top-level
change log. This list covers some of the highlights to give you an
idea of what features are core and solid, and which ones are still evolving:

## Core Features

Fulcro is production-ready, and a number of companies are actively using it. 

- Client-side rendering (100%)
- Data Model (100%)
- Primary Networking (100%)

## Data Loading

The original library had a function called `load-data`. This function
was deprecated and replaced with `load`. Some of the documentation does
not do a great job of describing the overall philosophy around how
load works and is intended to be used because it evolved a bit over time.

Be sure to watch the [whiteboard discussion video](https://youtu.be/mT4jJHf929Q?list=PLVi9lDx-4C_T_gsmBQ_2gztvk6h_Usw6R) on YouTube!

## HTML5 Routing

The primitives to support this are well-defined in `routing.cljc`. There are a few gotchas, and
it is recommended that you carefully examine the [Fulcro Template](https://github.com/fulcrologic/fulcro-template)
to see an app in action.

Routing is also supported for server-side rendering (100%).

## Form Support

Forms are largely something you'll want to have a lot of control over. The `forms-state` namespace is 
particularly useful for managing a pristine vs. edited copy of state, along with minimal state diff tools.

There is also a `forms` namespace that goes a bit further, but is deprecated since it seemed
to be a bit of overreach. It includes:

- Components as user-editable forms (100%)
- Commit/submit (100%)
- Validation (100%)
- Custom "form input" rendering (90%)

## Documentation

There is quite a bit of documentation: A book, tutorial, cheat sheet, and
YouTube video series.

## Internationalization

The story is well defined, and the code is present. String extraction,
code generation (as namespaces and/or loadable modules) works.

- Core code (100%)
- String extraction (100%)
- Server-side rendering (100%)
- Dynamic Loading (100%)

## Websockets

Working and in use in production applications. 

## Server-Side

There are two ways to build a server. The easy way assumes you don't need
to muck with the Ring handler much. It is fully extensible, but the
extension points are a bit messy.

There is also just an API handler hook that you can use to hook into
any kind of custom server.  See the Developer's Guide for instructions on rolling your own
server.

- Easy Server (100%)

## Twitter Bootstrap Support

In order to facilitate very rapid application development there is a
Bootstrap 3 namespace that includes wrappers that can generate the
correct DOM with classes needed to set up a UI. You must include your own
bootstrap CSS and theme in your HTML page.

- CSS (95%)
- Components (90%)
- Active Components (80%)

This will probably be moved to an alternate project in the near future.

## Advanced Optimization (Closure)

Advanced optimization works well.

## Devcards

The `fulcro.cards` namespace includes `defcard-fulcro` macro that lets you
embed a full-stack Fulcro app within a card. 
Combine this with `ui-iframe` from elements to get the CSS and rendering fully encapsulated
in a card. See the Bootstrap devcards for examples.

## Server-Side Rendering

Server-side rendering is demonstrated on the `fulcro-template`. There are two versions
of this: a cljc-only rendering (which requires all components to be written in `cljc` files),
and a true isomorphic version that renders things with Nashorn via the 
advanced-optimized Javascript. The former is easier and more performant if
you write all of your components, but the latter is necessary if you
use external Javascript libraries. See the `nashorn` branch of the template
for a demonstration of the latter.

