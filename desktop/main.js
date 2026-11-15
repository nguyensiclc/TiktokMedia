// Electron shell: starts the Spring Boot backend on a free localhost port and shows the Vue UI it serves.
const { app, BrowserWindow, shell, dialog } = require('electron')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')
const net = require('net')
const http = require('http')

let backend = null
let win = null

// Prefer a fixed port so OAuth redirect URIs registered with a fixed port (e.g. TikTok
// http://127.0.0.1:8787/api/tiktok/callback/) keep working; fall back to any free port.
const PREFERRED_PORT = 8787

function tryPort(port) {
  return new Promise((resolve) => {
    const srv = net.createServer()
    srv.once('error', () => resolve(null))
    srv.listen(port, '127.0.0.1', () => {
      const { port: p } = srv.address()
      srv.close(() => resolve(p))
    })
  })
}

async function freePort() {
  const p = (await tryPort(PREFERRED_PORT)) ?? (await tryPort(0))
  if (!p) throw new Error('Không tìm được cổng trống cho backend')
  return p
}

function javaPath() {
  // Prefer a bundled JRE (desktop/jre) so users don't need Java installed.
  const bundled = app.isPackaged
    ? path.join(process.resourcesPath, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    : path.join(__dirname, 'jre', 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
  if (fs.existsSync(bundled)) return bundled
  if (process.env.JAVA_HOME) {
    const j = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java')
    if (fs.existsSync(j)) return j
  }
  return 'java'
}

function jarPath() {
  return app.isPackaged
    ? path.join(process.resourcesPath, 'backend.jar')
    : path.join(__dirname, '..', 'backend', 'target', 'backend.jar')
}

function waitForHealth(port, timeoutMs = 90000) {
  const started = Date.now()
  return new Promise((resolve, reject) => {
    const tick = () => {
      http
        .get({ host: '127.0.0.1', port, path: '/api/health', timeout: 2000 }, (res) => {
          res.resume()
          if (res.statusCode === 200) resolve()
          else retry()
        })
        .on('error', retry)
    }
    const retry = () => {
      if (backend && backend.exitCode !== null) return reject(new Error('Backend đã dừng (mã ' + backend.exitCode + ')'))
      if (Date.now() - started > timeoutMs) return reject(new Error('Backend không khởi động kịp'))
      setTimeout(tick, 500)
    }
    tick()
  })
}

async function startBackend() {
  const jar = jarPath()
  if (!fs.existsSync(jar)) throw new Error('Không tìm thấy ' + jar + '\nChạy: cd backend && mvn package')
  const port = await freePort()
  const logFile = path.join(app.getPath('userData'), 'backend.log')
  const out = fs.openSync(logFile, 'a')
  backend = spawn(javaPath(), ['-Dfile.encoding=UTF-8', '-jar', jar, `--server.port=${port}`], {
    stdio: ['ignore', out, out],
    windowsHide: true
  })
  backend.on('error', (e) => dialog.showErrorBox('Không chạy được Java', e.message))
  await waitForHealth(port)
  return `http://127.0.0.1:${port}/`
}

async function createWindow() {
  win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1000,
    minHeight: 640,
    backgroundColor: '#0f1115',
    title: 'Media Studio',
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, sandbox: true }
  })
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })
  win.loadFile(path.join(__dirname, 'loading.html'))

  try {
    // Dev: Vite on :5173 proxies /api to a backend you started yourself (mvn spring-boot:run).
    const url = process.env.MS_DEV_URL || (await startBackend())
    await win.loadURL(url)
  } catch (e) {
    dialog.showErrorBox('Media Studio', e.message)
    app.quit()
  }
}

function stopBackend() {
  if (backend && backend.exitCode === null) {
    if (process.platform === 'win32') spawn('taskkill', ['/pid', String(backend.pid), '/T', '/F'])
    else backend.kill()
  }
}

app.whenReady().then(createWindow)
app.on('window-all-closed', () => app.quit())
app.on('before-quit', stopBackend)
process.on('exit', stopBackend)
