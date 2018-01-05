# Project Status

## Status

The most current information on status is available in the top-level
change log. This list covers some of the highlights to give you an
idea of what features are core and solid, and which ones are still evolving:

## Core Features

Fulcro is production-ready, and a number of companies are actively using it. Some of
the newer features still have some rough edges.

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

This is in active (priority) development. It should be relatively stable, but might
have missing features that you need. File upload works, but
needs some CSS love or other instructions for how to make it look good.

A simple state management version of forms is also planned (60% but on a branch currently).

- Components as user-editable forms (100%)
- Commit/submit (100%)
- Validation (100%)
- Custom "form input" rendering (90%)
- File Upload (80%)

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

Working and in use in production applications. Could probably use a
little bit more documentation and a bit of refactoring.

## Server-Side

There are two ways to build a server. The easy way assumes you don't need
to muck with the Ring handler much. It is fully extensible, but the
extension points are a bit messy.

The more recent modular server allows you to build your own Ring
middleware stack, and is therefore a bit easier to understand (though
more work). The modular nature of the new server also enables 3rd party
libraries that can hook into your server-side query/mutation logic.

Both are in good shape, and well-documented.

- Easy Server (100%)
- Module-based Server (100%)

See the Developer's Guide for instructions on rolling your own
server that isn't dependent on either.

## Twitter Bootstrap Support

In order to facilitate very rapid application development there is a
Bootstrap 3 namespace that includes wrappers that can generate the
correct DOM with classes needed to set up a UI. You must include your own
bootstrap CSS and theme in your HTML page.

- CSS (95%)
- Components (90%)
- Active Components (80%)
- Integration with Forms (50%)

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

## Random Bits

Some of these will probably get moved with the bootstrap support. Most
of these are UI-only concerns.

- `events.cljs`: Has helpers for detecting a few kinds of key events. Could be dramatically expanded.
- `icons.cljc` : Has embedded SVG data for generating nice SVG icons from the Material Icons set with needing CSS or other code.
- `clip_tool.cljs` and `clip_geometry.cljs` : Experimental component for doing live image clipping with locked aspect ratios. Needs CSS love, and instructions.
- `entities.cljc` : Simple defs in unicode of common HTML entities (since you can't type them in as `&blah;`). The ones that are there are correct. Many are missing.

