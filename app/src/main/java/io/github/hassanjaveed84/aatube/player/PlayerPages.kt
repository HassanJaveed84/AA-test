package io.github.hassanjaveed84.aatube.player

import io.github.hassanjaveed84.aatube.core.AspectFit
import io.github.hassanjaveed84.aatube.core.PlayerError
import io.github.hassanjaveed84.aatube.core.PlayerStatus
import io.github.hassanjaveed84.aatube.core.VideoRef

/**
 * The web pages the car-screen WebView shows. Every page exposes the same
 * small `window.aatube` API to native code (play, pause, setBlocked, back,
 * arrows) and reports back through the `AATube` JavaScript interface
 * (onState, onError, onStats), which only accepts passive status reports.
 */
object PlayerPages {

    const val BRIDGE_NAME = "AATube"
    const val TV_URL = "https://www.youtube.com/tv"
    const val TEST_PATTERN_URL = "file:///android_asset/testpattern.html"

    /** Smart-TV user agent so youtube.com/tv serves the leanback interface. */
    const val TV_USER_AGENT =
        "Mozilla/5.0 (SMART-TV; Linux; Tizen 5.5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/5.5 TV Safari/537.36"

    /** YouTube's TV UI is laid out for a 1280px-wide screen. */
    const val TV_LOGICAL_WIDTH = 1280

    /**
     * YouTube's embedded player rejects requests without an identifying
     * Referer (error 153). The page is therefore loaded with
     * loadDataWithBaseURL from an https URL naming the app.
     */
    fun directBaseUrl(applicationId: String): String = "https://$applicationId/"

    fun tvUrl(video: VideoRef?): String =
        if (video == null) TV_URL else "$TV_URL#/watch?v=${video.id}"

    fun directHtml(video: VideoRef, surfaceWidth: Int, surfaceHeight: Int, blocked: Boolean): String {
        require(Regex("^[A-Za-z0-9_-]{11}$").matches(video.id)) { "invalid video id" }
        val box = AspectFit.fit(16, 9, surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
        fun pct(part: Int, whole: Int) = "%.3f%%".format(java.util.Locale.ROOT, part * 100.0 / whole.coerceAtLeast(1))
        return """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
html,body{margin:0;height:100%;background:#000;overflow:hidden}
#box{position:absolute;left:${pct(box.x, surfaceWidth)};top:${pct(box.y, surfaceHeight)};width:${pct(box.width, surfaceWidth)};height:${pct(box.height, surfaceHeight)}}
#p{width:100%;height:100%}
</style>
</head><body><div id="box"><div id="p"></div></div>
<script>
var blocked = $blocked, player = null, lastT = 0;
function report(fn, a) { try { $BRIDGE_NAME[fn](a); } catch (e) {} }
var tag = document.createElement('script');
tag.src = 'https://www.youtube.com/iframe_api';
tag.onerror = function () { report('onError', -1); };
document.head.appendChild(tag);
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%', videoId: '${video.id}',
    playerVars: { playsinline: 1, autoplay: 0, start: ${video.startSeconds}, rel: 0, origin: location.origin },
    events: {
      onReady: function () { report('onState', -2); if (blocked) { player.mute(); } else { player.playVideo(); } },
      onStateChange: function (e) {
        if (blocked && (e.data === 1 || e.data === 3)) { player.pauseVideo(); }
        report('onState', e.data);
      },
      onError: function (e) { report('onError', e.data); }
    }
  });
}
window.aatube = {
  play: function () { if (!blocked && player) player.playVideo(); },
  pause: function () { if (player) player.pauseVideo(); },
  setBlocked: function (b) {
    blocked = b;
    if (!player || !player.pauseVideo) return;
    if (b) { player.pauseVideo(); player.mute(); } else { player.unMute(); }
  },
  back: function () {},
  arrows: function () {}
};
setInterval(function () {
  if (blocked && player && player.getPlayerState && player.getPlayerState() === 1) player.pauseVideo();
  if (!player || !player.getCurrentTime) return;
  var t = player.getCurrentTime();
  report('onStats', JSON.stringify({ mode: 'direct', mediaTime: t, advanced: +(t - lastT).toFixed(3),
    state: player.getPlayerState(), muted: player.isMuted(), blocked: blocked,
    visibility: document.visibilityState }));
  lastT = t;
}, 1000);
</script>
</body></html>
""".trimIndent()
    }

    /**
     * Installed into youtube.com/tv at document start (and again when the
     * page finishes loading). While blocked it pauses and mutes every video
     * element the moment it tries to play, including a video cast from a
     * passenger's phone.
     */
    val TV_CONTROL_SCRIPT = """
(function () {
  if (window.aatube && window.aatube.tv) return;
  var blocked = window.__aatubeBlocked === true;
  function vids() { return Array.prototype.slice.call(document.querySelectorAll('video')); }
  function report(fn, a) { try { $BRIDGE_NAME[fn](a); } catch (e) {} }
  function hold(v) { if (!v.paused) v.pause(); v.muted = true; }
  document.addEventListener('play', function (e) { if (blocked && e.target.pause) hold(e.target); }, true);
  document.addEventListener('playing', function (e) { if (blocked) { hold(e.target); return; } report('onState', 1); }, true);
  document.addEventListener('pause', function () { report('onState', 2); }, true);
  document.addEventListener('waiting', function () { report('onState', 3); }, true);
  document.addEventListener('ended', function () { report('onState', 0); }, true);
  var frames = 0, lastFrames = 0, lastT = 0, hooked = new WeakSet();
  function hook(v) {
    if (hooked.has(v) || !v.requestVideoFrameCallback) return;
    hooked.add(v);
    var cb = function (now, meta) { frames = meta.presentedFrames; v.requestVideoFrameCallback(cb); };
    v.requestVideoFrameCallback(cb);
  }
  setInterval(function () {
    if (blocked) vids().forEach(hold);
    var v = vids()[0];
    if (!v) return;
    hook(v);
    report('onStats', JSON.stringify({ mode: 'tv', presentedFrames: frames, fps: frames - lastFrames,
      mediaTime: v.currentTime, advanced: +(v.currentTime - lastT).toFixed(3), paused: v.paused,
      muted: v.muted, blocked: blocked, visibility: document.visibilityState }));
    lastFrames = frames; lastT = v.currentTime;
  }, 1000);
  function key(type, name, code, target) {
    var e = new KeyboardEvent(type, { bubbles: true, cancelable: true, key: name, code: name });
    Object.defineProperty(e, 'keyCode', { get: function () { return code; } });
    Object.defineProperty(e, 'which', { get: function () { return code; } });
    target.dispatchEvent(e);
  }
  window.aatube = {
    tv: true,
    play: function () { if (!blocked) vids().forEach(function (v) { v.play(); }); },
    pause: function () { vids().forEach(function (v) { v.pause(); }); },
    setBlocked: function (b) {
      blocked = b; window.__aatubeBlocked = b;
      vids().forEach(function (v) { if (b) hold(v); else v.muted = false; });
    },
    // The TV UI routes navigation itself; back is the remote's back key:
    // Escape (27), webOS (461) and Tizen (10009).
    back: function () {
      [27, 461, 10009].forEach(function (c) {
        key('keydown', 'Escape', c, document); key('keyup', 'Escape', c, document);
      });
    },
    arrows: function (list) {
      var t = document.activeElement || document.body;
      list.forEach(function (k) { key('keydown', k[0], k[1], t); key('keyup', k[0], k[1], t); });
    }
  };
})();
""".trimIndent()

    /** Native call into the page, guarded for pages where the API is missing. */
    fun call(expression: String): String = "(function(){if(window.aatube){window.aatube.$expression;}})();"

    fun setBlockedJs(blocked: Boolean): String =
        "window.__aatubeBlocked=$blocked;" + call("setBlocked($blocked)")

    /** IFrame API and TV video-element states -> player status. */
    fun statusFromState(state: Int, blocked: Boolean): PlayerStatus? = when (state) {
        -1 -> PlayerStatus.Loading
        0 -> PlayerStatus.Ended
        1 -> PlayerStatus.Playing
        2, 5 -> PlayerStatus.Paused(byGate = blocked)
        3 -> PlayerStatus.Buffering
        -2 -> if (blocked) PlayerStatus.Paused(byGate = true) else null
        else -> null
    }

    fun errorFromCode(code: Int): PlayerError =
        if (code == -1) PlayerError.NETWORK else PlayerError.fromIframeCode(code)
}
