/* global localStorage, matchMedia, document */
// Runs synchronously in <head>, before styles or React, to avoid a wrong-theme flash.
;(function () {
  var preference = 'auto'
  try {
    var saved = localStorage.getItem('live-jvg.theme')
    if (saved === 'light' || saved === 'dark' || saved === 'auto') preference = saved
  } catch { /* Storage can be unavailable in private/restricted browsing. */ }
  var lightSystem = typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: light)').matches
  var theme = preference === 'auto' ? (lightSystem ? 'light' : 'dark') : preference
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
  var meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', theme === 'light' ? '#f3f6fa' : '#080e19')
})()
