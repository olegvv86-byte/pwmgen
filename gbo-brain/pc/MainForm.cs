using System.Reflection;
using System.Text;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace GboHeater;

/// <summary>
/// Окно программы. Интерфейс — тот же самый index.html, что и в приложении
/// на телефоне: он лежит в сборке как ресурс и ничем не отличается. Разница
/// только в оболочке и в том, что здесь есть загрузчик прошивки.
///
/// Чтобы страница не знала, где она работает, ей подставляется объект
/// AndroidGBO с теми же методами, что даёт Android. Со стороны страницы всё
/// выглядит одинаково.
/// </summary>
public class MainForm : Form
{
    readonly WebView2 _web = new();
    readonly Board _board = new();
    readonly System.Windows.Forms.Timer _poll = new() { Interval = 1000 };

    string _remembered;
    bool _ready;
    bool _busy;          // идёт заливка — опрос притормаживаем
    int _lost;

    static string SettingsPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "GboHeater", "board.txt");

    public MainForm()
    {
        Text = "GBO HEATER";
        // та же иконка, что у файла программы — Windows её уже знает
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
        BackColor = Color.FromArgb(4, 4, 8);
        ClientSize = new Size(920, 560);
        MinimumSize = new Size(620, 400);
        StartPosition = FormStartPosition.CenterScreen;

        _web.Dock = DockStyle.Fill;
        _web.DefaultBackgroundColor = Color.FromArgb(4, 4, 8);
        Controls.Add(_web);

        _poll.Tick += async (_, _) => await Poll();
        Load += async (_, _) => await Boot();
    }

    // ─────────────────────────── запуск ───────────────────────────

    async Task Boot()
    {
        try { _remembered = File.ReadAllText(SettingsPath).Trim(); } catch { }

        await _web.EnsureCoreWebView2Async();
        var core = _web.CoreWebView2;

        core.Settings.AreDefaultContextMenusEnabled = false;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.AreDevToolsEnabled = false;

        await core.AddScriptToExecuteOnDocumentCreatedAsync(Shim);
        core.WebMessageReceived += OnMessage;

        core.NavigateToString(LoadUi());
        core.NavigationCompleted += async (_, _) => { _ready = true; await Search(false); };
    }

    /// <summary>Тот же index.html, что уходит в APK, — лежит рядом или в сборке.</summary>
    static string LoadUi()
    {
        var near = Path.Combine(AppContext.BaseDirectory, "index.html");
        if (File.Exists(near)) return File.ReadAllText(near, Encoding.UTF8);

        var asm = Assembly.GetExecutingAssembly();
        var name = asm.GetManifestResourceNames().First(n => n.EndsWith("index.html"));
        using var s = asm.GetManifestResourceStream(name);
        using var r = new StreamReader(s, Encoding.UTF8);
        return r.ReadToEnd();
    }

    /// <summary>Мост с теми же именами, что даёт Android — страница не видит разницы.</summary>
    const string Shim = @"
window.GBO_PC = true;
function _gbo(o){ chrome.webview.postMessage(JSON.stringify(o)); }
window.AndroidGBO = {
  setParams:  function(sp,pw,ct){ _gbo({m:'setParams',sp:sp,pw:pw,ct:ct}); },
  setEnabled: function(on){ _gbo({m:'setEnabled',on:!!on}); },
  setForce:   function(on){ _gbo({m:'setForce',on:!!on}); },
  resetFault: function(){ _gbo({m:'resetFault'}); },
  rescan:     function(){ _gbo({m:'rescan'}); },
  askIp:      function(){ _gbo({m:'askIp'}); },
  pickFirmware: function(){ _gbo({m:'pickFirmware'}); },
  getCurve:   function(){ _gbo({m:'getCurve'}); },
  clearHints: function(){ _gbo({m:'clearHints'}); },
  resetVmin:  function(){ _gbo({m:'resetVmin'}); },
  setSolo:    function(on){ _gbo({m:'setSolo',on:!!on}); },
  saveLog:    function(t){ _gbo({m:'saveLog',t:String(t||'')}); }
};";

    // ─────────────────────────── поиск платы ───────────────────────────

    async Task Search(bool forget)
    {
        _poll.Stop();
        _board.Status += OnSearchStep;
        Push("onNet('search',null)");

        var ip = await Task.Run(() => _board.Find(_remembered, forget));

        _board.Status -= OnSearchStep;

        if (ip == null)
        {
            Push("onNet('notfound',null)");
            return;
        }

        _remembered = ip;
        Remember(ip);
        _lost = 0;
        Push($"onNet('online','{ip}')");
        Text = $"GBO HEATER — {ip}";
        _poll.Start();
        await Poll();
    }

    void OnSearchStep(string s) => BeginInvoke(() => Text = "GBO HEATER — " + s);

    static void Remember(string ip)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath));
            File.WriteAllText(SettingsPath, ip);
        }
        catch { }
    }

    // ─────────────────────────── опрос ───────────────────────────

    async Task Poll()
    {
        if (_busy || !_ready) return;

        var json = await _board.Api();
        if (json == null)
        {
            // связь могла моргнуть; три промаха подряд — ищем заново
            if (++_lost >= 3) { _poll.Stop(); await Search(true); return; }
            Push("onNet('offline',null)");
            return;
        }

        _lost = 0;
        Push("onNet('online',null)");
        Push($"window.onApi && onApi({json})");
    }

    void Push(string js)
    {
        if (!_ready) return;
        try { _web.CoreWebView2?.ExecuteScriptAsync(js); } catch { }
    }

    // ─────────────────────────── команды из интерфейса ───────────────────────────

    async void OnMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        JsonElement m;
        try { m = JsonDocument.Parse(e.TryGetWebMessageAsString()).RootElement; }
        catch { return; }

        switch (m.GetProperty("m").GetString())
        {
            case "setParams":
                await _board.Set($"sp={m.GetProperty("sp").GetInt32()}" +
                                 $"&pw={m.GetProperty("pw").GetInt32()}" +
                                 $"&ct={m.GetProperty("ct").GetInt32()}");
                break;

            case "setEnabled":
                await _board.Set("en=" + (m.GetProperty("on").GetBoolean() ? 1 : 0));
                break;

            case "setForce":
                await _board.Set("force=" + (m.GetProperty("on").GetBoolean() ? 1 : 0));
                break;

            case "resetFault":
                var r = await _board.Reset();
                var res = r == null ? "err" : (r.TrimStart().StartsWith("hot") ? "hot" : "ok");
                Push($"window.onReset && onReset('{res}')");
                break;

            case "rescan":
                await Search(true);
                break;

            case "askIp":
                AskIp();
                break;

            case "getCurve":
                var cv = await _board.Curve();
                if (cv != null) Push("window.onCurve && onCurve(" + JsonSerializer.Serialize(cv) + ")");
                break;

            case "clearHints":
                await _board.Set("hints=0");
                break;

            case "resetVmin":
                await _board.Set("vmin=0");
                break;

            case "setSolo":
                await _board.Set("solo=" + (m.GetProperty("on").GetBoolean() ? 1 : 0));
                break;

            case "saveLog":
                SaveLog(m.GetProperty("t").GetString() ?? "");
                break;

            case "pickFirmware":
                await Flash();
                break;
        }
    }

    /// <summary>
    /// Журнал в текстовый файл. Двадцать строк кольцевого буфера платы — то,
    /// что видно на экране; больше на плате и не хранится.
    /// </summary>
    void SaveLog(string text)
    {
        using var dlg = new SaveFileDialog
        {
            Filter = "Текстовый файл (*.txt)|*.txt",
            FileName = "gbo-log-" + DateTime.Now.ToString("yyyy-MM-dd-HHmm") + ".txt"
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        try
        {
            var head = "Журнал платы подогревателя ГБО" + Environment.NewLine +
                       DateTime.Now.ToString("dd.MM.yyyy HH:mm") +
                       "   плата " + (_board.Host ?? "не найдена") + Environment.NewLine +
                       new string('-', 52) + Environment.NewLine;
            File.WriteAllText(dlg.FileName, head + text, Encoding.UTF8);
        }
        catch (Exception e)
        {
            MessageBox.Show(this, "Не вышло сохранить: " + e.Message,
                            "ГБО", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    void AskIp()
    {
        using var dlg = new IpDialog(_board.Host ?? "");
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        _board.Use(dlg.Ip);
        _remembered = dlg.Ip;
        Remember(dlg.Ip);
        _lost = 0;
        _poll.Start();
    }

    // ─────────────────────────── заливка прошивки ───────────────────────────

    async Task Flash()
    {
        using var dlg = new OpenFileDialog
        {
            Title = "Файл прошивки",
            Filter = "Прошивка (*.bin)|*.bin|Все файлы|*.*",
            CheckFileExists = true
        };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;

        var name = Path.GetFileName(dlg.FileName);
        if (name.Contains("bootloader") || name.Contains("partitions") || name.Contains("merged"))
        {
            MessageBox.Show(this,
                "Это служебный файл, он не для заливки по сети.\n\n" +
                "Нужен обычный .ino.bin — тот, что около мегабайта.",
                "Не тот файл", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        _busy = true;
        _poll.Stop();
        Push("window.onFlash && onFlash('start',0)");

        var progress = new Progress<int>(p => Push($"window.onFlash && onFlash('progress',{p})"));
        var result = await _board.Upload(dlg.FileName, progress, CancellationToken.None);

        Push($"window.onFlash && onFlash('done',0,{JsonSerializer.Serialize(result)})");
        _busy = false;

        // плата перезагружается — ждём и ищем заново
        await Task.Delay(6000);
        await Search(false);
    }
}

/// <summary>Ручной ввод адреса — на случай, если поиск не справился.</summary>
public class IpDialog : Form
{
    readonly TextBox _box = new();
    public string Ip => _box.Text.Trim();

    public IpDialog(string current)
    {
        Text = "Адрес платы";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        MaximizeBox = MinimizeBox = false;
        ClientSize = new Size(300, 110);
        BackColor = Color.FromArgb(10, 15, 26);
        ForeColor = Color.FromArgb(232, 238, 245);

        var lbl = new Label
        {
            Text = "Адрес платы в сети:",
            Location = new Point(12, 14),
            AutoSize = true
        };
        _box.Text = current;
        _box.Location = new Point(12, 36);
        _box.Width = 276;
        _box.BackColor = Color.FromArgb(4, 4, 8);
        _box.ForeColor = Color.FromArgb(232, 238, 245);
        _box.BorderStyle = BorderStyle.FixedSingle;

        var ok = new Button { Text = "OK", DialogResult = DialogResult.OK, Location = new Point(132, 70), Width = 74 };
        var no = new Button { Text = "Отмена", DialogResult = DialogResult.Cancel, Location = new Point(214, 70), Width = 74 };

        Controls.AddRange(new Control[] { lbl, _box, ok, no });
        AcceptButton = ok;
        CancelButton = no;
    }
}
