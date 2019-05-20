# Contributing

If you'd like to submit a PR, please follow these general guidelines:

- Read through the current issues on Github. There might be something you can
help with!
- Either talk about it in Slack on #fulcro, or open a github issue
- Please use a github issue to go along with your PR.
- Do development against the *develop* branch (we use git flow). PRs should be directed at the develop branch. Master is
  the latest release, not the live development.
- In general, please squash your change into a single commit
- Add an entry to the CHANGELOG describing the change

## Git Flow on OSX

Please read about [Git Flow](http://nvie.com/posts/a-successful-git-branching-model/)

I use homebrew to install git flow extensions:

```bash
$ brew install git-flow-avh
```

and make sure my startup shell file (I use .bashrc) sources the completion file:

```
. /usr/local/etc/bash_completion.d/git-flow-completion.bash
```

There is also a plain `git-flow` package, but the AVH version is better maintained and has better hook support.

## Github Instructions

Basically follow the instructions here:

https://help.github.com/categories/collaborating-with-issues-and-pull-requests/

## General Guidelines

I need to rewrite these for this project.  Ping me on Slack.
