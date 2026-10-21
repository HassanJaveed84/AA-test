// Real YouTube TV playback, then the gate closes: the shipped script must
// pause and mute the video and keep it held; reopening must not resume it.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('fs');
const [dir, videoId] = process.argv.slice(2);
const TV_UA = 'Mozilla/5.0 (SMART-TV; Linux; Tizen 5.5) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.5 TV Safari/537.36';
(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const ctx = await browser.newContext({ userAgent: TV_UA, viewport: { width: 1280, height: 720 }, hasTouch: true });
  await ctx.addInitScript('window.AATube={onState(){},onError(){},onStats(){}};window.__aatubeBlocked=false;' + fs.readFileSync(dir + '/tv-control.js', 'utf8'));
  const page = await ctx.newPage();
  await page.goto('https://www.youtube.com/tv#/watch?v=' + videoId, { waitUntil: 'domcontentloaded', timeout: 60000 });
  for (let i = 0; i < 10; i++) {
    await page.waitForTimeout(2500);
    const s = JSON.parse(await page.evaluate(() => window.aatube.nextStep()));
    if (s.action === 'tap') await page.touchscreen.tap(s.target.x * 1280, s.target.y * 720);
    if (s.playing) break;
  }
  const v = () => page.evaluate(() => { const x = document.querySelector('video'); return { t: +x.currentTime.toFixed(2), paused: x.paused, muted: x.muted }; });
  const out = { playing: await v() };
  await page.evaluate(() => window.aatube.setBlocked(true));
  await page.waitForTimeout(500); out.blocked0_5s = await v();
  await page.waitForTimeout(3000); out.blocked3_5s = await v();
  // A play attempt while blocked (e.g. a passenger's cast or the TV UI's own autoplay).
  await page.evaluate(() => document.querySelector('video').play().catch(() => {}));
  await page.waitForTimeout(1500); out.afterPlayAttemptWhileBlocked = await v();
  await page.evaluate(() => window.aatube.setBlocked(false));
  await page.waitForTimeout(2500); out.unblockedNoAutoResume = await v();
  await page.evaluate(() => window.aatube.play());
  await page.waitForTimeout(2500); out.afterUserPlay = await v();
  console.log(JSON.stringify(out, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
