tests:
	npm install
	npx shadow-cljs compile ci-tests
	npx karma start --single-run
	clojure -A:dev:provided:test:clj-tests

# gem install asciidoctor asciidoctor-diagram
# gem install coderay
docs/DevelopersGuide.html: DevelopersGuide.adoc
	asciidoctor -o docs/DevelopersGuide.html -b html5 -r asciidoctor-diagram DevelopersGuide.adoc

book: docs/DevelopersGuide.html

dev:
	clojure -A:dev:provided:test:clj-tests --watch --fail-fast 

bookdemos:
	rm -rf docs/js/book docs/js/book.js
	npm install
	shadow-cljs release book

publish: book
	rsync -av docs/DevelopersGuide.html linode:/usr/share/nginx/html/index.html

publish-all: book
	rsync -av docs/DevelopersGuide.html linode:/usr/share/nginx/html/index.html
	rsync -av docs/js/book/*.js linode:/usr/share/nginx/html/js/book/
	rsync -av docs/assets/img linode:/usr/share/nginx/html/assets/
