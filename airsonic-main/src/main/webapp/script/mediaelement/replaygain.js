MediaElementPlayer = (function(cls) {
  const DEFAULT_VOLUME_MULTIPLIER = 0.7;
  const CACHE_ENTRY_LIFETIME_DAYS = 7;

  var ReplayGainHelper = function() {
    /* cache with expiry */
    var AudioCacheLocalStorage = function() {
      const KEY_PREFIX = "rpg_";

      const d = window.localStorage;
      var now = function() {
        /* try to keep the keys small */
        return Math.floor(new Date().getTime() / (86400 * 1000));
      };

      var gc = function() {
        var toDelete = [];
        for (var i=0;i<d.length;i++) {
          var k = d.key(i);
          if (!k.startsWith(KEY_PREFIX)) {
            continue;
          }

          var t = d.getItem(k).split(" ");
          var expiry = parseInt(t[0]);
          if (now() <= expiry) {
            continue;
          }

          toDelete.push(k);
        }
        for (var i=0;i<toDelete.length;i++) {
          d.removeItem(toDelete[i]);
        }
      }
      this.get = function(k) {
        k = KEY_PREFIX + k;
        var v = d.getItem(k);
        if (v === null) {
          return null;
        }

        var t = v.split(" ");
        var expiry = parseInt(t[0]);
        if (now() > expiry) {
          d.removeItem(k);
          return null;
        }

        return parseFloat(t[1]);
      };

      this.set = function(k, v) {
        k = KEY_PREFIX + k;
        var expiry = now() + CACHE_ENTRY_LIFETIME_DAYS;
        d.setItem(k, expiry + " " + String(v).substring(0, 7)); /* small keys */
      };

      gc();
    };

    var cache = new AudioCacheLocalStorage();

    var extractReplay = function(tag) {
      var tags = tag.tags["TXXX"];
      if (!tags)
        return;

      for (var t in tags) {
        if (!tags.hasOwnProperty(t))
          continue;

        var tv = tags[t];
        if(tv.data === undefined || tv.data.user_description != "replaygain_track_gain")
          continue;

        var v = tv.data.data;
        return parseFloat(v.substring(v, v.length - 3));
      }
    }

    var activeRequest = 0;
    this.load = function(src, callback) {
      activeRequest++;
      var ourRequest = activeRequest;

      var a_e = document.createElement("a");
      a_e.href = src;

      var songId = new URLSearchParams(a_e.search).get("id");
      var cacheValue = cache.get(songId);
      if (cacheValue !== null) {
        console.log("replaygain value found in cache");
        callback(src, cacheValue);
        return;
      }

      console.log("replaygain value not found in cache, fetching from server...");

      new jsmediatags.Reader(a_e.href).setTagsToRead(["TXXX"]).read({
        onSuccess: function(tag) {
          if (ourRequest != activeRequest) {
            console.log("replaygain request cancelled");
            return;
          }

          var baseVolume;
          var rg = extractReplay(tag);
          if (rg !== undefined) {
            baseVolume = Math.pow(10, (rg) / 20);
            if (baseVolume < 0 || baseVolume > 1)
              baseVolume = -1;
          } else {
            console.log("replaygain tag not found or invalid");
            baseVolume = -1;
          }

          cache.set(songId, baseVolume);
          callback(src, baseVolume);
        },
        onError: function() {
          if (ourRequest != activeRequest) {
            console.log("replaygain request cancelled");
            return;
          }

          callback(value, -1);
        },
      });
    };
  };

  var phony = function() {
    cls.apply(this, arguments);

    var helper = new ReplayGainHelper();
    var real = this;

    var realLoad = real.load;
    var realPlay = real.play;
    var realSrcProperties = Object.getOwnPropertyDescriptor(cls.prototype, "src");
    var realCurrentTime = Object.getOwnPropertyDescriptor(cls.prototype, "currentTime");

    var loading, playAfterLoad, setCurrentTimeAfterLoad;
    var ctx, track, gainNode;
    var callback = function(src, baseVolume) {
      if (gainNode == null) {
        ctx = new AudioContext();
        track = ctx.createMediaElementSource(real.media.originalNode);
        gainNode = ctx.createGain();
        GAIN = gainNode;
        track.connect(gainNode).connect(ctx.destination);
      }

      console.log("replaygain value is: " + baseVolume);
      if (baseVolume == -1) {
        baseVolume = DEFAULT_VOLUME_MULTIPLIER;
      }
      gainNode.gain.value = baseVolume;

      realSrcProperties.set.call(real, src);
      realLoad.call(real);
      if (playAfterLoad) {
        realPlay.call(real);
      }
      if (setCurrentTimeAfterLoad != -1) {
        realCurrentTime.set.call(real, setCurrentTimeAfterLoad);
      }
      loading = false;
    };

    var activeSrc;

    /* monkeypatch load(), play(), .src, .currentTime */
    real.load = function() {
      loading = true;
      playAfterLoad = false;
      setCurrentTimeAfterLoad = -1;
      real.pause();
      helper.load(activeSrc, callback);
    };
    real.play = function() {
      if (loading) {
        playAfterLoad = true;
        return;
      }
      realPlay.call(real);
    };

    Object.defineProperty(real, "src", {
        get: function() {
          return activeSrc;
        },
        set: function(value) {
          activeSrc = value;
        }
    });

    Object.defineProperty(real, "currentTime", {
        get: function() {
          if (loading) {
            return setCurrentTimeAfterLoad == -1 ? 0 : setCurrentTimeAfterLoad;
          }
          return realCurrentTime.get.call(real);
        },
        set: function(value) {
          if (loading) {
            setCurrentTimeAfterLoad = value;
            return;
          }
          return realCurrentTime.set.call(real, value);
        }
    });
  }

  phony.prototype = Object.create(cls.prototype);
  return phony;
})(MediaElementPlayer);
