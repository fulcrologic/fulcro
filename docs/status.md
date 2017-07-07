# Project Status

## Status

The most current information on status is available in the top-level
change log. This list covers some of the highlights to give you an
idea of what features are core and solid, and which ones are still evolving:

## Core Features

Untangled is production-ready, and a number of companies are actively using it. Some of
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
it is recommended that you carefully examine the new [Untangled Template](https://github.com/untangled-web/untangled-template).

Server-side rendering still needs to be checked and possibly augmented so that
you can pre-render the correct page.

## Form Support

This is in active (priority) development. It should be relatively stable, but might
have missing features that you need. File upload works, but
needs some CSS love or other instructions for how to make it look good.

- Components as user-editable forms (99%)
- Commit/submit (90%)
- Error Handling (70%)
- Validation (90%)
- Custom "form input" rendering (90%)
- File Upload (80%)

## Developer's Guide

The developer's guide is currently a little out-of-date. All of the
things it describes still work, but there are better ways of doing
some of them now.

## Internationalization

The story is well defined, and the code is present. The lein plugin
to do string extraction and module generation is outdated and possibly
broken. There are mostly easy workarounds for this, but it needs work.

Fortunately, this does not affect your ability to write programs with
i18n support, it just affects the ease in which you can generate
translations for other languages.

- Core code (100%)
- String extraction (80%)
- Dynamic Loading (80%)
- Server-side rendering (0%)

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

Both are in good shape. The modular server could use more examples and
documentation.

- Easy Server (100%)
- Module-based Server (95%)

## Twitter Bootstrap Support

In order to facilitate very rapid application development there is a
Bootstrap 3 namespace that includes wrappers that can generate the
correct DOM with classes needed to set up a UI. You must include your own
bootstrap CSS and theme in your HTML page.

- CSS (95%)
- Components (90%)
- Active Components (80%)
- Integration with Forms (50%)

## Advanced Optimization (Closure)

Advanced optimization should generally be something you test heavily
if you use it. The dead code elimination is nice, but it is sometimes
too aggressive. It is hard to test the advanced optimization feature
on every build, and your choice of Om version can also affect it,
so it is possible that this might break from time-to-time.

If you want to use advanced optimizations, please understand that you
should do more aggressive testing, and possibly follow version upgrades
a bit more slowly.

In general, "simple" optimizations will still get you a single file that is cacheable
by browsers. It is highly unlikely that this level of optimization will break things,
so as long as your web server does a good job with cache headers it really
should not be that big of a deal for your deployment size to be a little larger.


## Elements

A small library of useful React tools. It might get renamed, but
there isn't a lot in it. It currently has a couple of helpers for
writing components that have nested components. See the Bootstrap
code for the `defui` of `Modal` to see some examples.

One of the most useful elements is `ui-iframe`, which can be used
to drop content into an iframe. This is used, for example, to make
sure the correct CSS and top-level rendering can work within a devcard.

## Mix-ins (augmentation.clj)

The `augmentation.clj` namespace includes an EXPERIMENTAL mechanism for defining and
using reusable mix-ins. It sounded like a good idea at the time, but we've yet
to find compelling evidence that this is a "Good Idea". Could be removed
in future versions, but if you find a great use-case, let us know!

## Devcards

The `cards.cljc` namespace includes an `untangled-app` macro that lets you
embed a full-stack Untangled app within a card. It works, but has a few
small issues (one is that hot code reload restarts the app). Combine this
with `ui-iframe` from elements to get the CSS and rendering fully encapsulated
in a card. See the Bootstrap devcards in the Developer's Guide.

Once this gets solidified, this needs to move to the `devcards` project as
a contribution there.

## Server-Side Rendering

This is a relatively new feature, and some of the bits have not been well-tested.

We know that the general DOM rendering is fine, and rest-assured it is a first-class
feature; however,
also understand that you may run into an issue. Event though most everything has been
written to support SSR, these areas in particular have not been well-tested and
may need additional work:

- SSR of alternate pages that use HTML5 Routing
- Rendering a non-default language with Internationalization
- Pre-rendering a page that uses the forms support

## Random Bits

- `events.cljs`: Has helpers for detecting a few kinds of key events. Could be dramatically expanded.
- `icons.cljc` : Has embedded SVG data for generating nice SVG icons from the Material Icons set with needing CSS or other code.
- `clip_tool.cljs` and `clip_geometry.cljs` : Experimental component for doing live image clipping with locked aspect ratios. Needs CSS love, and instructions.
- `entities.cljc` : Simple defs in unicode of common HTML entities (since you can't type them in as `&blah;`). The ones that are there are correct. Many are missing.

