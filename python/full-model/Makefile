FULL_SRC = ../../models-full/src/main/resources/com/jlpka/langidentify/models/full
DATA_DST = src/langidentify_full_model/data

.PHONY: models clean install build

models:
	@echo "Copying full model data..."
	cp $(FULL_SRC)/ngrams-*.txt.gz $(DATA_DST)/
	cp $(FULL_SRC)/topwords-*.txt.gz $(DATA_DST)/
	cp $(FULL_SRC)/skipwords.txt $(DATA_DST)/

clean:
	rm -f $(DATA_DST)/ngrams-*.txt.gz $(DATA_DST)/topwords-*.txt.gz $(DATA_DST)/skipwords.txt

build: models
	@if ! ls $(DATA_DST)/ngrams-*.txt.gz >/dev/null 2>&1; then \
		echo "ERROR: No model data in $(DATA_DST). Run 'make models' first." >&2; \
		exit 1; \
	fi
	python3 -m build

install: models
	pip install .
