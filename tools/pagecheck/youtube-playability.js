// Captures YouTube's own playabilityStatus for the same videos in three
// contexts: IFrame embed (https base URL as Referer), embed page loaded
// directly, and the desktop watch page. Shows *why* playback is refused.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const IDS = (process.argv[2] || 'jNQXAC9IVRw,aqz-KE-bpKQ,M7lc1UVf-VE').split(',');
const PAGE = (vid) => `<!doctype html><html><body style="margin:0"><div id="p"></div><script>
window.log=[];var t=document.createElement('script');t.src='https://www.youtube.com/iframe_api';document.head.appendChild(t);
function onYouTubeIframeAPIReady(){new YT.Player('p',{width:640,height:360,videoId:'${vid}',playerVars:{playsinline:1,autoplay:1,mute:1,origin:location.origin},
events:{onReady:e=>{log.push('ready');e.target.playVideo();},onStateChange:e=>log.push('state:'+e.data),onError:e=>log.push('error:'+e.data)}});}
</script></body></html>`;
function summarize(json) {
  const p = json.playabilityStatus || {};
  const es = p.errorScreen && (p.errorScreen.playerErrorMessageRenderer || p.errorScreen.playerLegacyDesktopYpcOfferRenderer || {});
  const text = (r) => r ? (r.simpleText || (r.runs || []).map(x => x.text).join('')) : undefined;
  return { status: p.status, reason: p.reason, playableInEmbed: p.playableInEmbed,
    subreason: es ? text(es.subreason) : undefined, errReason: es ? text(es.reason) : undefined,
    hasStreams: !!(json.streamingData && (json.streamingData.adaptiveFormats || []).length) };
}
(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const out = {};
  for (const id of IDS) {
    out[id] = {};
    for (const ctxName of ['embed_iframe_api', 'embed_direct', 'watch_page']) {
      const ctx = await browser.newContext({ viewport: { width: 1000, height: 600 } });
      const page = await ctx.newPage();
      const captured = [];
      page.on('response', async (r) => {
        if (r.url().includes('/youtubei/v1/player')) {
          try { captured.push(summarize(await r.json())); } catch (e) { captured.push({ parseError: String(e) }); }
        }
      });
      try {
        if (ctxName === 'embed_iframe_api') {
          const base = 'https://io.github.hassanjaveed84.aatube.lab/';
          await page.route(base + '**', route => route.fulfill({ contentType: 'text/html', body: PAGE(id) }));
          await page.goto(base);
        } else if (ctxName === 'embed_direct') {
          await page.goto('https://www.youtube.com/embed/' + id, { waitUntil: 'domcontentloaded' });
          await page.waitForTimeout(3000);
          try { await page.click('.ytp-large-play-button', { timeout: 3000 }); } catch (e) {}
        } else {
          await page.goto('https://www.youtube.com/watch?v=' + id, { waitUntil: 'domcontentloaded' });
        }
        await page.waitForTimeout(12000);
        // The watch page and the embed page also inline the first player response.
        const inline = await page.evaluate(() => {
          try { return window.ytInitialPlayerResponse ? JSON.stringify(window.ytInitialPlayerResponse) : null; } catch (e) { return null; }
        });
        const frames = page.frames();
        let iframeLog = null;
        try { iframeLog = await page.evaluate(() => window.log || null); } catch (e) {}
        const video = await Promise.all(frames.map(f => f.evaluate(() => {
          const v = document.querySelector('video'); return v ? { t: v.currentTime, w: v.videoWidth, paused: v.paused } : null;
        }).catch(() => null)));
        out[id][ctxName] = { apiLog: iframeLog, player: captured, inline: inline ? summarize(JSON.parse(inline)) : null, video: video.filter(Boolean) };
      } catch (e) { out[id][ctxName] = { error: String(e).slice(0, 200) }; }
      await ctx.close();
    }
  }
  console.log(JSON.stringify(out, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
