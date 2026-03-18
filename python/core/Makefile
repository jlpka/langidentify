LITE_SRC = ../../models-lite/src/main/resources/com/jlpka/langidentify/models/lite
FULL_SRC = ../../models-full/src/main/resources/com/jlpka/langidentify/models/full
LITE_DST = src/langidentify/models/lite
FULL_DST = src/langidentify/models/full

.PHONY: models models-lite models-full clean test install build

# Copy both model variants into the package
models: models-lite models-full

models-lite:
	@echo "Copying lite model data..."
	cp $(LITE_SRC)/ngrams-*.txt.gz $(LITE_DST)/
	cp $(LITE_SRC)/topwords-*.txt.gz $(LITE_DST)/
	cp $(LITE_SRC)/skipwords.txt $(LITE_DST)/

models-full:
	@echo "Copying full model data..."
	cp $(FULL_SRC)/ngrams-*.txt.gz $(FULL_DST)/
	cp $(FULL_SRC)/topwords-*.txt.gz $(FULL_DST)/
	cp $(FULL_SRC)/skipwords.txt $(FULL_DST)/

clean:
	rm -f $(LITE_DST)/ngrams-*.txt.gz $(LITE_DST)/topwords-*.txt.gz $(LITE_DST)/skipwords.txt
	rm -f $(FULL_DST)/ngrams-*.txt.gz $(FULL_DST)/topwords-*.txt.gz $(FULL_DST)/skipwords.txt

build: models-lite
	@if ! ls $(LITE_DST)/ngrams-*.txt.gz >/dev/null 2>&1; then \
		echo "ERROR: No model data in $(LITE_DST). Run 'make models' first." >&2; \
		exit 1; \
	fi
	python3 -m build

install: models
	pip install -e ".[dev]"

test:
	python3 -m pytest tests/ -v
