BASE_URL := $(shell cat config.toml | grep baseurl | cut -d ' ' -f 3)

SUBTREE_ARGS := --prefix=public git@github.com:Technius/blog.git gh-pages

.PHONY: build publish pull-public
build:
	echo $(BASE_URL)
	@git submodule init
	@git submodule update
	@hugo

publish: pull-public build
	git subtree push $(SUBTREE_ARGS)
	echo "TODO"

# For avoiding merge conflicts w/ publish
pull-public:
	git subtree pull $(SUBTREE_ARGS)
