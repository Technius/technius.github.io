BASE_URL := $(shell cat config.toml | grep baseurl | cut -d ' ' -f 3)

ORIGIN_URL := $(shell git config remote.origin.url)

.PHONY: build publish pull-public clean
build:
	@echo $(BASE_URL)
	@git submodule init
	@git submodule update
	@hugo

publish: pull-public build
	cd public && \
		git add -A && \
		git commit -am "Update site" && \
		git push site gh-pages

pull-public: public
	cd public && git pull site gh-pages && git checkout gh-pages

public:
	mkdir -p public
	git clone $(ORIGIN_URL) public -b gh-pages

clean:
	@rm -rf public
