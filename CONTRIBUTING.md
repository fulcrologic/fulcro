# Contributing

Thank you for taking an interest in the project and your consideration of contribution. Your gesture is highly appreciated! 

Fulcro is an open source (family of) projects and there are a few ways in which your contribution would 
make a great impact.

## Financial contribution

You can support Fulcro's development by considering a donation via the
[Github Sponsor's program](https://github.com/sponsors/awkay).

## Code contribution

As a living open-source project there's always a lot which could be done. If you are just starting out with the code-base then 
consider making good use of the online [video-series](https://www.youtube.com/user/tonythekay/videos) where you might find videos 
and tutorials in addition to the information regarding the development setup and various optimizations.
 
Also, feel free make use of the fact that most of the source code is in `CLJC` file which makes it possible to a fire up a REPL and 
explore things interactively.

The other piece of advice is that you should get familiar with the [fulcro-spec](https://github.com/fulcrologic/fulcro-spec) testing library,
which is used extensively in Fulcro.

### Github Instructions

Basically follow the instructions here:

https://help.github.com/categories/collaborating-with-issues-and-pull-requests/

### Guidelines for the PR

If you'd like to submit a PR, please follow these general guidelines:

- Read through the current (and closed) issues on Github. There might be something you can help with!
- In general, a PR is expected to be accompanied with test cases.
- Either talk about it in Slack on #fulcro, or open a github issue
- Please use a github issue to go along with your PR.
- Fulcro adheres to the single-commit-per-PR policy, therefore please squash your change into a single commit for it to be mergeable.
- Be sure to make a very detailed commit message. The first line should be a summary sentence, and then there should be
  a list of bullet items if more than one change was made.

### Guidelines for documentation

It's a good idea for a docstring to do more than the function name itself already does 
(e.g. `clear-js-timeout!` does not need a docstring that says "Clears a js timeout"...it adds no clarity).

For adding doc-strings, please consider the end-user (programmer) who will be using the function, and what they are going to want to know:

-   What are the arguments (i.e. allowed types, units, etc.). If an argument is named 
    `tm` for example, is that an inst?, and integer? Is it measured in seconds, minutes, milliseconds?
-   What is the return value?
-   Are there other functions that they should consider/look at?
-   Can some things be nil?
-   What are the error behaviors?
-   How might the function behave in surprising ways?
-   A small example is really useful, esp. if the function is pure

Also, try to format the docstring using markdown, since `cljdoc` and other tools support that. For example:

```
(defn set-js-timeout!
  "Create a timer that will call `f` after `tm` milliseconds.

   Returns an opaque value that can be passed to `clear-js-timeout!` to cancel the timer.

  See also `defer`, which is intended to be platform independent.
  "
  [tm f]
  ...)

```

Perhaps, it might not make sense to give an example usage for a small utility like the above, 
but for something like `integrate-ident` I certainly would recommend a more elaborate docstring. 

## Community Participation

Last but not the least, please connect with the friendly Fulcro community over at #fulcro channel in the `Clojurians` slack. 
This is a place for fulcro beginners and experts alike to hangout and share knowledge.

# Welcome to the Fulcro community!
