const ADMIN_MENUS = [
  { name: 'home', path: '/workspace', label: '概览' },
  { name: 'market', path: '/market', label: '实时盘面' },
  { name: 'replay', path: '/replay', label: '策略回放' },
  { name: 'strategy', path: '/strategy', label: '策略管理' },
  { name: 'backtest', path: '/backtest', label: '回测' },
  { name: 'history', path: '/history', label: '历史记录' },
  { name: 'ai-reports', path: '/ai-reports', label: 'AI复盘' },
  { name: 'account', path: '/account', label: '我的账户' }
]

const VIEWER_MENUS = [
  { name: 'market', path: '/market', label: '实时盘面' },
  { name: 'account', path: '/account', label: '我的账户' }
]

export function workspaceHomeForRole(role) {
  return role === 'ADMIN' ? '/workspace' : '/market'
}

export function workspaceMenusForRole(role) {
  return role === 'ADMIN' ? ADMIN_MENUS : VIEWER_MENUS
}
