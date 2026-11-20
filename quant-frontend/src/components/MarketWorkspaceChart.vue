<template>
  <div ref="frame" class="chart-frame" :tabindex="editable ? 0 : -1"
    aria-label="K 线与交易区域" @keydown="editKey" @contextmenu.capture.prevent="openChartMenu">
    <div ref="element" class="market-chart" :class="{ drawing }"></div>
    <svg v-if="overlay" ref="editLayer" class="edit-layer" :style="{ clipPath: overlay.clip }" aria-hidden="true"
      @pointermove="moveEdit" @pointerup="finishEdit" @pointercancel="cancelEdit" @lostpointercapture="cancelEdit">
      <polygon :points="overlay.points" class="edit-shape" @pointerdown.stop="startEdit($event, 'move')" />
      <circle v-for="handle in overlay.handles" :key="handle.id" :cx="handle.x" :cy="handle.y" r="5"
        class="edit-handle" @pointerdown.stop="startEdit($event, handle.id)" />
    </svg>
    <div v-if="chartMenu" ref="menuElement" class="chart-menu" :style="chartMenu"
      @contextmenu.prevent.stop @keydown.esc.stop="closeChartMenu(true)">
      <button type="button" @click="resetChart">重置图表</button>
    </div>
  </div>
</template>
<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { chartOption, FUTURE_AXIS_PERIODS, periodMillis } from '../utils/marketWorkspace.js'
import { adjustZone, drawnZone } from '../utils/chartGeometry.js'
import { colorTheme } from '../utils/colorTheme.js'
const props = defineProps({ snapshot: Object, draft: Object, drawing: Boolean, channel: Boolean,
  editable: Boolean, selectedId: Number, showEma: Boolean })
const emit = defineEmits(['draw-range', 'cancel-drawing', 'select-zone', 'adjust-zone', 'archive-zone', 'cancel-edit'])
const element = ref()
const frame = ref(), editLayer = ref(), overlay = ref(null)
const chartMenu = ref(null), menuElement = ref()
let drag
let chart, resize, renderedScope, renderedMarketEnd, outline, pointer
let renderedTimeAxis
let candleWidth
let rightEdge
let pan
let panFrame
let pendingRender = false
let anchors = []

function render() {
  if (!chart || drag) return
  const scope = props.snapshot ? [props.snapshot.marketType, props.snapshot.symbol, props.snapshot.interval].join('/') : null
  const changed = scope !== renderedScope
  // 拖动只更新视窗，行情推送在松手后合并应用最新快照。
  if (pan && !changed) {
    pendingRender = true
    return
  }
  if (changed) clearPan()
  pendingRender = false
  if (changed) candleWidth = undefined
  const visibleRange = changed ? undefined : chart.getModel()?.getComponent('dataZoom')?.getValueRange('x', 0)
  const viewport = visibleRange && { startValue: visibleRange[0], endValue: visibleRange[1], marketEnd: renderedMarketEnd }
  const option = chartOption(props.snapshot, props.draft, viewport, colorTheme.value, props.showEma)
  // 同一时间域内的实时推送、主题或草稿更新不向 ECharts 重发缩放指令。
  if (!changed && renderedTimeAxis?.min === option.xAxis.min && renderedTimeAxis?.max === option.xAxis.max) {
    delete option.dataZoom
  }
  chart.setOption(option, {
    notMerge: changed,
    replaceMerge: ['series']
  })
  renderedScope = scope
  renderedTimeAxis = option.xAxis
  const period = periodMillis(props.snapshot?.interval)
  renderedMarketEnd = period && option.xAxis.max - FUTURE_AXIS_PERIODS * period
  const candles = option.series[0].data
  rightEdge = period && candles.length ? {
    start: Math.max(option.xAxis.min, (candles.at(-2) ?? candles[0])[0] - period / 2),
    last: candles.at(-1)[0],
    padding: period / 2
  } : null
  onZoom()
}

function onZoom() {
  if (rightEdge) {
    const [start, end] = chart.getModel().getComponent('dataZoom').getValueRange('x', 0)
    if (start > rightEdge.start) {
      const width = Math.max(end - start, rightEdge.last + rightEdge.padding - rightEdge.start)
      chart.dispatchAction({ type: 'dataZoom', startValue: rightEdge.start, endValue: rightEdge.start + width }, { silent: true })
    }
  }
  // 拖动中滚轮仍由 ECharts 缩放。用缩放后的视窗继续拖动。
  if (pan) {
    const [start, end] = chart.getModel().getComponent('dataZoom').getValueRange('x', 0)
    pan.start = start
    pan.width = end - start
  }
  updateViewport()
}

function startPan(event) {
  const input = event.event
  const primary = input?.which === 1 || input?.button === 0 || input?.zrByTouch
  if (!rightEdge || !primary ||
      !chart.containPixel({ gridIndex: 0 }, [event.offsetX, event.offsetY])) return
  const [start, end] = chart.getModel().getComponent('dataZoom').getValueRange('x', 0)
  const pixels = chart.getModel().getComponent('grid').coordinateSystem.getRect().width
  if (pixels > 0) pan = { x: event.offsetX, start, width: end - start, pixels }
}

function movePan(event) {
  if (!pan || event.gestureEvent === 'pinch') return
  const start = pan.start + (pan.x - event.offsetX) * pan.width / pan.pixels
  const width = start > rightEdge.start
    ? Math.max(pan.width, rightEdge.last + rightEdge.padding - rightEdge.start)
    : pan.width
  const maxStart = Math.min(rightEdge.start, renderedTimeAxis.max - width)
  const bounded = Math.max(renderedTimeAxis.min, Math.min(maxStart, start))
  pan.x = event.offsetX
  if (pan.start === bounded && pan.width === width) return
  pan.start = bounded
  pan.width = width
  if (panFrame == null) panFrame = window.requestAnimationFrame(applyPan)
}

function applyPan() {
  panFrame = undefined
  const [start, end] = chart.getModel().getComponent('dataZoom').getValueRange('x', 0)
  const startValue = Math.round(pan.start), endValue = Math.round(pan.start + pan.width)
  if (startValue === start && endValue === end) return
  chart.dispatchAction({ type: 'dataZoom', startValue, endValue }, { silent: true })
  updateViewport()
}

function clearPan() {
  if (panFrame != null) window.cancelAnimationFrame(panFrame)
  panFrame = undefined
  pan = undefined
}

function finishPan() {
  if (panFrame != null) {
    window.cancelAnimationFrame(panFrame)
    applyPan()
  }
  clearPan()
  if (pendingRender) render()
}

async function openChartMenu(event) {
  if (drag) return
  const bounds = frame.value.getBoundingClientRect()
  chartMenu.value = { left: '0px', top: '0px' }
  await nextTick()
  if (!menuElement.value) return
  chartMenu.value = {
    left: `${Math.max(0, Math.min(event.clientX - bounds.left, bounds.width - menuElement.value.offsetWidth))}px`,
    top: `${Math.max(0, Math.min(event.clientY - bounds.top, bounds.height - menuElement.value.offsetHeight))}px`
  }
  menuElement.value.querySelector('button').focus({ preventScroll: true })
}

function closeChartMenu(restoreFocus = false) {
  chartMenu.value = null
  if (restoreFocus) frame.value.focus({ preventScroll: true })
}

function dismissChartMenu(event) {
  if (chartMenu.value && !menuElement.value?.contains(event.target)) closeChartMenu()
}

function resetChart() {
  const { start, end, startValue, endValue } = chartOption(props.snapshot, null).dataZoom[0]
  chart.dispatchAction({ type: 'dataZoom', start, end, startValue, endValue })
  closeChartMenu(true)
}

/** 时间轴上的一根 K 线占一个周期，未来空白不改变它的宽度。 */
function updateViewport() {
  const period = periodMillis(props.snapshot?.interval)
  if (period && Number.isFinite(renderedMarketEnd)) {
    const axis = { xAxisIndex: 0 }
    const start = chart.convertToPixel(axis, renderedMarketEnd - period)
    const end = chart.convertToPixel(axis, renderedMarketEnd)
    const width = Math.max(0.5, Math.round(Math.abs(end - start) * 6) / 10)
    if (Number.isFinite(width) && width !== candleWidth) {
      candleWidth = width
      chart.setOption({ series: [{ id: 'workspace-candles', barWidth: width }] }, { silent: true })
    }
  }
  renderOutline()
  renderSelection()
}

/** 将时间与价位投影成可编辑的图形。无失效时间的区域只提供左侧控制点。 */
function zonePoints(zone) {
  const until = zone.effectiveUntil ?? chart.getModel().getComponent('dataZoom').getValueRange('x', 0)[1]
  const lower = Number(zone.lowerPrice), upper = Number(zone.upperPrice)
  const endLower = Number(zone.endLowerPrice ?? lower)
  return [[zone.effectiveFrom, lower], [zone.effectiveFrom, upper],
    [until, endLower + (upper - lower)], [until, endLower]]
    .map(point => chart.convertToPixel({ gridIndex: 0 }, point))
}

function renderSelection() {
  const zone = drag?.preview ?? props.draft
  if (!props.editable || props.drawing || props.selectedId == null || !zone) {
    overlay.value = null
    return
  }
  const points = zonePoints(zone)
  const grid = chart.getModel().getComponent('grid').coordinateSystem.getRect()
  const channel = zone.endLowerPrice != null
  const handles = channel ? [
    ['start-channel', points[0]], ['end-channel', points[3]],
    ['width', [(points[1][0] + points[2][0]) / 2, (points[1][1] + points[2][1]) / 2]]
  ] : [['start-lower', points[0]], ['start-upper', points[1]],
    ...(zone.effectiveUntil == null ? [] : [['end-upper', points[2]], ['end-lower', points[3]]])]
  overlay.value = {
    points: points.map(point => point.join(',')).join(' '),
    clip: `inset(${grid.y}px ${chart.getWidth() - grid.x - grid.width}px ${chart.getHeight() - grid.y - grid.height}px ${grid.x}px)`,
    handles: handles.map(([id, [x, y]]) => ({ id, x, y }))
  }
}

/** 仅单击命中图形时选中，空白处仍可平移。重叠时优先选择最后绘制的区域。 */
function selectSavedZone(event) {
  if (!props.editable || props.drawing) return
  const point = [event.offsetX, event.offsetY]
  if (!chart.containPixel({ gridIndex: 0 }, point)) return
  const zone = [...(props.snapshot?.zones || [])].reverse().find(item => {
    if (item.lifecycle === 'ARCHIVED') return false
    const [left, upperLeft, upperRight, right] = zonePoints(item)
    if (point[0] < left[0] - 4 || point[0] > right[0] + 4 || right[0] <= left[0]) return false
    const t = Math.max(0, Math.min(1, (point[0] - left[0]) / (right[0] - left[0])))
    const top = upperLeft[1] + (upperRight[1] - upperLeft[1]) * t
    const bottom = left[1] + (right[1] - left[1]) * t
    return point[1] >= top - 4 && point[1] <= bottom + 4
  })
  if (zone) {
    frame.value?.focus?.({ preventScroll: true })
    emit('select-zone', zone)
  }
}

function editPoint(event) {
  const bounds = editLayer.value.getBoundingClientRect()
  return chart.convertFromPixel({ gridIndex: 0 }, [event.clientX - bounds.left, event.clientY - bounds.top])
}

function startEdit(event, handle) {
  if (event.button !== 0 || drag || !props.editable || !props.draft) return
  event.preventDefault()
  frame.value.focus({ preventScroll: true })
  drag = { pointerId: event.pointerId, handle, origin: editPoint(event), zone: { ...props.draft } }
  editLayer.value.setPointerCapture(event.pointerId)
}

function moveEdit(event) {
  if (event.pointerId !== drag?.pointerId) return
  const [time, price] = editPoint(event)
  const period = periodMillis(props.snapshot?.interval)
  if (!period || ![time, price, ...drag.origin].every(Number.isFinite)) return
  drag.preview = adjustZone(drag.zone, drag.handle, time - drag.origin[0], price - drag.origin[1], period)
  renderSelection()
}

function finishEdit(event) {
  if (event.pointerId !== drag?.pointerId) return
  const preview = drag.preview
  cancelEdit()
  if (preview && props.editable) emit('adjust-zone', preview)
}

function cancelEdit() {
  if (!drag) return
  const { pointerId } = drag
  drag = null
  if (editLayer.value?.hasPointerCapture(pointerId)) editLayer.value.releasePointerCapture(pointerId)
  render()
}

function editKey(event) {
  if (event.target !== frame.value || !props.editable || props.drawing || props.selectedId == null) return
  if (event.key === 'Delete' && !drag && !event.repeat) {
    event.preventDefault()
    emit('archive-zone', props.selectedId)
  } else if (event.key === 'Escape') {
    if (drag) cancelEdit()
    else emit('cancel-edit')
  }
}

/** 以 K 线开盘时间吸附锚点，价格精确到分。 */
function dataPoint(point) {
  const values = chart.convertFromPixel({ gridIndex: 0 }, point)
  if (!values?.every(Number.isFinite)) return null
  const period = periodMillis(props.snapshot?.interval)
  const time = Math.floor(values[0] / period) * period
  const price = Number(values[1].toFixed(2))
  return period && time > 0 && price > 0 ? { time, price } : null
}

/** 水平区间两点完成，平行通道第三点确定宽度。 */
function selectBoundary(event) {
  if (!props.drawing) return
  const point = [event.offsetX, event.offsetY]
  if (!chart.containPixel({ gridIndex: 0 }, point)) return
  const value = dataPoint(point)
  if (!value) return
  const next = [...anchors, value]
  const result = drawnZone(next, periodMillis(props.snapshot?.interval), props.channel)
  if (next.length === (props.channel ? 3 : 2)) {
    if (!result) return
    clearDrawing()
    emit('draw-range', result)
  } else {
    if (props.channel && anchors.length === 1 && anchors[0].time === value.time) return
    anchors = next
    pointer = point
    renderOutline()
  }
}

/** 只更新预览多边形，坐标存为行情时间与价格，缩放后重新投影。 */
function renderOutline() {
  if (!outline) return
  if (!props.drawing || !anchors.length || !pointer || !chart.containPixel({ gridIndex: 0 }, pointer)) {
    outline.hide()
    return
  }
  const value = dataPoint(pointer)
  if (value && props.channel && anchors.length === 1) {
    const [a, b] = anchors[0].time <= value.time ? [anchors[0], value] : [value, anchors[0]]
    const start = chart.convertToPixel({ gridIndex: 0 }, [a.time, a.price])
    const end = chart.convertToPixel({ gridIndex: 0 }, [b.time + periodMillis(props.snapshot.interval), b.price])
    outline.setShape({ points: [start, end, end, start] })
    outline.show()
    return
  }
  const result = value && drawnZone([...anchors, value], periodMillis(props.snapshot?.interval), props.channel && anchors.length === 2)
  if (!result) {
    outline.hide()
    return
  }
  const endLower = Number(result.endLowerPrice ?? result.lowerPrice)
  const endUpper = endLower + Number(result.upperPrice) - Number(result.lowerPrice)
  outline.setShape({ points: [
    [result.effectiveFrom, Number(result.lowerPrice)], [result.effectiveFrom, Number(result.upperPrice)],
    [result.effectiveUntil, endUpper], [result.effectiveUntil, endLower]
  ].map(point => chart.convertToPixel({ gridIndex: 0 }, point)) })
  outline.show()
}

function movePointer(event) {
  if (!props.drawing || !anchors.length) return
  pointer = [event.offsetX, event.offsetY]
  renderOutline()
}

function leavePlot() {
  pointer = null
  outline?.hide()
}

function clearDrawing() {
  anchors = []
  leavePlot()
}

function cancelOnEscape(event) {
  if (props.drawing && event.key === 'Escape') emit('cancel-drawing')
}

onMounted(() => {
  chart = echarts.init(element.value)
  render()
  // 使用画布事件，空白处也能拾取价格。
  const canvas = chart.getZr()
  outline = new echarts.graphic.Polygon({
    shape: { points: [] },
    silent: true, ignore: true, z: 100,
    style: { fill: '#38bdf826', stroke: '#38bdf8', lineDash: [5, 4], lineWidth: 2 }
  })
  canvas.add(outline)
  canvas.on('click', selectBoundary)
  canvas.on('click', selectSavedZone)
  canvas.on('mousemove', movePointer)
  canvas.on('mousemove', movePan)
  canvas.on('globalout', leavePlot)
  canvas.on('mousedown', startPan)
  canvas.on('mouseup', finishPan)
  chart.on('datazoom', onZoom)
  window.addEventListener('mouseup', finishPan)
  window.addEventListener('pointercancel', finishPan)
  window.addEventListener('blur', finishPan)
  window.addEventListener('pointerdown', dismissChartMenu, true)
  window.addEventListener('keydown', cancelOnEscape)
  if (typeof ResizeObserver !== 'undefined') { resize = new ResizeObserver(onResize); resize.observe(element.value) }
  else window.addEventListener('resize', onResize)
})
function onResize() {
  if (pan) finishPan()
  chart?.resize()
  if (chart) updateViewport()
}
watch(() => [props.snapshot, props.draft, colorTheme.value, props.showEma], render, { deep: true })
watch(() => [props.drawing, props.channel], clearDrawing)
watch(() => [props.editable, props.selectedId, props.drawing], () => { cancelEdit(); render() })
onBeforeUnmount(() => {
  clearPan()
  resize?.disconnect()
  window.removeEventListener('resize', onResize)
  window.removeEventListener('mouseup', finishPan)
  window.removeEventListener('pointercancel', finishPan)
  window.removeEventListener('blur', finishPan)
  window.removeEventListener('pointerdown', dismissChartMenu, true)
  window.removeEventListener('keydown', cancelOnEscape)
  chart?.dispose()
  chart = null
  outline = null
})
</script>
<style scoped>
.chart-frame { position: relative; }
.edit-layer { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.edit-shape, .edit-handle { pointer-events: all; touch-action: none; }
.edit-shape { fill: #38bdf81a; stroke: #38bdf8; stroke-width: 2; cursor: move; }
.edit-handle { fill: white; stroke: #0284c7; stroke-width: 2; cursor: grab; }
.market-chart { width: 100%; min-width: 0; height: 440px; }
.chart-menu { position: absolute; z-index: 2; padding: 4px; border: 1px solid var(--qc-control-border);
  border-radius: 8px; background: var(--qc-surface); box-shadow: var(--qc-shadow); }
.chart-menu button { display: block; border: 0; border-radius: 4px; padding: 8px 14px;
  color: var(--qc-text); background: transparent; cursor: pointer; white-space: nowrap; }
.chart-menu button:is(:hover, :focus-visible) { background: var(--qc-accent-soft); }
.market-chart.drawing, .market-chart.drawing :deep(div), .market-chart.drawing :deep(canvas) { cursor: crosshair !important; }
@media (max-width: 600px) { .market-chart { height: 340px; } }
</style>
