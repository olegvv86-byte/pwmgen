using Microsoft.Web.WebView2.Core;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using Microsoft.Win32;

namespace POBEDA
{
    public partial class MainWindow : Window
    {
        TcpClient _picoTcp;
        NetworkStream _picoStream;
        readonly object _sendLock = new();
        bool _picoConnected;
        long _lastStatusRxMs;
        const int LinkFreshMs = 2500;
        string _picoIp = "192.168.4.1";
        const int PICO_PORT = 8888;

        readonly object _rxLock = new();
        readonly List<string> _rxLines = new();
        int _rxSc;
        Thread _rxThread;
        volatile bool _rxAlive;

        HttpListener _http;
        int _httpPort = 18088;
        volatile bool _alive = true;
        volatile bool _otaBusy;
        readonly object _otaLock = new();
        string _editorFilePath;
        byte[] _indexHtmlBytes;

        string ConfPath
        {
            get
            {
                var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "POBEDA");
                try { Directory.CreateDirectory(dir); } catch { }
                return Path.Combine(dir, "pobeda_conf.txt");
            }
        }

        byte[] GetIndexHtml()
        {
            if (_indexHtmlBytes != null) return _indexHtmlBytes;
            var asm = Assembly.GetExecutingAssembly();
            var res = asm.GetManifestResourceNames().FirstOrDefault(n => n.EndsWith("index_wifi.html", StringComparison.OrdinalIgnoreCase));
            if (res != null)
            {
                using var s = asm.GetManifestResourceStream(res);
                if (s != null)
                {
                    using var ms = new MemoryStream();
                    s.CopyTo(ms);
                    _indexHtmlBytes = ms.ToArray();
                    return _indexHtmlBytes;
                }
            }
            _indexHtmlBytes = Encoding.UTF8.GetBytes("<html><body>POBEDA: UI not embedded</body></html>");
            return _indexHtmlBytes;
        }

        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
            Closing += (s, e) => { _alive = false; _rxAlive = false; try { _http?.Stop(); } catch { } try { _picoTcp?.Close(); } catch { } };
            // Синхронизация скролла нумерации строк с редактором
            txtEditor.AddHandler(System.Windows.Controls.ScrollViewer.ScrollChangedEvent,
                new System.Windows.Controls.ScrollChangedEventHandler((s, e) => SyncLineNumberScroll()));
        }

        // ═══ СТАРТ ═══
        async void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            LoadConf();
            lblLoading.Text = "Поиск генератора...";

            await Task.Run(() =>
            {
                string found = FindPico();
                if (found != "") { _picoIp = found; SaveConf(); }
                ConnectPico(_picoIp);
            });

            lblLoading.Text = IsLinkLive() ? $"Подключено: {_picoIp}" : "Не найден — нажми WiFi";

            StartHttpServer();
            try
            {
                var env = await CoreWebView2Environment.CreateAsync(null,
                    Path.Combine(Path.GetTempPath(), "POBEDA_WV2"));
                await webView.EnsureCoreWebView2Async(env);
                webView.CoreWebView2.Navigate($"http://localhost:{_httpPort}/");
            }
            catch (Exception ex) { lblLoading.Text = $"WebView2: {ex.Message}"; }
            loadingOverlay.Visibility = Visibility.Collapsed;
            UpdateConnLabel();
            var connTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(1)
            };
            connTimer.Tick += (s, e) => NotifyLinkChanged();
            connTimer.Start();
            Task.Run(() =>
            {
                Thread.Sleep(500);
                PicoSend("GET");
                if (WaitForLinkLive(5000)) NotifyLinkChanged();
            });
        }

        // ═══ БЫСТРЫЙ ПОИСК ЧЕРЕЗ МАЯЧОК ═══
        const int BEACON_PORT = 8890;

        string FindPicoViaBeacon(int timeoutMs)
        {
            try
            {
                using var udp = new UdpClient();
                udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                udp.Client.Bind(new IPEndPoint(IPAddress.Any, BEACON_PORT));
                udp.Client.ReceiveTimeout = timeoutMs;
                var remote = new IPEndPoint(IPAddress.Any, 0);
                var data = udp.Receive(ref remote);
                var msg = Encoding.UTF8.GetString(data);
                if (msg.StartsWith("PWMGEN"))
                    return remote.Address.ToString();
            }
            catch { }
            return "";
        }

        // ═══ ПОИСК — оригинальная логика, НЕ ТРОГАТЬ ═══
        string FindPico()
        {
            // Маячок — плата сама "кричит" свой IP раз в секунду
            var beacon = FindPicoViaBeacon(2000);
            if (beacon != "" && Ping(beacon)) return beacon;

            // Сохранённый IP
            if (_picoIp != "192.168.4.1" && Ping(_picoIp)) return _picoIp;
            // Роутерные адреса
            foreach (var ip in new[] {
                "192.168.0.13","192.168.0.14","192.168.0.15",
                "192.168.1.13","192.168.1.14","192.168.1.15",
                "192.168.0.24","192.168.1.24",
                "192.168.0.100","192.168.1.100" })
            {
                if (ip == _picoIp) continue;
                if (Ping(ip)) return ip;
            }
            // AP-адрес — ТОЛЬКО если ПК реально в сети 192.168.4.x,
            // иначе это Docker/VMware и не надо к нему подключаться
            if (IsOnSubnet("192.168.4.") && Ping("192.168.4.1")) return "192.168.4.1";
            return "";
        }

        bool IsOnSubnet(string prefix)
        {
            try
            {
                foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                    foreach (var a in ni.GetIPProperties().UnicastAddresses)
                        if (a.Address.AddressFamily == AddressFamily.InterNetwork && a.Address.ToString().StartsWith(prefix))
                            return true;
                }
            }
            catch { }
            return false;
        }

        bool Ping(string ip)
        {
            try
            {
                using var tcp = new TcpClient();
                if (!tcp.ConnectAsync(ip, PICO_PORT).Wait(1500)) return false;
                return tcp.Connected;
            }
            catch { return false; }
        }

        // ═══ ПОДКЛЮЧЕНИЕ — оригинальная логика, НЕ ТРОГАТЬ ═══
        void ConnectPico(string ip)
        {
            try
            {
                _rxAlive = false;
                try { _picoStream?.Close(); } catch { }
                try { _picoTcp?.Close(); } catch { }
                Thread.Sleep(50);

                _picoTcp = new TcpClient();
                if (!_picoTcp.ConnectAsync(ip, PICO_PORT).Wait(3000))
                {
                    _picoConnected = false;
                    return;
                }
                _picoStream = _picoTcp.GetStream();
                _picoStream.ReadTimeout = 3000;
                _picoConnected = _picoTcp.Connected;
                _picoIp = ip;
                ClearRx();
                _lastStatusRxMs = 0;

                _rxAlive = true;
                _rxThread = new Thread(RxLoop) { IsBackground = true };
                _rxThread.Start();
            }
            catch { _picoConnected = false; }
        }

        void DisconnectPico()
        {
            _picoConnected = false;
            _lastStatusRxMs = 0;
            _rxAlive = false;
            try { _picoStream?.Close(); } catch { }
            try { _picoTcp?.Close(); } catch { }
            ClearRx();
        }

        bool IsLinkLive()
        {
            if (!_picoConnected || _picoStream == null) return false;
            if (_lastStatusRxMs == 0) return false;
            return Environment.TickCount64 - _lastStatusRxMs < LinkFreshMs;
        }

        bool WaitForLinkLive(int ms)
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < ms)
            {
                if (IsLinkLive()) return true;
                Thread.Sleep(50);
            }
            return IsLinkLive();
        }

        bool _linkUiLive;

        void NotifyLinkChanged()
        {
            bool live = IsLinkLive();
            if (live == _linkUiLive) return;
            _linkUiLive = live;
            UpdateConnLabel();
        }

        void UpdateConnLabel()
        {
            Dispatcher.BeginInvoke(() =>
                lblConn.Text = IsLinkLive() ? $"✓ {_picoIp}" : "✗ нет связи");
        }

        void CloseWifiOk()
        {
            SaveConf();
            UpdateConnLabel();
            wifiOverlay.Visibility = Visibility.Collapsed;
            webView.Visibility = Visibility.Visible;
            try { webView.CoreWebView2?.Navigate($"http://localhost:{_httpPort}/"); } catch { }
        }

        void ConnectAndWait(string ip)
        {
            if (ip != _picoIp || !_picoConnected)
            {
                DisconnectPico();
                Thread.Sleep(200);
                ConnectPico(ip);
            }
            else if (!IsLinkLive())
            {
                ClearRx();
                _lastStatusRxMs = 0;
            }
            TryCancelStuckOta();
            ClearRx();
            PicoSend("GET");
            WaitForLinkLive(5000);
        }

        void TryCancelStuckOta()
        {
            if (_picoStream == null) return;
            try
            {
                var d = Encoding.UTF8.GetBytes("UF:2\n");
                lock (_sendLock)
                {
                    _picoStream.Write(d, 0, d.Length);
                    _picoStream.Flush();
                }
                Thread.Sleep(80);
            }
            catch { }
        }

        void ConnectPicoForVer(string ip)
        {
            DisconnectPico();
            Thread.Sleep(150);
            ConnectPico(ip);
            Thread.Sleep(200);
        }

        void LoadConf() { try { if (File.Exists(ConfPath)) { var ip = File.ReadAllText(ConfPath).Trim(); if (ip.Length > 0) _picoIp = ip; } } catch { } }
        void SaveConf() { try { File.WriteAllText(ConfPath, _picoIp); } catch { } }

        // ═══ ПРИЁМ ДАННЫХ ═══
        void RxLoop()
        {
            var sb = new StringBuilder();
            var buf = new byte[4096];
            while (_rxAlive)
            {
                try
                {
                    if (_picoStream == null) { Thread.Sleep(50); continue; }
                    int n;
                    try { n = _picoStream.Read(buf, 0, buf.Length); }
                    catch (IOException)
                    {
                        if (_otaBusy) { Thread.Sleep(20); continue; }
                        _picoConnected = false; Thread.Sleep(200); continue;
                    }
                    if (n <= 0) { _picoConnected = false; Thread.Sleep(200); continue; }
                    sb.Append(Encoding.UTF8.GetString(buf, 0, n));
                    int idx;
                    while ((idx = sb.ToString().IndexOf('\n')) >= 0)
                    {
                        string line = sb.ToString(0, idx).TrimEnd('\r');
                        sb.Remove(0, idx + 1);
                        if (line.Length == 0) continue;
                        if (line.StartsWith("{")) { _lastStatusRxMs = Environment.TickCount64; NotifyLinkChanged(); }
                        lock (_rxLock) { _rxLines.Add(line); if (_rxLines.Count > 500) _rxLines.RemoveAt(0); }
                    }
                }
                catch { Thread.Sleep(300); }
            }
        }

        string WaitForLine(Func<string, bool> pred, int ms)
        {
            var sw = System.Diagnostics.Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < ms)
            {
                lock (_rxLock)
                {
                    for (int i = _rxSc; i < _rxLines.Count; i++)
                    {
                        if (!pred(_rxLines[i])) continue;
                        var line = _rxLines[i];
                        _rxSc = i + 1;
                        return line;
                    }
                }
                Thread.Sleep(20);
            }
            return null;
        }

        void ClearRx() { lock (_rxLock) { _rxLines.Clear(); _rxSc = 0; } }

        void PicoSend(string cmd, bool ota = false)
        {
            if (_otaBusy && !ota) return;
            if (_picoStream == null) return;
            if (!ota && !_picoConnected) return;
            lock (_sendLock)
            {
                try
                {
                    var d = Encoding.UTF8.GetBytes(cmd.Trim() + "\n");
                    _picoStream.Write(d, 0, d.Length);
                    _picoStream.Flush();
                }
                catch (Exception ex)
                {
                    _picoConnected = false;
                    Log($"TX ОШИБКА: {ex.GetType().Name}: {ex.Message}");
                }
            }
        }

        // ═══ HTTP-ПРОКСИ ═══
        void StartHttpServer()
        {
            _http = new HttpListener();
            for (int i = 0; i < 10; i++)
            {
                try { _http.Prefixes.Clear(); _http.Prefixes.Add($"http://localhost:{_httpPort}/"); _http.Start(); break; }
                catch { _httpPort++; _http = new HttpListener(); }
            }
            Task.Run(() => { while (_alive && _http.IsListening) { try { var c = _http.GetContext(); Task.Run(() => HandleReq(c)); } catch { } } });
        }

        void HandleReq(HttpListenerContext ctx)
        {
            try
            {
                var p = ctx.Request.Url?.AbsolutePath ?? "/";
                if (p == "/" || p == "/index.html" || p == "/index_wifi.html")
                {
                    var b = GetIndexHtml();
                    ctx.Response.ContentType = "text/html; charset=utf-8";
                    ctx.Response.ContentLength64 = b.Length;
                    ctx.Response.OutputStream.Write(b, 0, b.Length);
                }
                else if (p == "/ota/busy")
                {
                    var d = Encoding.UTF8.GetBytes(_otaBusy ? "{\"busy\":true}" : "{\"busy\":false}");
                    ctx.Response.ContentType = "application/json";
                    ctx.Response.OutputStream.Write(d, 0, d.Length);
                }
                else if (p.StartsWith("/cmd/"))
                {
                    if (_otaBusy) { Wr(ctx, "BUSY"); return; }
                    var r = p.Substring(5).Trim('/').Split('/');
                    if (r.Length == 2 && r[0].Length > 0) PicoSend(r[0] + ":" + r[1]);
                    Wr(ctx, "OK");
                }
                else if (p == "/link")
                {
                    var live = IsLinkLive();
                    var d = Encoding.UTF8.GetBytes(live ? "{\"up\":true}" : "{\"up\":false}");
                    ctx.Response.ContentType = "application/json";
                    ctx.Response.OutputStream.Write(d, 0, d.Length);
                }
                else if (p == "/status")
                {
                    string last = null;
                    if (IsLinkLive())
                    {
                        lock (_rxLock) { for (int i = _rxLines.Count - 1; i >= 0; i--) if (_rxLines[i].StartsWith("{")) { last = _rxLines[i]; break; } }
                    }
                    var d = Encoding.UTF8.GetBytes(last ?? "{}"); ctx.Response.ContentType = "application/json"; ctx.Response.OutputStream.Write(d, 0, d.Length);
                }
                else if (p == "/stop")
                {
                    if (_otaBusy) { Wr(ctx, "BUSY"); return; }
                    PicoSend("ST:1"); Wr(ctx, "OK");
                }
                else if (p == "/run")
                {
                    if (_otaBusy) { Wr(ctx, "BUSY"); return; }
                    PicoSend("ST:0"); Wr(ctx, "OK");
                }
                else if (p == "/upload" && ctx.Request.HttpMethod == "POST")
                {
                    string c; using (var sr = new StreamReader(ctx.Request.InputStream, Encoding.UTF8)) c = sr.ReadToEnd();
                    if (string.IsNullOrWhiteSpace(c)) { Wr(ctx, "ОШИБКА: пустой файл"); return; }
                    var sv = ExtractVerTag(c);
                    var sc = FirmwareCrc32(c);
                    Wr(ctx, UploadFirmware(c, sv, sc));
                }
                else ctx.Response.StatusCode = 404;
            }
            catch { }
            finally { try { ctx.Response.OutputStream.Close(); } catch { } }
        }

        void Wr(HttpListenerContext c, string t) { var b = Encoding.UTF8.GetBytes(t); c.Response.ContentType = "text/plain; charset=utf-8"; c.Response.OutputStream.Write(b, 0, b.Length); }

        // ═══ WiFi-ДИАЛОГ ═══
        void btnWifi_Click(object sender, RoutedEventArgs e)
        {
            txtIp.Text = _picoIp;
            lblWifiStatus.Text = IsLinkLive() ? $"Подключено: {_picoIp}" : "Нет связи";
            webView.Visibility = Visibility.Collapsed;
            wifiOverlay.Visibility = Visibility.Visible;
        }

        void btnWifiCancel_Click(object sender, RoutedEventArgs e)
        {
            wifiOverlay.Visibility = Visibility.Collapsed;
            webView.Visibility = Visibility.Visible;
            NotifyLinkChanged();
        }

        async void btnWifiConnect_Click(object sender, RoutedEventArgs e)
        {
            string ip = txtIp.Text.Trim();
            if (!IPAddress.TryParse(ip, out _)) { lblWifiStatus.Text = "Неверный IP"; return; }
            if (ip == _picoIp && IsLinkLive()) { CloseWifiOk(); return; }
            btnWifiConnect.IsEnabled = false;
            lblWifiStatus.Text = $"Подключаюсь к {ip}...";
            _picoIp = ip;

            bool ok = false;
            await Task.Run(() => { ConnectAndWait(ip); ok = IsLinkLive(); });

            if (ok) CloseWifiOk();
            else { lblWifiStatus.Text = $"{ip} не отвечает"; NotifyLinkChanged(); }
            btnWifiConnect.IsEnabled = true;
        }

        async void btnWifiAutosearch_Click(object sender, RoutedEventArgs e)
        {
            btnWifiAutosearch.IsEnabled = false;
            lblWifiStatus.Text = "Ищу...";
            string found = "";
            bool ok = false;
            await Task.Run(() =>
            {
                found = FindPico();
                if (found != "")
                {
                    _picoIp = found;
                    ConnectAndWait(found);
                    ok = IsLinkLive();
                }
            });
            if (ok) CloseWifiOk();
            else { lblWifiStatus.Text = "Не найден. Введи IP."; NotifyLinkChanged(); }
            btnWifiAutosearch.IsEnabled = true;
        }

        // ═══ РЕДАКТОР ═══
        void btnFile_Click(object s, RoutedEventArgs e) { webView.Visibility = Visibility.Collapsed; editorOverlay.Visibility = Visibility.Visible; txtLog.Clear(); prgUpload.Value = 0; }
        void btnCloseEditor_Click(object s, RoutedEventArgs e) { editorOverlay.Visibility = Visibility.Collapsed; webView.Visibility = Visibility.Visible; }

        void btnOpen_Click(object s, RoutedEventArgs e)
        {
            var d = new OpenFileDialog { Filter = "Python|*.py|Все|*.*" };
            var fwDir = @"c:\Users\XE\Music\Новая папка";
            if (Directory.Exists(fwDir)) d.InitialDirectory = fwDir;
            if (d.ShowDialog() == true)
            {
                try
                {
                    txtEditor.Text = File.ReadAllText(d.FileName, Encoding.UTF8);
                    _editorFilePath = d.FileName;
                    lblFileName.Text = Path.GetFileName(d.FileName);
                    var ver = ExtractVerTag(txtEditor.Text);
                    Log($"Открыт: {d.FileName}" + (ver != null ? $" [{ver}]" : ""));
                }
                catch (Exception ex) { MessageBox.Show(ex.Message); }
            }
        }

        void btnSave_Click(object s, RoutedEventArgs e)
        {
            var d = new SaveFileDialog { Filter = "Python|*.py|Все|*.*", FileName = _editorFilePath != null ? Path.GetFileName(_editorFilePath) : "main.py" };
            if (d.ShowDialog() == true) { try { File.WriteAllText(d.FileName, txtEditor.Text, new UTF8Encoding(false)); _editorFilePath = d.FileName; lblFileName.Text = Path.GetFileName(d.FileName); Log($"Сохранено: {d.FileName}"); } catch (Exception ex) { MessageBox.Show(ex.Message); } }
        }

        // ── Нумерация строк ──
        void txtEditor_TextChanged(object sender, System.Windows.Controls.TextChangedEventArgs e)
        {
            int count = txtEditor.LineCount;
            if (count < 1) count = 1;
            var sb = new StringBuilder();
            for (int i = 1; i <= count; i++) { sb.AppendLine(i.ToString()); }
            txtLineNums.Text = sb.ToString();
            SyncLineNumberScroll();
        }

        void SyncLineNumberScroll()
        {
            try
            {
                var svEditor = FindScrollViewer(txtEditor);
                var svNums = FindScrollViewer(txtLineNums);
                if (svEditor != null && svNums != null)
                    svNums.ScrollToVerticalOffset(svEditor.VerticalOffset);
            }
            catch { }
        }

        System.Windows.Controls.ScrollViewer FindScrollViewer(System.Windows.DependencyObject o)
        {
            if (o is System.Windows.Controls.ScrollViewer sv) return sv;
            for (int i = 0; i < System.Windows.Media.VisualTreeHelper.GetChildrenCount(o); i++)
            {
                var r = FindScrollViewer(System.Windows.Media.VisualTreeHelper.GetChild(o, i));
                if (r != null) return r;
            }
            return null;
        }

        void Log(string s) { Dispatcher.BeginInvoke(() => { txtLog.AppendText(s + "\n"); txtLog.ScrollToEnd(); }); }
        void SetProg(double v) { Dispatcher.BeginInvoke(() => prgUpload.Value = v); }

        string LoadFirmwareContent()
        {
            if (!string.IsNullOrEmpty(_editorFilePath) && File.Exists(_editorFilePath))
            {
                try
                {
                    var disk = File.ReadAllText(_editorFilePath, Encoding.UTF8);
                    Dispatcher.Invoke(() =>
                    {
                        txtEditor.Text = disk;
                        lblFileName.Text = Path.GetFileName(_editorFilePath);
                    });
                    return disk;
                }
                catch { }
            }
            return txtEditor.Text ?? "";
        }

        static bool VerTagEquals(string verLine, string expected)
        {
            if (string.IsNullOrEmpty(expected)) return true;
            var got = ExtractVerTag(verLine);
            return got != null && string.Equals(got, expected, StringComparison.OrdinalIgnoreCase);
        }

        static string ExtractVerTag(string content)
        {
            if (string.IsNullOrEmpty(content)) return null;
            var m = System.Text.RegularExpressions.Regex.Match(content, @"VER:\d+_\d+_[A-Za-z0-9]+");
            return m.Success ? m.Value : null;
        }

        static uint FirmwareCrc32(string content)
        {
            var lines = content.Replace("\r\n", "\n").Replace("\r", "\n").Split('\n');
            using var ms = new MemoryStream();
            foreach (var l in lines)
            {
                var d = Encoding.UTF8.GetBytes(l + "\n");
                ms.Write(d, 0, d.Length);
            }
            return Crc32Ieee(ms.ToArray());
        }

        static uint Crc32Ieee(byte[] data)
        {
            uint crc = 0xFFFFFFFF;
            foreach (byte b in data)
            {
                crc ^= b;
                for (int i = 0; i < 8; i++)
                    crc = (crc >> 1) ^ (0xEDB88320u & (uint)-(int)(crc & 1));
            }
            return crc ^ 0xFFFFFFFF;
        }

        static int FirmwareByteSize(string content)
        {
            int n = 0;
            foreach (var l in content.Replace("\r\n", "\n").Replace("\r", "\n").Split('\n'))
                n += Encoding.UTF8.GetByteCount(l + "\n");
            return n;
        }

        // ═══ OTA-ЗАГРУЗЧИК ═══
        async void btnUpload_Click(object s, RoutedEventArgs e)
        {
            try
            {
                string firmware = LoadFirmwareContent();
                if (string.IsNullOrWhiteSpace(firmware)) { Log("Сначала открой файл."); return; }
                if (!string.IsNullOrEmpty(_editorFilePath) && File.Exists(_editorFilePath))
                {
                    var onDisk = File.ReadAllText(_editorFilePath, Encoding.UTF8);
                    if (onDisk != firmware)
                    {
                        Log("⚠ Текст в редакторе отличается от файла на диске — беру версию с диска.");
                        firmware = onDisk;
                        Dispatcher.Invoke(() => txtEditor.Text = onDisk);
                    }
                }
                var srcVer = ExtractVerTag(firmware);
                var payload = BuildFirmwarePayload(firmware);
                var srcCrc = Crc32Ieee(payload);
                var srcBytes = payload.Length;
                btnUpload.IsEnabled = false; btnRollback.IsEnabled = false;
                txtLog.Clear(); SetProg(0);
                Log("Начинаю загрузку...");
                if (!string.IsNullOrEmpty(_editorFilePath)) Log($"Файл на диске: {_editorFilePath}");
                if (srcVer != null) Log($"Версия в файле: {srcVer}");
                else Log("⚠ В файле нет метки VER:");
                Log($"CRC32: {srcCrc:X8}  ({srcBytes} байт)");
                string result = await Task.Run(() => UploadFirmware(firmware, srcVer, srcCrc));
                Log(result);
                UpdateConnLabel();
            }
            catch (Exception ex) { Log($"ОШИБКА: {ex.Message}"); }
            finally { btnUpload.IsEnabled = true; btnRollback.IsEnabled = true; }
        }

        void OtaCancel()
        {
            try
            {
                if (_picoStream == null) return;
                var d = Encoding.UTF8.GetBytes("UF:2\n");
                lock (_sendLock)
                {
                    _picoStream.Write(d, 0, d.Length);
                    _picoStream.Flush();
                }
            }
            catch { }
        }

        static byte[] BuildFirmwarePayload(string content)
        {
            var t = content.Replace("\r\n", "\n").Replace("\r", "\n");
            if (!t.EndsWith("\n")) t += "\n";
            return Encoding.UTF8.GetBytes(t);
        }

        string UploadFirmware(string content, string srcVer = null, uint srcCrc = 0)
        {
            lock (_otaLock)
            {
                if (_otaBusy) return "ОШИБКА: загрузка уже идёт.";
                _otaBusy = true;
            }
            try
            {
                ConnectAndWait(_picoIp);
                TryCancelStuckOta();
                ClearRx();
                if (!_picoConnected) return "ОШИБКА: нет связи с Pico. Подключись через WiFi.";

                var payload = BuildFirmwarePayload(content);
                int expectedBytes = payload.Length;
                uint expectCrc = srcCrc != 0 ? srcCrc : Crc32Ieee(payload);
                Log($"Файл: {expectedBytes} байт, IP {_picoIp}");

                try { _picoTcp.SendBufferSize = 16384; _picoTcp.NoDelay = true; } catch { }
                int oldReadTimeout = 3000;
                try { oldReadTimeout = _picoStream.ReadTimeout; _picoStream.ReadTimeout = Timeout.Infinite; } catch { }

                ClearRx(); PicoSend($"UF:0:{expectedBytes}:{expectCrc:X8}", ota: true);
                var s = WaitForLine(l => l.StartsWith("OTA:START") || l.StartsWith("OTA:ERR"), 5000);
                if (s == null) { OtaCancel(); return "ОШИБКА: нет OTA:START."; }
                if (s.StartsWith("OTA:ERR")) return $"ОШИБКА: {s}";
                Log($"← {s}");

                Log("Передача...");
                _picoStream.WriteTimeout = 60000;
                lock (_sendLock)
                {
                    const int chunk = 4096;
                    for (int off = 0; off < payload.Length; off += chunk)
                    {
                        int n = Math.Min(chunk, payload.Length - off);
                        _picoStream.Write(payload, off, n);
                        SetProg(90.0 * (off + n) / payload.Length);
                    }
                    _picoStream.Flush();
                }

                var recv = WaitForLine(l => l.StartsWith("OTA:RECEIVED") || l.StartsWith("OTA:ERR"), 30000);
                if (recv == null) { OtaCancel(); return "ОШИБКА: Pico не приняла файл."; }
                if (recv.StartsWith("OTA:ERR")) return $"ОШИБКА: {recv}";
                Log($"← {recv}");

                ClearRx(); PicoSend("UF:1", ota: true);
                string v = null;
                string crcOnPico = null;
                string fileVerOnPico = null;
                bool gotReboot = false;
                var swVerify = System.Diagnostics.Stopwatch.StartNew();
                while (swVerify.ElapsedMilliseconds < 30000)
                {
                    lock (_rxLock)
                    {
                        foreach (var ln in _rxLines)
                        {
                            if (ln.StartsWith("OTA:ERR")) v = ln;
                            if (ln.StartsWith("OTA:CRC:")) crcOnPico = ln.Substring(8).Trim();
                            if (ln.StartsWith("OTA:FILEVER:")) fileVerOnPico = ln.Substring(12).Trim();
                            if (ln.StartsWith("OTA:VERIFIED")) v = ln;
                            if (ln.StartsWith("OTA:REBOOT")) gotReboot = true;
                        }
                    }
                    if (v != null && v.StartsWith("OTA:ERR")) break;
                    if (gotReboot && v != null && v.StartsWith("OTA:VERIFIED")) break;
                    Thread.Sleep(20);
                }
                try { _picoStream.ReadTimeout = oldReadTimeout; } catch { }

                if (v == null || !v.StartsWith("OTA:VERIFIED"))
                {
                    OtaCancel();
                    return v != null ? $"ОШИБКА: {v}" : "ОШИБКА: проверка не прошла, main.py не изменён.";
                }
                Log($"← {v}");
                if (fileVerOnPico != null) Log($"← {fileVerOnPico}");
                if (crcOnPico != null)
                {
                    Log($"← CRC {crcOnPico}");
                    if (!crcOnPico.Equals(expectCrc.ToString("X8"), StringComparison.OrdinalIgnoreCase))
                        return $"ОШИБКА: CRC {crcOnPico} != {expectCrc:X8}";
                }
                if (!gotReboot)
                {
                    var rb = WaitForLine(l => l.StartsWith("OTA:REBOOT"), 5000);
                    if (rb != null) Log($"← {rb}");
                }
                Log("Перезагрузка..."); SetProg(98);

                DisconnectPico();
                Thread.Sleep(3000);
                for (int i = 0; i < 8; i++)
                {
                    if (i > 0) Thread.Sleep(600);
                    // После перезагрузки IP мог смениться — сначала маячок
                    var freshIp = FindPicoViaBeacon(1200);
                    var tryIp = freshIp != "" ? freshIp : _picoIp;
                    ConnectPicoForVer(tryIp);
                    if (!_picoConnected) continue;
                    ClearRx(); PicoSend("VER", ota: true);
                    var ver = WaitForLine(l => l.StartsWith("VER:"), 2500);
                    if (ver == null) { DisconnectPico(); continue; }
                    Log($"← {ver}");
                    if (srcVer != null && !VerTagEquals(ver, srcVer)) { DisconnectPico(); continue; }
                    if (freshIp != "") _picoIp = freshIp;
                    SetProg(100); NotifyLinkChanged();
                    return $"УСПЕХ: {ver}  {expectedBytes} байт  CRC:{expectCrc:X8}";
                }
                return "ОШИБКА: после OTA старая версия. Залей main.py через Thonny (нужна 10_27_OTA2).";
            }
            catch (Exception ex)
            {
                OtaCancel();
                return $"ОШИБКА: {ex.Message}";
            }
            finally
            {
                try { if (_picoStream != null) _picoStream.ReadTimeout = 3000; } catch { }
                _otaBusy = false;
            }
        }

        async void btnRollback_Click(object s, RoutedEventArgs e)
        {
            if (MessageBox.Show("Откатить?", "OTA", MessageBoxButton.YesNo) != MessageBoxResult.Yes) return;
            btnUpload.IsEnabled = false; btnRollback.IsEnabled = false; txtLog.Clear();
            Log(await Task.Run(() =>
            {
                ConnectAndWait(_picoIp);
                if (!_picoConnected) return "ОШИБКА: нет связи.";
                ClearRx(); PicoSend("UF:3", ota: true);
                var r = WaitForLine(l => l.StartsWith("OTA:ROLLBACK") || l.StartsWith("OTA:ERR"), 3000);
                if (r == null) return "ОШИБКА: нет ответа.";
                Log($"← {r}"); DisconnectPico();
                for (int i = 0; i < 10; i++) { Thread.Sleep(1000); ConnectPico(_picoIp); if (_picoConnected) { ClearRx(); PicoSend("VER"); var v = WaitForLine(l => l.StartsWith("VER:"), 2000); if (v != null) return $"Откат: {v}"; DisconnectPico(); } }
                return "Откат отправлен, ожидание...";
            }));
            btnUpload.IsEnabled = true; btnRollback.IsEnabled = true;
        }
    }
}
