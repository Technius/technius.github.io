BASE_URL := $(shell cat config.toml | grep baseurl | cut -d ' ' -f 3)

ORIGIN_URL := $(shell git config remote.origin.url)

.PHONY: build publish pull-public clean
build:
	@echo $(BASE_URL)
	@git submodule init
	@git submodule update
	@hugo

pre-publish:
	cd public && \
		git reset HEAD && \
		git checkout -- .

publish: pull-public pre-publish build
	cd public && \
		git add -A && \
		git commit -am "Update site" && \
		git push origin master

pull-public: public
	cd public && git pull origin master && git checkout master

public:
	mkdir -p public
	git clone $(ORIGIN_URL) public -b master

clean:
	@rm -rf public
