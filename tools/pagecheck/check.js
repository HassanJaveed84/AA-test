// Loads AA Tube's generated pages in Chromium with a stub AATube bridge and
// checks the gate behaviour of each page.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('fs');
const [pagesDir, assetsDir, outDir] = process.argv.slice(2);
const BRIDGE = `window.__reports=[];window.AATube={onState:function(s){__reports.push('state:'+s)},onError:function(c){__reports.push('error:'+c)},onStats:function(j){__reports.push('stats:'+j)}};`;
(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const result = {};
  const errors = [];
  // 1. Test pattern: frames advance while open, stop when blocked, play refused while blocked.
  {
    const ctx = await browser.newContext({ viewport: { width: 960, height: 360 } });
    await ctx.addInitScript(BRIDGE);
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push('testpattern: ' + e.message));
    await page.route('https://aatube.test/**', r => r.fulfill({ contentType: 'text/html', body: fs.readFileSync(assetsDir + '/testpattern.html', 'utf8') }));
    await page.goto('https://aatube.test/testpattern.html');
    await page.waitForTimeout(2500);
    const f1 = await page.evaluate(() => window.__reports.filter(r => r.startsWith('stats:')).pop());
    await page.evaluate(() => window.aatube.setBlocked(true));
    await page.waitForTimeout(1300);
    const a = await page.evaluate(() => JSON.parse(window.__reports.filter(r => r.startsWith('stats:')).pop().slice(6)));
    await page.waitForTimeout(2200);
    await page.evaluate(() => window.aatube.play());
    await page.waitForTimeout(1200);
    const b = await page.evaluate(() => JSON.parse(window.__reports.filter(r => r.startsWith('stats:')).pop().slice(6)));
    await page.screenshot({ path: outDir + '/testpattern-blocked.png' });
    await page.evaluate(() => { window.aatube.setBlocked(false); window.aatube.play(); });
    await page.waitForTimeout(2200);
    const c = await page.evaluate(() => JSON.parse(window.__reports.filter(r => r.startsWith('stats:')).pop().slice(6)));
    await page.screenshot({ path: outDir + '/testpattern-playing.png' });
    result.testPattern = { openStats: f1, blockedFramesStable: b.frames === a.frames, blockedFrames: [a.frames, b.frames], blockedPaused: b.paused && b.blocked, resumedFps: c.fps, resumedPaused: c.paused };
    await ctx.close();
  }
  // 2. Direct page: JS runs, reaches the IFrame API, reports state/errors through the bridge.
  for (const name of ['direct-open', 'direct-blocked']) {
    const ctx = await browser.newContext({ viewport: { width: 960, height: 360 } });
    await ctx.addInitScript(BRIDGE);
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push(name + ': ' + e.message));
    await page.route('https://io.github.hassanjaveed84.aatube.lab/**', r => r.fulfill({ contentType: 'text/html', body: fs.readFileSync(pagesDir + '/' + name + '.html', 'utf8') }));
    await page.goto('https://io.github.hassanjaveed84.aatube.lab/');
    await page.waitForTimeout(15000);
    result[name] = await page.evaluate(() => window.__reports.filter(r => !r.startsWith('stats:')).concat(window.__reports.filter(r => r.startsWith('stats:')).slice(-1)));
    await page.screenshot({ path: outDir + '/' + name + '.png' });
    await ctx.close();
  }
  // 3. TV control script on youtube.com/tv: installs, and a video that starts while blocked is held.
  {
    const TV_UA = 'Mozilla/5.0 (SMART-TV; Linux; Tizen 5.5) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.5 TV Safari/537.36';
    const ctx = await browser.newContext({ userAgent: TV_UA, viewport: { width: 1280, height: 720 } });
    await ctx.addInitScript(BRIDGE + 'window.__aatubeBlocked=true;' + fs.readFileSync(pagesDir + '/tv-control.js', 'utf8'));
    const page = await ctx.newPage();
    page.on('pageerror', e => errors.push('tv: ' + e.message));
    await page.goto('https://www.youtube.com/tv', { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForTimeout(8000);
    // Simulate a cast arriving while blocked: an unprotected local media element starts playing.
    const held = await page.evaluate(async () => {
      const v = document.createElement('video');
      v.muted = false; v.loop = true;
      const c = document.createElement('canvas'); c.width = 64; c.height = 36;
      const g = c.getContext('2d'); let n = 0;
      setInterval(() => { g.fillStyle = n++ % 2 ? '#f00' : '#00f'; g.fillRect(0, 0, 64, 36); }, 30);
      v.srcObject = c.captureStream(30);
      document.body.appendChild(v);
      try { await v.play(); } catch (e) {}
      await new Promise(r => setTimeout(r, 1500));
      const whileBlocked = { paused: v.paused, muted: v.muted };
      window.aatube.setBlocked(false);
      try { await v.play(); } catch (e) {}
      await new Promise(r => setTimeout(r, 1500));
      return { installed: !!(window.aatube && window.aatube.tv), whileBlocked, afterUnblock: { paused: v.paused, muted: v.muted } };
    });
    result.tv = held;
    await ctx.close();
  }
  result.pageErrors = errors;
  console.log(JSON.stringify(result, null, 2));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
