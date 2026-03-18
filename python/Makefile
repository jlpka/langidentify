.PHONY: models models-lite models-full clean test install install-full

# Copy model data for both packages
models: models-lite models-full

models-lite:
	$(MAKE) -C core models-lite

models-full:
	$(MAKE) -C core models-full
	$(MAKE) -C full-model models

clean:
	$(MAKE) -C core clean
	$(MAKE) -C full-model clean

install: models-lite
	pip install -e core

install-full: models
	pip install -e core -e full-model

test: models-lite
	cd core && python3 -m pytest tests/ -v
