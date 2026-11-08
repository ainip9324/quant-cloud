<template><div ref="element" class="market-chart" role="img" aria-label="K 线与交易区域，未保存的区域以虚线预览"></div></template>
<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { chartOption } from '../utils/marketWorkspace.js'
import { colorTheme } from '../utils/colorTheme.js'
const props = defineProps({ snapshot: Object, draft: Object })
const element = ref()
let chart, resize, renderedScope
function render() {
  if (!chart) return
  const scope = props.snapshot ? [props.snapshot.marketType, props.snapshot.symbol, props.snapshot.interval].join('/') : null
  const changed = scope !== renderedScope
  const viewport = changed ? undefined : chart.getOption()?.dataZoom?.[0]
  chart.setOption(chartOption(props.snapshot, props.draft, viewport, colorTheme.value), {
    notMerge: changed,
    replaceMerge: ['series']
  })
  renderedScope = scope
}
onMounted(() => {
  chart = echarts.init(element.value); render()
  if (typeof ResizeObserver !== 'undefined') { resize = new ResizeObserver(() => chart?.resize()); resize.observe(element.value) }
  else window.addEventListener('resize', onResize)
})
function onResize() { chart?.resize() }
watch(() => [props.snapshot, props.draft, colorTheme.value], render, { deep: true })
onBeforeUnmount(() => { resize?.disconnect(); window.removeEventListener('resize', onResize); chart?.dispose(); chart = null })
</script>
<style scoped>.market-chart { width: 100%; min-width: 0; height: 440px; } @media (max-width: 600px) { .market-chart { height: 340px; } }</style>
