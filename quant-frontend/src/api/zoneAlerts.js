import api from './core.js'

const ROOT = '/core/zone-alerts'

export async function getZoneAlerts() { return (await api.get(ROOT)).data }
export async function getZoneAlertNotices(noticePage) {
  return (await api.get(ROOT, { params: { noticePage } })).data
}
export function subscribeZoneAlert(id, enabled) {
  const resource = `${ROOT}/subscriptions/${id}`
  return enabled ? api.put(resource) : api.delete(resource)
}
