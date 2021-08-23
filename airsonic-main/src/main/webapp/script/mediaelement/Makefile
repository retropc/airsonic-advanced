TARGET=mediaelement-and-player.min.js

all: $(TARGET)

.PHONY: clean

$(TARGET): mediaelement-and-player.min-orig.js jsmediatags.js replaygain.js
	cat $^ > $@

clean:
	rm -f $(TARGET)
