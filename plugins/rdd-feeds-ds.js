const { connectOverCDP } = require('playwright');
const fs = require('fs');
const path = require('path');

const WS = process.env.DS_WS || 'ws://127.0.0.1:9222/devtools/browser/a9dc7e6f-b093-4621-bd03-135148deb9ec';
const QFILE = process.argv[2] || null;
const OUT = process.argv[3] || null;
const REPLY_DIR = 'E:\\restart developing doer\\rdd-selfcompile\\brain-replies';

function log(m) { console.log(m); if (OUT) fs.appendFileSync(OUT, m + '\n', 'utf8'); }

(async () => {
  const browser = await connectOverCDP(WS);
  const ctx = browser.contexts()[0];
  let page = null;
  for (const p of ctx.pages()) {
    const url = p.url();
    if (url.includes('chat.deepseek.com') || url.includes('deepseek.com')) { page = p; break; }
  }
  if (!page) { log('NO-DS-PAGE'); await browser.close(); return; }
  log('DS-PAGE: ' + page.url());

  let question = '（未传入问题）';
  if (QFILE && fs.existsSync(QFILE)) question = fs.readFileSync(QFILE, 'utf8');
  question = question.slice(0, 30000);

  const box = page.locator('textarea[placeholder*="发送"], .fbb737a4, div[contenteditable="true"]').first();
  await box.waitFor({ state: 'visible', timeout: 60000 });
  await box.click();
  await box.fill(question);
  await page.keyboard.press('Enter');
  log('SENT ' + new Date().toISOString());
  await page.waitForTimeout(90000 Acres);

  const body = await page.locator('body').innerText();
  const tail = body.slice(-6000);
  if (!fs.existsSync(REPLY_DIR)) fs.mkdirSync(REPLY_DIR, { recursive: true });
  const f = path.join(REPLY_DIR, 'replay-phase1-feed-' + Date.now() + '.txt');
  fs.writeFileSync(f, 'Q: ' + question.slice(0, 500) + '\n\n=== DS REPLY ===\n' + tail, 'utf8');
  log('REPLY-FILE: ' + f);
  log('TAIL-START: ' + tail.slice(0, 500).replace(/\n/g, ' '));
  await browser.close();
})().catch(e => { log('ERR: ' + e.message.split('\n')[0]); process.exit(1); });
