using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;

namespace GboHeater;

/// <summary>
/// Связь с платой: поиск в сети, опрос, команды, заливка прошивки.
/// Логика поиска та же, что в приложении на телефоне — адрес из прошлого
/// запуска, потом своя точка платы, потом перебор текущей подсети. Так что
/// ни IP набирать, ни держать за роутером фиксированный адрес не нужно:
/// программа найдёт плату там, где она есть.
/// </summary>
public class Board
{
    const int TimeoutPoll = 2500;
    const int TimeoutProbe = 1500;
    const int TimeoutScan = 400;
    const string ApIp = "192.168.4.1";

    readonly HttpClient _http = new() { Timeout = TimeSpan.FromMilliseconds(TimeoutPoll) };

    public string Host { get; private set; }
    public event Action<string> Status;

    void Say(string s) => Status?.Invoke(s);

    // ─────────────────────────── поиск ───────────────────────────

    public async Task<string> Find(string remembered, bool skipRemembered = false)
    {
        if (!skipRemembered && !string.IsNullOrWhiteSpace(remembered))
        {
            Say($"проверяю {remembered}");
            if (await Probe(remembered, TimeoutProbe)) return Host = remembered;
        }

        Say($"проверяю {ApIp}");
        if (await Probe(ApIp, TimeoutProbe)) return Host = ApIp;

        var prefixes = LocalPrefixes();
        if (prefixes.Count == 0) { Host = null; return null; }

        // все подсети сразу: ждать, пока переберётся виртуальная сеть Docker,
        // чтобы только потом взяться за настоящую, — терять секунды впустую
        Say("ищу плату в сети");
        var tasks = prefixes.Select(ScanSubnet).ToArray();
        var results = await Task.WhenAll(tasks);
        var found = results.FirstOrDefault(r => r != null);

        Host = found;
        return found;
    }

    /// <summary>
    /// Свои подсети. У ноутбука их обычно несколько: Wi-Fi, кабель и вдобавок
    /// виртуальные от Docker и WSL. Настоящие отличаются наличием шлюза —
    /// ставим их первыми, виртуальные в конец.
    /// </summary>
    static List<string> LocalPrefixes()
    {
        var real = new List<string>();
        var rest = new List<string>();

        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;

            var props = ni.GetIPProperties();
            bool hasGateway = props.GatewayAddresses
                .Any(g => g.Address != null &&
                          g.Address.AddressFamily == AddressFamily.InterNetwork &&
                          !g.Address.Equals(IPAddress.Any));

            foreach (var ua in props.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var s = ua.Address.ToString();
                if (s.StartsWith("169.254.")) continue;      // адрес без DHCP, смысла нет
                var p = s[..(s.LastIndexOf('.') + 1)];
                var target = hasGateway ? real : rest;
                if (!real.Contains(p) && !rest.Contains(p)) target.Add(p);
            }
        }

        real.AddRange(rest);
        return real;
    }

    /// <summary>Перебор 1..254 пачками, чтобы уложиться в пару секунд.</summary>
    async Task<string> ScanSubnet(string prefix)
    {
        using var gate = new SemaphoreSlim(40);
        var found = new TaskCompletionSource<string>();

        var jobs = Enumerable.Range(1, 254).Select(async i =>
        {
            var ip = prefix + i;
            await gate.WaitAsync();
            try
            {
                if (found.Task.IsCompleted) return;
                if (await Probe(ip, TimeoutScan)) found.TrySetResult(ip);
            }
            finally { gate.Release(); }
        }).ToArray();

        var all = Task.WhenAll(jobs);
        var done = await Task.WhenAny(found.Task, all);
        return done == found.Task ? found.Task.Result : null;
    }

    async Task<bool> Probe(string ip, int ms)
    {
        var body = await Get($"http://{ip}/api", ms);
        return body != null && body.Contains("\"latched\"");
    }

    // ─────────────────────────── запросы ───────────────────────────

    async Task<string> Get(string url, int ms)
    {
        try
        {
            using var cts = new CancellationTokenSource(ms);
            using var r = await _http.GetAsync(url, cts.Token);
            if (!r.IsSuccessStatusCode) return null;
            return await r.Content.ReadAsStringAsync(cts.Token);
        }
        catch { return null; }
    }

    public Task<string> Api() =>
        Host == null ? Task.FromResult<string>(null) : Get($"http://{Host}/api", TimeoutPoll);

    public Task<string> Set(string query) =>
        Host == null ? Task.FromResult<string>(null) : Get($"http://{Host}/set?{query}", TimeoutPoll);

    public Task<string> Curve() =>
        Host == null ? Task.FromResult<string>(null) : Get($"http://{Host}/curve", TimeoutPoll);

    public Task<string> Reset() =>
        Host == null ? Task.FromResult<string>(null) : Get($"http://{Host}/reset", TimeoutPoll);

    public void Use(string ip) => Host = ip;

    // ─────────────────────────── прошивка ───────────────────────────

    /// <summary>
    /// Отправка .bin на /update. Плата на время приёма снимает нагрев сама,
    /// сторожевой таймер кормит — здесь достаточно не торопиться и дать ей
    /// время на запись: таймаут ставим большой, файл около мегабайта.
    /// </summary>
    public async Task<string> Upload(string path, IProgress<int> progress, CancellationToken ct)
    {
        if (Host == null) return "плата не найдена";

        var bytes = await File.ReadAllBytesAsync(path, ct);
        if (bytes.Length < 100_000)
            return "файл подозрительно мал — это точно .ino.bin, а не bootloader?";

        var boundary = "----gbo" + Guid.NewGuid().ToString("N");
        var head = Encoding.ASCII.GetBytes(
            $"--{boundary}\r\nContent-Disposition: form-data; name=\"u\"; " +
            $"filename=\"{Path.GetFileName(path)}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n");
        var tail = Encoding.ASCII.GetBytes($"\r\n--{boundary}--\r\n");

        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(3) };
        using var content = new ProgressContent(head, bytes, tail, progress);
        content.Headers.TryAddWithoutValidation(
            "Content-Type", $"multipart/form-data; boundary={boundary}");

        try
        {
            using var r = await http.PostAsync($"http://{Host}/update", content, ct);
            var txt = await r.Content.ReadAsStringAsync(ct);
            return r.IsSuccessStatusCode ? txt : "плата ответила ошибкой: " + txt;
        }
        catch (TaskCanceledException) { return "отменено"; }
        catch (Exception e) { return "обрыв связи: " + e.Message; }
    }

    /// <summary>Тело запроса с отчётом о продвижении — чтобы полоса ехала.</summary>
    class ProgressContent : HttpContent
    {
        readonly byte[] _head, _body, _tail;
        readonly IProgress<int> _progress;

        public ProgressContent(byte[] head, byte[] body, byte[] tail, IProgress<int> p)
        {
            _head = head; _body = body; _tail = tail; _progress = p;
        }

        protected override async Task SerializeToStreamAsync(Stream s, TransportContext ctx)
        {
            await s.WriteAsync(_head);
            const int chunk = 4096;
            for (int off = 0; off < _body.Length; off += chunk)
            {
                int n = Math.Min(chunk, _body.Length - off);
                await s.WriteAsync(_body.AsMemory(off, n));
                _progress?.Report((int)((off + n) * 100L / _body.Length));
            }
            await s.WriteAsync(_tail);
        }

        protected override bool TryComputeLength(out long length)
        {
            length = _head.Length + _body.Length + _tail.Length;
            return true;
        }
    }
}
