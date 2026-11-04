/* HostID appearance v1 -> native mail WebView -> launcher titlebar.
 * The native shell intercepts and cancels the reserved navigation before any request.
 * HostID itself only publishes a snapshot/event and never needs Tauri access.
 */
;(function () {
  var key = '__MINIS_MAIL_APPEARANCE_BRIDGE__'
  if (window[key]) {
    window[key].sync()
    return
  }

  var lastBackground = null
  var pending = null
  var frame = null

  function publish(appearance, force) {
    if (!appearance || appearance.version !== 1 ||
        typeof appearance.backgroundColor !== 'string' ||
        !/^#[0-9a-f]{6}$/i.test(appearance.backgroundColor)) return
    var background = appearance.backgroundColor.toLowerCase()
    if (!force && background === (pending || lastBackground)) return
    pending = background
    if (frame !== null) return
    frame = window.requestAnimationFrame(function () {
      frame = null
      lastBackground = pending
      pending = null
      window.location.href = window.location.origin + '/__minis_mail_appearance?version=1&backgroundColor=' + encodeURIComponent(lastBackground)
    })
  }

  window.addEventListener('hostid:appearance', function (event) {
    publish(event.detail, false)
  })
  window[key] = {
    // Resend even when unchanged: the launcher document may have been recreated.
    sync: function () { publish(window.__HOSTID_APPEARANCE__, true) }
  }
  window[key].sync()
})()
