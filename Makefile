tests:
	npm install
	lein doo chrome automated-tests once

guide:
	rm -rf docs/js/[a-fh-z]* docs/js/goog docs/js/garden
	lein with-profile production cljsbuild once devguide-live

# gem install asciidoctor asciidoctor-diagram 
# brew install coderay
docs/DevelopersGuide.html: DevelopersGuide.adoc
	asciidoctor -o docs/DevelopersGuide.html -b html5 -r asciidoctor-diagram DevelopersGuide.adoc

book: docs/DevelopersGuide.html

publish: book
	rsync -av docs/DevelopersGuide.html linode:/usr/share/nginx/html/index.html
	rsync -av docs/js/book.js linode:/usr/share/nginx/html/js/
	rsync -av docs/js/book/*.js linode:/usr/share/nginx/html/js/book/
	rsync -av docs/assets/img linode:/usr/share/nginx/html/assets/
