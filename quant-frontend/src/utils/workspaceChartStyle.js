export function workspaceChartStyle(theme) {
  const light = theme === 'light'
  const label = light ? '#526274' : 'rgba(255,255,255,0.5)'
  const border = light ? '#cbd5e1' : 'rgba(255,255,255,0.14)'
  const grid = light ? '#e2e8f0' : 'rgba(255,255,255,0.06)'
  return {
    axis: {
      axisLabel: { color: label },
      axisLine: { lineStyle: { color: border } },
      nameTextStyle: { color: label },
      splitLine: { lineStyle: { color: grid } }
    },
    tooltip: {
      backgroundColor: light ? '#fff' : 'rgba(17,20,24,0.92)',
      borderColor: border,
      textStyle: { color: light ? '#171c25' : '#e5e7eb', fontSize: 12 },
      axisPointer: { lineStyle: { color: light ? 'rgba(20,85,180,0.55)' : 'rgba(56,189,248,0.5)' } }
    },
    slider: {
      id: 'workspace-zoom-slider',
      borderColor: border,
      backgroundColor: light ? '#f1f4f8' : 'rgba(255,255,255,0.04)',
      fillerColor: light ? 'rgba(20,85,180,0.13)' : 'rgba(56,189,248,0.14)',
      dataBackground: { lineStyle: { color: border }, areaStyle: { color: grid } },
      handleStyle: { color: light ? '#fff' : 'rgba(255,255,255,0.7)', borderColor: label },
      moveHandleStyle: { color: light ? '#467aca' : 'rgba(56,189,248,0.6)' },
      textStyle: { color: label }
    }
  }
}

// Merge only presentation options, preserving the selected dataZoom range and existing series.
export function applyWorkspaceChartStyle(chart, theme) {
  if (!chart) return
  const { axis, tooltip, slider } = workspaceChartStyle(theme)
  chart.setOption({ xAxis: axis, yAxis: axis, tooltip, dataZoom: [slider] })
}
