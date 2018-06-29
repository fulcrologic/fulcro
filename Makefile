tests:
	npm install
	lein doo chrome automated-tests once
	lein test-refresh :run-once

tutorial:
	rm -rf docs/js/[a-fh-z]* docs/js/goog docs/js/garden
	lein cljsbuild once tutorial-live

# gem install asciidoctor asciidoctor-diagram 
# gem install coderay
docs/DevelopersGuide.html: DevelopersGuide.adoc
	asciidoctor -o docs/DevelopersGuide.html -b html5 -r asciidoctor-diagram DevelopersGuide.adoc

book: docs/DevelopersGuide.html

bookdemos:
	rm -rf docs/js/book docs/js/book.js
	lein cljsbuild once book-live

publish: book
	rsync -av docs/DevelopersGuide.html linode:/usr/share/nginx/html/index.html
	rsync -av docs/js/book.js linode:/usr/share/nginx/html/js/
	rsync -av docs/js/book/*.js linode:/usr/share/nginx/html/js/book/
	rsync -av docs/assets/img linode:/usr/share/nginx/html/assets/
