// Exercises AA Tube's shipped YouTube TV control script the way the app does:
// the script is installed at document start, the app polls
// window.aatube.nextStep() every 2.5 s and performs a *trusted* tap at the
// returned fraction of the view (the app uses a native MotionEvent), and
// re-applies the #/watch deep link when the page drops it.
//
// usage: node tv-flow.js <dir with tv-control.js> <videoId> [start-url] [blocked]
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('fs');
const [dir, videoId, startUrlArg, blockedArg] = process.argv.slice(2);
const blocked = blockedArg === 'blocked';
const TV_UA = 'Mozilla/5.0 (SMART-TV; Linux; Tizen 5.5) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.5 TV Safari/537.36';
const BRIDGE = `window.__reports=[];window.AATube={onState:function(s){__reports.push('state:'+s)},onError:function(c){__reports.push('error:'+c)},onStats:function(j){__reports.push(j)}};`;
(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const W = 1280, H = 720;
  const ctx = await browser.newContext({ userAgent: TV_UA, viewport: { width: W, height: H }, hasTouch: true });
  await ctx.addInitScript(BRIDGE + `window.__aatubeBlocked=${blocked};` + fs.readFileSync(dir + '/tv-control.js', 'utf8'));
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const t0 = Date.now();
  const startUrl = startUrlArg && startUrlArg !== '-' ? startUrlArg : 'https://www.youtube.com/tv#/watch?v=' + videoId;
  await page.goto(startUrl, { waitUntil: 'domcontentloaded', timeout: 60000 });
  const log = [];
  let done = false;
  for (let i = 0; i < 12 && !done; i++) {
    await page.waitForTimeout(2500);
    const step = JSON.parse(await page.evaluate(() => window.aatube && window.aatube.nextStep ? window.aatube.nextStep() : '{"action":"missing"}'));
    const entry = { t: Math.round((Date.now() - t0) / 1000), action: step.action, hash: step.hash, playing: step.playing };
    if (step.action === 'tap' && !blocked) {
      await page.touchscreen.tap(step.target.x * W, step.target.y * H);
      entry.tapped = step.target.label;
    } else if (step.action === 'none' && !(step.hash || '').startsWith('#/watch?v=' + videoId) && !blocked) {
      await page.evaluate((id) => { location.hash = '#/watch?v=' + id; }, videoId);
      entry.setHash = true;
    }
    if (step.action === 'none' && step.playing) done = true;
    log.push(entry);
  }
  await page.waitForTimeout(3000);
  const final = await page.evaluate(() => { const v = document.querySelector('video');
    return { hash: location.hash, t: v ? +v.currentTime.toFixed(2) : null, paused: v ? v.paused : null, muted: v ? v.muted : null, w: v ? v.videoWidth : null }; });
  const stats = await page.evaluate(() => window.__reports.filter(r => r[0] === '{').slice(-1)[0] || null);
  console.log(JSON.stringify({ startUrl: startUrl.replace(videoId, 'VIDEO'), blocked, log, final, lastStats: stats, pageErrors: errors,
    secondsToPlaying: done ? log[log.length - 1].t : null }, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
