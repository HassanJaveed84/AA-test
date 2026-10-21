const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const out = process.argv[2];
const TV_UA = 'Mozilla/5.0 (SMART-TV; Linux; Tizen 5.5) AppleWebKit/537.36 (KHTML, like Gecko) Version/5.5 TV Safari/537.36';
const PAGE = (vid) => `<!doctype html><html><body style="margin:0;background:#000">
<div id="p"></div><script>
window.log=[];var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){window.player=new YT.Player('p',{width:854,height:480,videoId:'${vid}',
playerVars:{playsinline:1,autoplay:1,mute:1,origin:location.origin},
events:{onReady:e=>{log.push('ready');e.target.playVideo();},onStateChange:e=>log.push('state:'+e.data),onError:e=>log.push('error:'+e.data)}});}
</script></body></html>`;
(async () => {
  const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const res = {};
  // 1. youtube.com/tv with a smart-TV UA
  {
    const ctx = await browser.newContext({ userAgent: TV_UA, viewport: { width: 1280, height: 720 } });
    const page = await ctx.newPage();
    const r = await page.goto('https://www.youtube.com/tv', { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForTimeout(20000);
    res.tv = { status: r.status(), url: page.url(), title: await page.title(),
      ytlr: await page.evaluate(() => document.querySelectorAll('[class*=ytlr],ytlr-app,ytlr-surface-page').length),
      text: (await page.evaluate(() => document.body.innerText)).slice(0, 600) };
    await page.screenshot({ path: out + '/tv.png' });
    await ctx.close();
  }
  // 2. IFrame player API, page served from an https base URL (like WebView loadDataWithBaseURL)
  for (const [name, base] of [['iframe_https_base', 'https://com.example.aayoutube/'], ['iframe_no_referrer', null]]) {
    const ctx = await browser.newContext({ viewport: { width: 1000, height: 600 } });
    const page = await ctx.newPage();
    if (base) {
      await page.route(base + '**', route => route.fulfill({ contentType: 'text/html', body: PAGE('M7lc1UVf-VE') }));
      await page.goto(base + 'player.html');
    } else {
      await page.setContent(PAGE('M7lc1UVf-VE'));
    }
    await page.waitForTimeout(20000);
    res[name] = await page.evaluate(() => ({ log: window.log, t: window.player && window.player.getCurrentTime ? window.player.getCurrentTime() : null }));
    await page.screenshot({ path: out + '/' + name + '.png' });
    await ctx.close();
  }
  console.log(JSON.stringify(res, null, 2));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
