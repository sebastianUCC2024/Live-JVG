/* global localStorage, document */
// Runs synchronously in <head>, before styles or React, to avoid a wrong-theme flash.
;(function () {
  var preference = 'dark'
  try {
    var saved = localStorage.getItem('live-jvg.theme')
    if (saved === 'light' || saved === 'dark') preference = saved
    else if (saved !== null) localStorage.setItem('live-jvg.theme', 'dark')
  } catch { /* Storage can be unavailable in private/restricted browsing. */ }
  var theme = preference
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
  var meta = document.querySelector('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', theme === 'light' ? '#f3f6fa' : '#080e19')
})()
