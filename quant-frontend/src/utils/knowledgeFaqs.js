// 预设问题直接展示静态答案和出处，点击无需请求模型。对应公开资料的 ID 和版本沿用后端定义。
const trendSource = {
  id: 'trend-following-v1',
  title: '趋势跟踪入门',
  category: '通用策略知识',
  version: 'v1',
  content: '时间序列动量用资产自身的历史收益方向建立持仓，是趋势跟踪的一种实现。',
  url: 'https://doi.org/10.1016/j.jfineco.2011.11.003'
}

const turtleSource = {
  id: 'turtle-trading-v1',
  title: '海龟策略的规则与取舍',
  category: '通用策略知识',
  version: 'v1',
  content: '经典海龟规则说明了通道突破、波动估计 N、仓位大小和止损的关系。',
  url: 'https://tradingblox.com/originalturtles/originalturtlerules.htm'
}

const orderSource = {
  id: 'order-types-v1',
  title: '市价单与限价单',
  category: '交易机制',
  version: 'v1',
  content: '市价单优先成交，成交价可能变化。限价单约束成交价，能否成交取决于市场。',
  url: 'https://www.investor.gov/introduction-investing/investing-basics/how-stock-markets-work/types-orders'
}

const overfittingSource = {
  id: 'backtest-overfitting-v1',
  title: '回测过拟合',
  category: '回测方法',
  version: 'v1',
  content: '研究分析了在同一份历史数据上筛选许多策略配置时，样本内赢家在样本外表现不佳的风险。',
  url: 'https://escholarship.org/uc/item/4w1110bb'
}

export const KNOWLEDGE_FAQS = [
  {
    question: '市价单和限价单有什么区别？',
    answer: '市价单通常优先成交，实际价格可能与下单时看到的报价不同。限价单先限定能接受的价格，买入只接受限价或更低，卖出只接受限价或更高。订单也可能只成交一部分，或一直没有成交。',
    sources: [orderSource]
  },
  {
    question: '什么是回测过拟合？',
    answer: '在同一段历史数据上反复试策略和参数，最漂亮的结果可能恰好符合这段数据的噪声。先确定规则，再用未参与调参的数据评估，有助于判断表现能否延续。',
    sources: [overfittingSource]
  },
  {
    question: '什么是趋势跟踪策略？',
    answer: '趋势跟踪根据历史价格识别已有方向，再按规则顺着该方向持仓。通道突破、均线和近期收益方向都可以作为信号。趋势反转或来回震荡时，信号可能频繁变化并带来交易成本。',
    sources: [trendSource]
  },
  {
    question: '海龟策略有什么优点与缺点？',
    answer: '经典海龟策略的入场、仓位和退出规则明确，便于复现和检验。趋势延续时可以按规则加仓。震荡时可能连续遇到假突破，跳空和滑点也可能使实际损失超过按止损价格估算的损失。',
    sources: [turtleSource]
  }
]
