# Fulcro

Fulcro is a library for building full-stack webapps using the Clojure and Clojurescript programming languages.
It leverages Om Next and a number of other libraries to provide a strongly cohesive story that has many 
advantages over techniques, libraries, and frameworks you might have used before.

## Is Fulcro for me?

Evaluating tools for web development can be a monumental task. Fulcro is a
full-stack, batteries-included library that is aimed at making data-driven
applications for the web. It enables great local reasoning, easy production
support, and rapid development. Of course, it is hard to make such a
big decision on a simple list of "features", nor should you!

Each set of tools have particular strengths that may or may not be a good fit.
To help you evaluate it, you might want to:

- Read more about [the benefits of Fulcro](benefits.html)
- Clone and play with the [Fulcro Template](https://github.com/fulcrologic/fulcro-template) project.
- Clone and play with an Fulcro [TODOMVC](https://github.com/fulcro-web/fulcro-todomvc) (full-stack!).
- Understand [how Fulcro improves stock Om Next](vsom-next.html) (assumes you understand something about Om Next)
- Use the [Fulcro Evaluation Spreadsheet](evaluation.html)

## What does it look Like?

There is a [YouTube video playlist](https://www.youtube.com/playlist?list=PLVi9lDx-4C_T_gsmBQ_2gztvk6h_Usw6R) of relatively short videos that can help you get started quickly and often clarifies things that people struggle with.

If you just want to get a quick look of what working in Fulcro looks like you might check this one out:

- [Fulcro In The Large - Component Development](https://youtu.be/uxI2XVgdDBU?list=PLVi9lDx-4C_T_gsmBQ_2gztvk6h_Usw6R)

Those who prefer to read should *still* check out the videos at some point, but should then
proceed to a complete overview in the
[Getting Started](https://github.com/fulcrologic/fulcro/blob/develop/GettingStarted.adoc)
guide. Those that like videos are encouraged to also read this guide.

**NOTE**: As the library continues to evolve more refined ways of doing certain things may appear. We'll do
our best to update the materials when possible, but it would be really helpful for you to
file an issue if you notice something is outdated.

## Companion Projects

Fulcro has a few companion projects that you should know about:

- [Fulcro Spec](https://github.com/fulcrologic/fulcro-spec): A BDD testing library written in Untangoed that gives you powerful extensions and expressiveness over clj/cljs test. It can refresh and render test results in a browser for both client and server!
- [Fulcro CSS](https://github.com/fulcrologic/fulcro-css): A library for writing/co-locating CSS on UI components in Om Next and Fulcro.
- [Fulcro Template](https://github.com/fulcrologic/fulcro-template): A (forkable) repository that has a full-stack starter app, and includes a rename script.
- [Getting Started Files](https://github.com/fulcrologic/fulcro-getting-started): A repository that has the source code for the Getting Started Guide.
- [In The Large Files](https://github.com/fulcrologic/fulcro-in-the-large): A repository with the source code for the Fulcro In the Large videos.

## Testimonials

Karan Toor – [AdStage](https://www.adstage.io/) (commercial)

<blockquote>
Fulcro is the best web framework for using Lisp in 2016. It's for building robust applications and
solving difficult problems, not building a mediocre blog in a weekend. At AdStage it was
our first full stack Clojure project and it took 3 developers 4 months to deliver the
smoothest launch in our company's history.
</blockquote>

Wilker Lucio – [www.daveconservatoire.org](www.daveconservatoire.org) (open-source)

<blockquote>
Om.next provides a robust architecture for building your client applications, but dealing with it
directly can be cumbersome. With Fulcro all the plumbing is built-in, and I'm impressed on how
often my assumptions match with their implementation, this way I can focus on the specifics of the application.
I'm not using it on my job, but on an open-source side project, I got in touch with
the owner of www.daveconservatoire.org and we are re-building the UI entirely on Clojurescript.
</blockquote>

The [source](https://github.com/daveconservatoire/dcsite-cljs) and  [beta](http://beta.daveconservatoire.org) of this rewrite are now available!

Mitchel Kuijpers – [Atlas CRM](https://www.atlascrm.io/) (commercial)

<blockquote>
We started our product about 2 years ago, and we started with Clojure, Clojurescript and Datomic.
We built most of our product with re-frame which gave us some very nice tools to quickly build an application.
Then when our app started to grow and we got more “complexity” in our application, our REST
services were starting to get bigger and bigger and at some point REST
wasn’t really cutting it anymore. The lack of a good data model also led to
lot's of bugs and inefficiencies. We had some views which would fire off 4 separate
requests to just render a view, and a data model that was hard to keep in sync among UI components.
<br/>
<br/>
I started experimenting in my free time with Om Next and in a few weeks I ported the most complex
view of our application. I noticed that once we got something working it would not break easily, and
we decided that we liked the model of Om Next better because we felt more confident in the code.
<br/>
<br/>
Many of our bugs and inefficiencies were completely solved by the Om Next standard database format;
however, when we started porting more of our application we ran into
some problems because you have to invent a lot yourself,
and I remembered seeing something about Fulcro (which I had dismissed it at that time, because it
seemed too heavy/frameworky).
<br/>
<br/>
I started asking some questions in the Fulcro Slack channel
and started with only integrating some of the Fulcro client stuff. This resulted in deleting
a lot of our own code and getting a lot of stuff for free.
<br/>
<br/>
At this point we've also integrated Fulcro into our backend (which was actually pretty easy),
and use most of Fulcro: Fulcro-spec, server-side rendering, fulcro-web/om-css, i18n, and
the devcards integrations (a lot, our UX designer even started writing some UI stuff in there).
<br/>
<br/>
The best part is that we have a way easier time on-boarding new developers: they do
all of the tutorials and the Fulcro exercises and they are pretty much ready to go!
<br/>
<br/>
Choosing all of your own libraries and stuff seems great, but
investing in a more complete solution can really pay off.
</blockquote>

If you're using it on an open-source or commercial project, please let me know
and I'll be glad to include you in the list!

## Social Media???

You can follow [@fulcrofw](http://www.twitter.com/fulcrofw) on twitter.

## Contributing

Fulcro is an open-source project that is currently supported by people like you. If you
find it useful please consider contributions in any of the following forms:

- Reporting issues, ideally with a reproducible case
- Look for things that need testing or additions and do that. See [Status](status.html).
- Proofreading the website/documentation. 
- A pull request fixing a bug (see [CONTRIBUTING](https://github.com/fulcrologic/fulcro/blob/develop/CONTRIBUTING.md))
- Expansions via a pull request (please check in on Slack first).
- [Donations](fund.html): help feed the code monkey!

### Corporate Sponsors

Become a corporate sponsor! Open source is about freedom, not free beer. The software you're organization
depends on is critical to your success! The following companies recognize that value and have
made contributions to Fulcro.

<div style="text-align: center">
<a href="http://www.adstage.io"><img width="200" class="sponsor-img" src="adstage.png"></a>
</div>
