export function logout() {
  localStorage.removeItem('quant_token')
  localStorage.removeItem('quant_user')
  window.location.replace('/')
}
