<template>
  <div class="landing" :data-theme="colorTheme">
    <div class="grid" aria-hidden="true"></div>
    <div class="glow" aria-hidden="true"></div>
    <header class="topbar">
      <button type="button" class="brand" aria-label="QuantCloud，返回页首" @click="scrollTop">
        <img src="/favicon.svg" alt="" width="32" height="32" />
        <span>QUANT<b>CLOUD</b></span>
      </button>
      <nav class="links" aria-label="首页导航">
        <a v-for="section in landingSections" :key="section.href" :href="section.href">{{ section.label }}</a>
      </nav>
      <div class="header-actions">
        <RouterLink class="workspace-link" :to="{ name: 'login' }">工作台</RouterLink>
        <ThemeToggle class="icon-button" />
        <details v-if="signedIn" class="account-menu">
          <summary class="icon-button" aria-label="账户菜单" title="账户菜单">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="12" cy="12" r="9" />
              <circle cx="12" cy="9" r="2.5" />
              <path d="M6.4 18.5a6 6 0 0 1 11.2 0" />
            </svg>
          </summary>
          <div class="account-popover">
            <RouterLink :to="{ name: 'account' }">我的账户</RouterLink>
            <button type="button" @click="logout">退出登录</button>
          </div>
        </details>
        <RouterLink v-else class="icon-button" :to="{ name: 'login' }" aria-label="登录" title="登录">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="9" />
            <circle cx="12" cy="9" r="2.5" />
            <path d="M6.4 18.5a6 6 0 0 1 11.2 0" />
          </svg>
        </RouterLink>
      </div>
    </header>
    <main>
      <section class="hero">
        <div class="copy">
          <p class="badge"><b></b> QUANTCLOUD 现已上线</p>
          <p class="eyebrow">QUANTITATIVE RESEARCH PLATFORM</p>
          <h1 class="hero-title"><span>让每一个策略，</span><em>在真实行情中</em><span>接受检验。</span></h1>
          <p class="intro">在现货与永续合约历史行情上回测策略，逐笔回放交易，再用 AI 复盘核对证据。想了解交易机制、策略原理或回测方法，也可以直接在首页提问。</p>
          <div class="actions">
            <RouterLink class="primary" :to="{ name: 'login' }">进入量化工作台 <span aria-hidden="true">→</span></RouterLink>
            <a class="secondary" href="#features">探索核心能力 <span aria-hidden="true">↓</span></a>
          </div>
          <div class="trust"><span><b>双市场</b>历史行情</span><span><b>逐笔</b>策略回放</span><span><b>AI</b>可信复盘</span></div>
        </div>
        <div class="visual" aria-label="量化回测示意">
          <div class="orbit outer"></div><div class="orbit inner"></div><div class="scan"></div>
          <div class="terminal">
            <header><span></span><span></span><span></span><small>BACKTEST ENGINE</small></header>
            <div class="quote"><span>BTCUSDT · 1h</span><b>+8.42%</b></div>
            <div class="bars"><i v-for="n in 28" :key="n" :style="{ height: (22 + ((n * 19) % 65)) + '%' }"></i></div>
            <footer><span>MQ DISPATCHED</span><b>● LIVE</b></footer>
          </div>
          <p class="chip chip-one">RISK CONTROL <b>ACTIVE</b></p><p class="chip chip-two">52 TRADES <b>VERIFIED</b></p>
        </div>
      </section>
      <section id="features" class="section">
        <div class="heading">
          <p>CORE CAPABILITIES</p>
          <h2>看行情、跑回测，<br>从交易记录核对结论。</h2>
        </div>
        <div class="features">
          <article>
            <span>01</span>
            <h3>双市场历史行情</h3>
            <p>查询现货与永续合约的历史 K 线。即使交易对名称相同，回测和回放也各用所选市场的数据。</p>
          </article>
          <article>
            <span>02</span>
            <h3>策略回测</h3>
            <p>设置市场、周期、策略与初始资金，提交后查看收益、回撤和成交。永续合约支持多空、杠杆和逐仓风控。</p>
          </article>
          <article>
            <span>03</span>
            <h3>逐笔策略回放</h3>
            <p>沿 K 线重看策略信号、成交和仓位变化，定位开仓、平仓或风险事件发生的时刻。</p>
          </article>
          <article>
            <span>04</span>
            <h3>AI 可信复盘</h3>
            <p>用已完成回测的指标和事件生成分析。报告分开展示 AI 结论与服务端核验的证据，可从事件证据跳到对应回放。</p>
          </article>
          <article>
            <span>05</span>
            <h3>AI 策略问答</h3>
            <p>打开右下角「策略问答」，查看常见问题或用中文自由提问。回答基于公开资料，可展开查看版本与出处。</p>
          </article>
          <article>
            <span>06</span>
            <h3>共享实时盘面</h3>
            <p>在同一张永续合约图表上观察价格、共享交易区域和最近事件。管理员维护区域，观察者可查看。</p>
          </article>
        </div>
      </section>
      <section id="flow" class="section flow">
        <div class="heading">
          <p>HOW IT WORKS</p>
          <h2>从选策略到复盘，<br>一次研究怎么做。</h2>
        </div>
        <div class="steps">
          <article>
            <b>01</b>
            <div>
              <h3>了解策略，选定市场</h3>
              <p>选现货或永续合约，确定交易对和策略。遇到不熟悉的规则，可以先用策略问答查出处。</p>
            </div>
          </article>
          <article>
            <b>02</b>
            <div>
              <h3>配置参数，提交回测</h3>
              <p>设定周期、K 线数量和资金参数。任务在后台执行，进度可在历史记录里查看。</p>
            </div>
          </article>
          <article>
            <b>03</b>
            <div>
              <h3>查看结果，逐笔回放</h3>
              <p>先看权益曲线与回撤，再沿 K 线核对信号、成交和仓位变化。</p>
            </div>
          </article>
          <article>
            <b>04</b>
            <div>
              <h3>生成 AI 可信复盘</h3>
              <p>从完成的任务生成报告，检查分析引用的指标或策略事件。</p>
            </div>
          </article>
        </div>
      </section>
      <section id="tech" class="section">
        <div class="heading">
          <p>TECHNICAL ARCHITECTURE</p>
          <h2>行情、回测与 AI，<br>由独立服务处理。</h2>
        </div>
        <div class="features architecture">
          <article>
            <span>VUE 3 · SPRING CLOUD GATEWAY</span>
            <h3>工作台与权限</h3>
            <p>Vue 提供公开首页和登录后的工作区。网关统一路由与鉴权，工作区按管理员和观察者角色展示入口。</p>
          </article>
          <article>
            <span>BITGET · REDIS · MYSQL</span>
            <h3>行情与存储</h3>
            <p>行情服务接入 Bitget 的现货与永续 K 线，Redis 缓存查询结果，MySQL 保存历史数据。共享盘面通过 SSE 接收更新。</p>
          </article>
          <article>
            <span>SPRING BOOT · ROCKETMQ</span>
            <h3>回测与回放</h3>
            <p>核心服务通过 RocketMQ 异步执行回测，保存任务结果与回放快照，供历史记录查询和 AI 复盘引用。</p>
          </article>
          <article>
            <span>SPRING AI</span>
            <h3>AI 分析与问答</h3>
            <p>AI 服务分别用任务数据生成复盘、用公开资料回答策略问题。服务端核验复盘引用的事实与事件，并校验问答引用的资料来源。</p>
          </article>
        </div>
      </section>
      <section class="cta">
        <p>QUANTCLOUD WORKSPACE</p>
        <h2>用真实行情检验策略。</h2>
        <RouterLink class="primary" :to="{ name: 'login' }">进入量化工作台 <span aria-hidden="true">→</span></RouterLink>
      </section>
    </main>
    <footer class="page-footer">
      <div class="footer-inner">
        <div class="footer-grid">
          <div class="footer-about">
            <button type="button" class="brand" aria-label="QuantCloud，返回页首" @click="scrollTop">
              <img src="/favicon.svg" alt="" width="32" height="32" />
              <span>QUANT<b>CLOUD</b></span>
            </button>
            <p>用真实行情做策略回测、逐笔回放和 AI 可信复盘。</p>
          </div>
          <nav class="footer-links" aria-labelledby="footer-navigation">
            <h2 id="footer-navigation">站内导航</h2>
            <ul>
              <li v-for="section in landingSections" :key="section.href">
                <a :href="section.href">{{ section.label }}</a>
              </li>
            </ul>
          </nav>
          <nav class="footer-links" aria-labelledby="footer-resources">
            <h2 id="footer-resources">第三方资源</h2>
            <ul>
              <li v-for="resource in externalResources" :key="resource.href">
                <a :href="resource.href" target="_blank" rel="noopener noreferrer"
                  :aria-label="`${resource.label}（在新标签页打开）`">
                  {{ resource.label }} <span aria-hidden="true">↗</span>
                </a>
              </li>
            </ul>
          </nav>
        </div>
        <p class="footer-copyright">© 2026 QuantCloud</p>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { logout } from '../auth/logout.js'
import ThemeToggle from '../components/ThemeToggle.vue'
import { colorTheme } from '../utils/colorTheme.js'

const landingSections = [
  { label: '核心能力', href: '#features' },
  { label: '工作流程', href: '#flow' },
  { label: '技术架构', href: '#tech' }
]

const externalResources = [
  { label: 'TradingView', href: 'https://www.tradingview.com/' },
  { label: 'Bitget', href: 'https://www.bitget.com/' },
  { label: '交易员新闻网', href: 'https://mktnews.com/' }
]

const signedIn = Boolean(localStorage.getItem('quant_token'))
const scrollTop = () => window.scrollTo({ top: 0, behavior: 'smooth' })
</script>

<style scoped>
.landing {
  --canvas: #08090b;
  --surface: #121315;
  --raised: #191b20;
  --border: #2a2e35;
  --text: #f4f6fa;
  --muted: #a9b2c0;
  --accent: #7dd3fc;
  --inverse: #15181d;
  --positive: #4ade80;
  --grid-line: rgb(255 255 255 / 5%);
  --glow: rgb(14 165 233 / 16%);
  --shadow: 0 20px 55px rgb(0 0 0 / 34%);
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: var(--canvas);
  color: var(--text);
  color-scheme: dark;
  font-family: Inter, ui-sans-serif, system-ui, sans-serif;
}

.landing[data-theme='light'] {
  --canvas: #f2f4f7;
  --surface: #fff;
  --raised: #f5f7fa;
  --border: #dce1e8;
  --text: #171c25;
  --muted: #4f5b6c;
  --accent: #1455b4;
  --inverse: #fff;
  --positive: #166534;
  --grid-line: rgb(29 78 216 / 8%);
  --glow: rgb(56 189 248 / 20%);
  --shadow: 0 20px 55px rgb(46 66 99 / 14%);
  color-scheme: light;
}

.grid,
.glow {
  position: absolute;
  pointer-events: none;
}

.grid {
  inset: 0 0 auto;
  height: 780px;
  background-image:
    linear-gradient(var(--grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--grid-line) 1px, transparent 1px);
  background-size: 72px 72px;
  mask-image: radial-gradient(ellipse at 56% 10%, #000 5%, transparent 73%);
}

.glow {
  top: 100px;
  right: -140px;
  width: 520px;
  height: 520px;
  border-radius: 50%;
  background: var(--glow);
  filter: blur(110px);
}

.topbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: clamp(20px, 3vw, 56px);
  min-height: 74px;
  padding: 0 clamp(16px, 2.5vw, 48px);
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: .04em;
  cursor: pointer;
}

.brand img {
  flex: none;
  display: block;
}

.brand b {
  color: var(--accent);
}

.links,
.header-actions {
  display: flex;
  align-items: center;
}

.links {
  gap: clamp(18px, 2.7vw, 48px);
}

.links a,
.workspace-link,
.icon-button,
.actions a,
.cta a {
  text-decoration: none;
}

.links a {
  display: inline-flex;
  align-items: center;
  min-height: 42px;
  color: var(--muted);
  font-size: 14px;
  white-space: nowrap;
}

.links a:hover {
  color: var(--accent);
}

.header-actions {
  gap: 8px;
  margin-left: auto;
}

.workspace-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 15px;
  border: 1px solid var(--text);
  border-radius: 9px;
  background: var(--text);
  color: var(--inverse);
  font-size: 13px;
  font-weight: 700;
  white-space: nowrap;
}

.workspace-link:hover {
  opacity: .86;
}

.icon-button {
  display: inline-grid;
  flex: none;
  width: 42px;
  height: 42px;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}

.icon-button:hover {
  background: var(--raised);
  color: var(--text);
}

.icon-button svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.65;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.account-menu {
  position: relative;
}

.account-menu summary {
  list-style: none;
}

.account-menu summary::-webkit-details-marker {
  display: none;
}

.account-popover {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  display: grid;
  min-width: 128px;
  padding: 6px;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
  box-shadow: var(--shadow);
}

.account-popover a,
.account-popover button {
  padding: 9px 12px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text);
  font: inherit;
  font-size: 14px;
  text-align: left;
  text-decoration: none;
  white-space: nowrap;
  cursor: pointer;
}

.account-popover a:hover,
.account-popover button:hover {
  background: var(--raised);
}

.landing :focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}

.hero,
.section,
.cta,
.footer-inner {
  position: relative;
  width: min(1120px, calc(100% - 48px));
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, .95fr);
  align-items: center;
  gap: 40px;
  min-height: 650px;
  margin: auto;
}

.badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 25px;
  padding: 7px 11px;
  border: 1px solid var(--border);
  border-radius: 99px;
  background: var(--surface);
  color: var(--muted);
  font-size: 11px;
}

.badge b {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--positive);
}

.eyebrow,
.heading > p,
.cta > p {
  margin: 0 0 16px;
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .2em;
}

.hero h1,
.heading h2,
.cta h2 {
  margin: 0;
  font-weight: 500;
  letter-spacing: -.055em;
  line-height: 1.1;
}

.hero h1 {
  max-width: 650px;
  font-size: clamp(42px, 6vw, 76px);
}

.hero-title span,
.hero-title em {
  display: block;
}

.hero-title em {
  color: var(--accent);
  font-style: normal;
}

.intro {
  max-width: 560px;
  margin: 28px 0;
  color: var(--muted);
  font-size: 16px;
  line-height: 1.8;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.primary,
.secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 48px;
  padding: 0 20px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 14px;
  font-weight: 700;
}

.primary {
  background: linear-gradient(110deg, #0369a1, #2850d8 56%, #6d28d9);
  box-shadow: 0 8px 20px rgb(37 99 235 / 20%);
  color: #fff;
}

.primary:hover {
  filter: brightness(1.12);
}

.secondary {
  border-color: var(--border);
  background: var(--surface);
  color: var(--text);
}

.secondary:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.trust {
  display: flex;
  gap: 19px;
  margin-top: 38px;
  color: var(--muted);
  font-size: 11px;
}

.trust span + span {
  padding-left: 19px;
  border-left: 1px solid var(--border);
}

.trust b {
  color: var(--text);
}

.visual {
  position: relative;
  height: 420px;
}

.terminal {
  position: absolute;
  z-index: 1;
  top: 70px;
  right: 5%;
  width: min(390px, 88%);
  padding: 20px;
  border: 1px solid var(--border);
  border-radius: 18px;
  background: var(--surface);
  box-shadow: var(--shadow);
  transform: rotate(-4deg);
}

.terminal header {
  display: flex;
  align-items: center;
  gap: 5px;
}

.terminal header span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
}

.terminal header span:first-child {
  background: #ef4444;
}

.terminal header span:nth-child(2) {
  background: #eab308;
}

.terminal small {
  margin-left: auto;
  color: var(--muted);
  font-size: 9px;
  letter-spacing: .12em;
}

.quote {
  display: flex;
  align-items: end;
  justify-content: space-between;
  margin: 30px 0 24px;
  color: var(--muted);
  font-size: 16px;
}

.quote b {
  color: var(--positive);
  font-size: 32px;
  letter-spacing: -.06em;
}

.bars {
  display: flex;
  align-items: end;
  gap: 4px;
  height: 110px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border);
  background: repeating-linear-gradient(0deg, transparent 0 26px, var(--grid-line) 27px);
}

.bars i {
  flex: 1;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(#38bdf8, rgb(56 189 248 / 18%));
  animation: bars 2.8s ease-in-out infinite alternate;
}

.bars i:nth-child(3n) {
  background: linear-gradient(#22c55e, rgb(34 197 94 / 15%));
  animation-delay: .45s;
}

@keyframes bars {
  to { transform: scaleY(.72); transform-origin: bottom; }
}

.terminal footer {
  display: flex;
  justify-content: space-between;
  margin-top: 17px;
  color: var(--muted);
  font-size: 10px;
  letter-spacing: .08em;
}

.terminal footer b,
.chip b {
  color: var(--positive);
}

.orbit,
.scan {
  position: absolute;
  top: 50%;
  left: 50%;
  border: 1px solid var(--border);
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.outer {
  width: 390px;
  height: 390px;
  animation: spin 18s linear infinite;
}

.inner {
  width: 270px;
  height: 270px;
  border-style: dashed;
  animation: spin 12s linear infinite reverse;
}

@keyframes spin {
  to { rotate: 360deg; }
}

.scan {
  width: 35px;
  height: 35px;
  border-color: var(--accent);
  animation: scan 3.4s ease-out infinite;
}

@keyframes scan {
  to { opacity: 0; transform: translate(-50%, -50%) scale(9); }
}

.chip {
  position: absolute;
  z-index: 1;
  padding: 9px 12px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
  color: var(--muted);
  box-shadow: var(--shadow);
  font-size: 9px;
  letter-spacing: .09em;
}

.chip-one {
  top: 26px;
  right: 3%;
}

.chip-two {
  bottom: -28px;
  left: 5%;
}

.section {
  margin: auto;
  padding: 112px 0;
}

.heading h2 {
  font-size: clamp(32px, 4.3vw, 60px);
}

.features {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-top: 46px;
}

.features.architecture {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.features article {
  min-height: 218px;
  padding: 25px;
  border: 1px solid var(--border);
  border-radius: 17px;
  background: var(--surface);
}

.features article:hover {
  border-color: var(--accent);
}

.features span,
.steps b {
  color: var(--accent);
  font-family: ui-monospace, monospace;
  font-size: 11px;
}

.features h3 {
  margin: 48px 0 10px;
  font-size: 18px;
}

.features.architecture article {
  min-height: 0;
}

.features.architecture h3 {
  margin-top: 20px;
}

.features p,
.steps p {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.75;
}

.flow {
  display: grid;
  grid-template-columns: minmax(0, .8fr) minmax(0, 1.2fr);
  gap: clamp(28px, 7vw, 100px);
  border-top: 1px solid var(--border);
}

.steps {
  display: grid;
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 16px;
  background: var(--border);
}

.steps article {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 8px;
  padding: 24px;
  background: var(--surface);
}

.steps h3 {
  margin: 0 0 8px;
  font-size: 16px;
}

.cta {
  margin: 0 auto 100px;
  padding: 88px 28px;
  border: 1px solid var(--border);
  border-radius: 24px;
  background: radial-gradient(ellipse at 75% 35%, var(--glow), transparent 55%), var(--surface);
  text-align: center;
}

.cta h2 {
  margin-bottom: 34px;
  font-size: clamp(32px, 5vw, 68px);
}

.page-footer {
  position: relative;
  border-top: 1px solid var(--border);
  background: var(--surface);
  padding: 56px 0 88px;
}

.footer-inner {
  margin: 0 auto;
}

.footer-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.65fr) repeat(2, minmax(0, 1fr));
  gap: 36px;
}

.footer-about p {
  max-width: 280px;
  margin: 16px 0 0;
  color: var(--muted);
  font-size: 14px;
  line-height: 1.75;
}

.footer-links h2 {
  margin: 5px 0 14px;
  font-size: 14px;
}

.footer-links ul {
  margin: 0;
  padding: 0;
  list-style: none;
}

.footer-links a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  color: var(--muted);
  font-size: 14px;
  text-decoration: none;
}

.footer-links a:hover {
  color: var(--accent);
}

.footer-links a span {
  font-size: 12px;
}

.footer-copyright {
  margin: 40px 0 0;
  padding-top: 20px;
  border-top: 1px solid var(--border);
  color: var(--muted);
  font-size: 12px;
}

@media (max-width: 760px) {
  .topbar {
    flex-wrap: wrap;
    gap: 0 12px;
    min-height: 0;
    padding: 10px 14px 2px;
  }

  .links {
    order: 1;
    flex: 0 0 100%;
    justify-content: center;
    gap: clamp(16px, 6vw, 38px);
  }

  .header-actions {
    gap: 2px;
  }

  .workspace-link {
    min-height: 34px;
    padding: 0 10px;
  }

  .icon-button {
    width: 40px;
    height: 40px;
  }

  .hero,
  .section,
  .cta,
  .footer-inner {
    width: calc(100% - 32px);
  }

  .hero {
    grid-template-columns: 1fr;
    gap: 8px;
    width: min(560px, calc(100% - 32px));
    min-height: 0;
    padding: 68px 0 28px;
    text-align: center;
  }

  .intro {
    font-size: 14px;
  }

  .actions,
  .trust {
    justify-content: center;
  }

  .trust {
    gap: 10px;
  }

  .trust span + span {
    padding-left: 10px;
  }

  .visual {
    height: 422px;
    text-align: left;
  }

  .terminal {
    top: 35px;
    right: 50%;
    width: min(310px, calc(100% - 22px));
    transform: translateX(50%) rotate(-3deg);
  }

  .outer {
    width: 310px;
    height: 310px;
  }

  .inner {
    width: 210px;
    height: 210px;
  }

  .chip-one {
    top: 0;
    right: 0;
  }

  .chip-two {
    bottom: 0;
    left: 0;
  }

  .scan {
    display: none;
  }

  .section {
    padding: 64px 0;
  }

  .features,
  .features.architecture,
  .flow {
    grid-template-columns: 1fr;
  }

  .features {
    margin-top: 30px;
  }

  .features article {
    min-height: 0;
  }

  .features h3 {
    margin-top: 20px;
  }

  .cta {
    margin-bottom: 52px;
    padding: 64px 18px;
  }

  .page-footer {
    padding-top: 38px;
  }

  .footer-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 24px 16px;
  }

  .footer-about {
    grid-column: 1 / -1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .orbit,
  .scan,
  .bars i {
    animation: none;
  }
}
</style>
