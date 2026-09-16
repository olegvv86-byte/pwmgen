/* =====================================================================
   Подогреватель редуктора ГБО — ESP32
   Защиты + настройка уставок из веб-панели (сохраняются в памяти платы)
   ---------------------------------------------------------------------
   Настраивается на ходу:
     SP  — уставка по пластинам, 60...130 °C   (было жёстко 75)
     PWR — потолок мощности, 20...100 %
     CUT — отсечка по редуктору, 40...80 °C
   Значения пишутся в NVS и переживают выключение зажигания.

   Коды аварий:
     1 RT3   2 RT4   3 RT2   4 расхождение пластин   5 перегрев
     6 нет роста   7 таймаут 15 мин   8 разряд АКБ   9 перенапряжение
   ===================================================================== */

#include <WiFi.h>
#include <WebServer.h>
#include <Preferences.h>
#include <esp_task_wdt.h>
#include <math.h>

const char* STA_SSID = "HeadUnit";
const char* STA_PASS = "";
const char* AP_SSID  = "GBO-Heater";
const char* AP_PASS  = "12345678";

#define PIN_RELAY    19
#define PIN_GATE     18
#define PIN_LED       2
#define PIN_RT3      32
#define PIN_RT4      33
#define PIN_RT2      34
#define PIN_VBAT     35

// ---- настраиваемые уставки (значения по умолчанию) ----
float   spPlate = 75.0;    // 60...130
uint8_t dutyMax = 80;      // 20...100
float   redOff  = 60.0;    // 40...80
float   redOn   = 50.0;    // всегда redOff - 10

// ---- неизменяемые пороги ----
const float TEN_MAX   = 250.0;
const float TEN_SOFT  = 220.0;
const float TEN_COOL  = 150.0;
const float KP = 8.0, KI = 0.25;
const uint8_t  DUTY_SLEW  = 4;
const uint16_t PWM_PERIOD = 500;
const uint16_t CTRL_DT_MS = 250;
const float    CTRL_DT    = CTRL_DT_MS / 1000.0;
const uint16_t T_PRECHARGE = 600, T_SHUTDOWN = 300;

const int   RAW_SHORT = 40, RAW_OPEN = 4055;
const float T_MIN_PHYS = -45.0, T_MAX_PHYS = 400.0;
const float DIVERGE_MAX = 40.0;
const uint32_t DIVERGE_MS = 5000;
const uint8_t  RISE_DUTY = 50;
const uint32_t RISE_MS = 60000;
const float    RISE_MIN = 5.0;
const uint32_t RUN_TIMEOUT_MS = 15UL * 60UL * 1000UL;
const float VBAT_LOW = 10.5, VBAT_BACK = 11.5, VBAT_DEAD = 9.5, VBAT_HIGH = 16.5;
const uint32_t VBAT_DEAD_MS = 10000, VBAT_HIGH_MS = 1000;
const uint32_t BOOT_HOLD_MS = 5000;
const uint8_t  GOOD_NEEDED = 5;
const float IGN_THR = 6.0;

const float VSUP  = 3.272;
const float DIV_K = 4.03;
const float EMA_K = 0.2;

struct Ntc { float r25, beta, rpull; };
Ntc NTC_PLATE = { 100000.0, 3950.0,  2200.0 };   // RT3 / RT4, подтяжка 2.2 к
Ntc NTC_RED   = { 100000.0, 3950.0, 22000.0 };   // RT2, подтяжка 22 к

struct Sensor {
  uint8_t pin; Ntc* ntc; int raw; float t;
  bool valid, seeded; uint8_t good, faultCode;
};
Sensor S3 = { PIN_RT3, &NTC_PLATE, 0, NAN, false, false, 0, 1 };
Sensor S4 = { PIN_RT4, &NTC_PLATE, 0, NAN, false, false, 0, 2 };
Sensor SR = { PIN_RT2, &NTC_RED,   0, NAN, false, false, 0, 3 };

enum State { ST_IDLE, ST_PRECHARGE, ST_RUN, ST_SHUTDOWN };
State state = ST_IDLE;
uint32_t stateSince = 0;

WebServer server(80);
Preferences prefs;

float vbat = 0;
bool  ignOn = false, warmedUp = false, relayOn = false;
uint8_t duty = 0;
float integ = 0;
uint8_t faultCode = 0;
bool faultLatched = false, uvBlock = false;
uint32_t deadSince = 0, ovSince = 0, divSince = 0;
uint32_t riseT0 = 0, heatStart = 0;
float riseStart = 0, riseMax = 0;
bool riseArmed = false;

String logBuf[20];
uint8_t logCount = 0;

const char* faultText(uint8_t c) {
  switch (c) {
    case 1: return "датчик RT3 (пластина 1)";
    case 2: return "датчик RT4 (пластина 2)";
    case 3: return "датчик RT2 (редуктор)";
    case 4: return "расхождение датчиков пластин";
    case 5: return "перегрев выше 250 C";
    case 6: return "нет роста температуры";
    case 7: return "таймаут 15 минут";
    case 8: return "аккумулятор разряжен";
    case 9: return "перенапряжение бортсети";
    default: return "";
  }
}

void addLog(const String &s) {
  uint32_t t = millis() / 1000;
  char ts[16];
  snprintf(ts, sizeof(ts), "%02u:%02u:%02u", t / 3600, (t / 60) % 60, t % 60);
  String line = String(ts) + "  " + s;
  if (logCount < 20) logBuf[logCount++] = line;
  else { for (uint8_t i = 0; i < 19; i++) logBuf[i] = logBuf[i + 1]; logBuf[19] = line; }
}

void raiseFault(uint8_t code) {
  if (faultLatched) return;
  faultLatched = true; faultCode = code; duty = 0;
  digitalWrite(PIN_GATE, LOW);
  addLog("АВАРИЯ " + String(code) + ": " + faultText(code));
}

int median5(int *a) {
  for (uint8_t i = 0; i < 4; i++)
    for (uint8_t j = i + 1; j < 5; j++)
      if (a[j] < a[i]) { int t = a[i]; a[i] = a[j]; a[j] = t; }
  return a[2];
}

void readSensor(Sensor &s) {
  int raw[5], mv[5];
  for (uint8_t i = 0; i < 5; i++) { raw[i] = analogRead(s.pin); mv[i] = analogReadMilliVolts(s.pin); }
  s.raw = median5(raw);
  float v = median5(mv) / 1000.0;
  if (s.raw < RAW_SHORT || s.raw > RAW_OPEN || v <= 0.001 || v >= VSUP - 0.001) {
    s.valid = false; s.good = 0; return;
  }
  float r = s.ntc->rpull * v / (VSUP - v);
  float t = 1.0 / (1.0 / 298.15 + log(r / s.ntc->r25) / s.ntc->beta) - 273.15;
  if (t < T_MIN_PHYS || t > T_MAX_PHYS) { s.valid = false; s.good = 0; return; }
  if (!s.seeded) { s.t = t; s.seeded = true; } else s.t += EMA_K * (t - s.t);
  s.valid = true;
  if (s.good < GOOD_NEEDED) s.good++;
}

void readVbat() {
  int mv[5];
  for (uint8_t i = 0; i < 5; i++) mv[i] = analogReadMilliVolts(PIN_VBAT);
  vbat = median5(mv) / 1000.0 * DIV_K;
  ignOn = (vbat > IGN_THR);
}

void readAll() { readSensor(S3); readSensor(S4); readSensor(SR); readVbat(); }

void setRelay(bool on) {
  if (relayOn == on) return;
  relayOn = on; digitalWrite(PIN_RELAY, on);
  addLog(on ? "реле K1 замкнуто" : "реле K1 разомкнуто");
}

void checkProtections() {
  uint32_t now = millis();
  if (!S3.valid) raiseFault(1);
  if (!S4.valid) raiseFault(2);
  if (!SR.valid) raiseFault(3);
  if (faultLatched) return;

  if (fabs(S3.t - S4.t) > DIVERGE_MAX) {
    if (divSince == 0) divSince = now;
    else if (now - divSince >= DIVERGE_MS) raiseFault(4);
  } else divSince = 0;

  float tHot = max(S3.t, S4.t);
  if (tHot >= TEN_MAX) { duty = 0; digitalWrite(PIN_GATE, LOW); raiseFault(5); return; }

  float tPlate = (S3.t + S4.t) * 0.5;
  if (state == ST_RUN && duty >= RISE_DUTY) {
    if (!riseArmed) { riseArmed = true; riseT0 = now; riseStart = tPlate; riseMax = tPlate; }
    else {
      if (tPlate > riseMax) riseMax = tPlate;
      if (now - riseT0 >= RISE_MS) {
        if (riseMax - riseStart < RISE_MIN) { raiseFault(6); return; }
        riseT0 = now; riseStart = tPlate; riseMax = tPlate;
      }
    }
  } else riseArmed = false;

  if (state == ST_RUN) {
    if (heatStart == 0) heatStart = now;
    if (!warmedUp && now - heatStart >= RUN_TIMEOUT_MS) { raiseFault(7); return; }
  } else if (state == ST_IDLE) heatStart = 0;

  if (ignOn) {
    if (vbat > VBAT_HIGH) {
      if (ovSince == 0) ovSince = now;
      else if (now - ovSince >= VBAT_HIGH_MS) { raiseFault(9); return; }
    } else ovSince = 0;

    if (!uvBlock && vbat < VBAT_LOW) {
      uvBlock = true; duty = 0; digitalWrite(PIN_GATE, LOW);
      addLog("просадка " + String(vbat, 1) + " В — нагрев снят");
    } else if (uvBlock && vbat > VBAT_BACK) {
      uvBlock = false;
      addLog("бортсеть " + String(vbat, 1) + " В — нагрев возобновлён");
    }

    if (vbat < VBAT_DEAD) {
      if (deadSince == 0) deadSince = now;
      else if (now - deadSince >= VBAT_DEAD_MS) { raiseFault(8); return; }
    } else deadSince = 0;
  }
}

void control() {
  uint32_t now = millis();
  checkProtections();

  bool prev = warmedUp;
  if (SR.valid) {
    if (SR.t >= redOff) warmedUp = true;
    if (SR.t <= redOn)  warmedUp = false;
  }
  if (warmedUp != prev)
    addLog(warmedUp ? "редуктор " + String(SR.t, 1) + " C — подогрев не нужен"
                    : "редуктор " + String(SR.t, 1) + " C — подогрев снова нужен");

  bool bootReady = (now >= BOOT_HOLD_MS) && (S3.good >= GOOD_NEEDED) &&
                   (S4.good >= GOOD_NEEDED) && (SR.good >= GOOD_NEEDED);
  bool want = !faultLatched && !uvBlock && ignOn && !warmedUp && bootReady;

  switch (state) {
    case ST_IDLE:
      duty = 0;
      if (want) { setRelay(true); state = ST_PRECHARGE; stateSince = now; }
      return;
    case ST_PRECHARGE:
      duty = 0;
      if (!want) { state = ST_SHUTDOWN; stateSince = now; return; }
      if (now - stateSince < T_PRECHARGE) return;
      state = ST_RUN; integ = 0; heatStart = now;
      addLog("подогрев запущен");
      break;
    case ST_RUN:
      if (!want) { duty = 0; state = ST_SHUTDOWN; stateSince = now; addLog("подогрев остановлен"); return; }
      break;
    case ST_SHUTDOWN:
      duty = 0;
      if (now - stateSince >= T_SHUTDOWN) { setRelay(false); state = ST_IDLE; }
      return;
  }

  float tPlate = (S3.t + S4.t) * 0.5;
  float err = spPlate - tPlate;
  integ += KI * err * CTRL_DT;
  integ = constrain(integ, 0.0f, 100.0f);
  float u = constrain(KP * err + integ, 0.0f, 100.0f);

  float tHot = max(S3.t, S4.t);
  float lim = (tHot > TEN_SOFT) ? 100.0 * (TEN_MAX - tHot) / (TEN_MAX - TEN_SOFT) : 100.0;
  if (lim < 0) lim = 0;

  uint8_t target = (uint8_t)(min(u, lim) + 0.5f);
  if (target > dutyMax) target = dutyMax;
  if (target > duty) duty = min<int>(target, duty + DUTY_SLEW);
  else               duty = target;
}

void pwmTask() {
  bool on = false;
  if (state == ST_RUN && duty > 0 && !faultLatched) {
    uint16_t pos = (uint16_t)(millis() % PWM_PERIOD);
    on = pos < (uint32_t)duty * PWM_PERIOD / 100;
  }
  digitalWrite(PIN_GATE, on);
}

bool resetFault() {
  if (!faultLatched) return true;
  if (faultCode == 5 && max(S3.t, S4.t) > TEN_COOL) return false;
  faultLatched = false; faultCode = 0;
  uvBlock = false; deadSince = ovSince = divSince = 0;
  riseArmed = false; heatStart = 0; integ = 0;
  addLog("авария сброшена вручную");
  return true;
}

void saveSettings() {
  prefs.putFloat("sp", spPlate);
  prefs.putUChar("pwr", dutyMax);
  prefs.putFloat("cut", redOff);
}

// ------------------------- Панель -------------------------------------
const char PAGE[] PROGMEM = R"HTML(<!DOCTYPE html><html lang="ru"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GBO HEATER</title><style>
:root{--bg:#0a0e13;--pan:#121922;--line:#1e2a36;--yel:#ffd23f;--cyan:#22d3ee;
--blu:#2b6cff;--red:#ff4d4d;--grn:#3ddc84;--tx:#e8eef5;--dim:#6b7c8d}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;background:var(--bg);color:var(--tx);
font:14px/1.35 ui-monospace,"Roboto Mono",monospace;padding:10px}
.hd{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px}
.hd b{color:var(--yel);font-size:17px;letter-spacing:2px}
.dot{font-size:12px;color:var(--grn)}
.st{padding:13px;border-radius:6px;text-align:center;font-weight:700;
letter-spacing:1px;border:1px solid var(--line);margin-bottom:10px}
.s-run{background:#0e2a18;color:var(--grn);border-color:#1d4a2c}
.s-idle{background:#221d0a;color:var(--yel);border-color:#4a3f14}
.s-err{background:#2a0e0e;color:var(--red);border-color:#4a1d1d}
canvas{background:var(--pan);border:1px solid var(--line);border-radius:6px;
width:100%;height:190px;display:block}
.leg{display:flex;gap:16px;justify-content:center;margin:6px 0 10px;font-size:11px;color:var(--dim)}
.leg i{display:inline-block;width:14px;height:3px;vertical-align:middle;margin-right:5px}
.gr{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:10px}
.cell{background:var(--pan);border:1px solid var(--line);border-radius:6px;padding:10px 8px;text-align:center}
.cl{font-size:10px;color:var(--dim);letter-spacing:1px}
.cv{font-size:25px;font-weight:700;margin-top:2px}
.cr{font-size:10px;color:#3f4e5c;margin-top:3px}
.sec{background:var(--pan);border:1px solid var(--line);border-radius:6px;
padding:12px;margin-bottom:10px}
.sec h3{margin:0 0 12px;font-size:11px;color:var(--dim);letter-spacing:2px;font-weight:400}
.sl{display:flex;align-items:center;gap:10px;margin-bottom:16px}
.sl:last-child{margin-bottom:0}
.sn{width:42px;font-size:12px;font-weight:700}
.sv{width:62px;text-align:right;font-size:19px;font-weight:700}
.su{width:16px;font-size:11px;color:var(--dim)}
input[type=range]{-webkit-appearance:none;flex:1;height:26px;background:transparent}
input[type=range]::-webkit-slider-runnable-track{height:4px;background:var(--line);border-radius:2px}
input[type=range]::-webkit-slider-thumb{-webkit-appearance:none;width:24px;height:24px;
border-radius:50%;margin-top:-10px;background:var(--blu);border:2px solid var(--bg)}
input.y::-webkit-slider-thumb{background:var(--yel)}
input.c::-webkit-slider-thumb{background:var(--cyan)}
.bar{height:6px;background:#070a0e;border-radius:3px;overflow:hidden;margin-top:6px}
.bf{height:100%;background:var(--blu);width:0;transition:width .4s}
button{width:100%;padding:15px;border:1px solid #5a2020;border-radius:6px;
background:#2a0e0e;color:var(--red);font:700 13px ui-monospace,monospace;letter-spacing:1px}
button:disabled{background:var(--pan);color:#38454f;border-color:var(--line)}
pre{background:var(--pan);border:1px solid var(--line);border-radius:6px;padding:10px;
font-size:11px;max-height:180px;overflow:auto;margin:10px 0 0;white-space:pre-wrap;color:var(--dim)}
</style></head><body>
<div class="hd"><b>GBO HEATER</b><span class="dot" id="dot">● ONLINE</span></div>
<div class="st s-idle" id="st">…</div>
<canvas id="g"></canvas>
<div class="leg">
<span><i style="background:#ff4d4d"></i>РЕДУКТОР</span>
<span><i style="background:#22d3ee"></i>ПЛАСТИНА 1</span>
<span><i style="background:#ffd23f"></i>ПЛАСТИНА 2</span></div>
<div class="gr">
<div class="cell"><div class="cl">РЕДУКТОР</div><div class="cv" id="red" style="color:var(--red)">--</div><div class="cr" id="rr">ADC --</div></div>
<div class="cell"><div class="cl">ПЛАСТИНА 1</div><div class="cv" id="p1" style="color:var(--cyan)">--</div><div class="cr" id="r1">ADC --</div></div>
<div class="cell"><div class="cl">ПЛАСТИНА 2</div><div class="cv" id="p2" style="color:var(--yel)">--</div><div class="cr" id="r2">ADC --</div></div>
</div>
<div class="gr">
<div class="cell"><div class="cl">БОРТСЕТЬ</div><div class="cv" id="ub" style="font-size:21px">--</div></div>
<div class="cell"><div class="cl">МОЩНОСТЬ</div><div class="cv" id="pw" style="font-size:21px;color:var(--blu)">--</div><div class="bar"><div class="bf" id="bp"></div></div></div>
<div class="cell"><div class="cl">РЕЛЕ K1</div><div class="cv" id="rl" style="font-size:14px;margin-top:8px">--</div></div>
</div>
<div class="sec"><h3>НАСТРОЙКА</h3>
<div class="sl"><span class="sn" style="color:var(--cyan)">SP</span>
<input type="range" class="c" id="s-sp" min="60" max="130" step="1" oninput="lv('sp',this.value)" onchange="send()">
<span class="sv" id="v-sp" style="color:var(--cyan)">--</span><span class="su">°C</span></div>
<div class="sl"><span class="sn" style="color:var(--blu)">PWR</span>
<input type="range" id="s-pw" min="20" max="100" step="5" oninput="lv('pw',this.value)" onchange="send()">
<span class="sv" id="v-pw" style="color:var(--blu)">--</span><span class="su">%</span></div>
<div class="sl"><span class="sn" style="color:var(--yel)">CUT</span>
<input type="range" class="y" id="s-ct" min="40" max="80" step="1" oninput="lv('ct',this.value)" onchange="send()">
<span class="sv" id="v-ct" style="color:var(--yel)">--</span><span class="su">°C</span></div>
</div>
<button id="rst" onclick="rst()">СБРОС АВАРИИ</button>
<pre id="log"></pre>
<script>
const H=[[],[],[]],MAX=300,C=['#ff4d4d','#22d3ee','#ffd23f'];
let touching=false;
document.querySelectorAll('input[type=range]').forEach(e=>{
 e.addEventListener('pointerdown',()=>touching=true);
 e.addEventListener('pointerup',()=>setTimeout(()=>touching=false,600));});
function lv(k,v){document.getElementById('v-'+k).textContent=v;}
async function send(){
 const sp=s_sp.value,pw=s_pw.value,ct=s_ct.value;
 await fetch(`/set?sp=${sp}&pw=${pw}&ct=${ct}`);}
async function rst(){const r=await fetch('/reset');
 if((await r.text())!=='ok')alert('Пластина ещё горячая — сброс после остывания ниже 150 °C');tick();}
function draw(){const c=g,x=c.getContext('2d');
 c.width=c.clientWidth*2;c.height=380;x.clearRect(0,0,c.width,c.height);
 x.strokeStyle='#1a2530';x.lineWidth=1;
 for(let i=1;i<5;i++){const y=c.height*i/5;x.beginPath();x.moveTo(0,y);x.lineTo(c.width,y);x.stroke();}
 for(let i=1;i<6;i++){const px=c.width*i/6;x.beginPath();x.moveTo(px,0);x.lineTo(px,c.height);x.stroke();}
 H.forEach((s,i)=>{if(s.length<2)return;x.strokeStyle=C[i];x.lineWidth=3;x.beginPath();
 s.forEach((v,j)=>{const px=j/(MAX-1)*c.width,py=c.height-(v/280)*c.height;
 j?x.lineTo(px,py):x.moveTo(px,py);});x.stroke();});}
async function tick(){try{const r=await fetch('/api');const d=await r.json();
 dot.style.color='#3ddc84';dot.textContent='● ONLINE';
 const f=v=>v===null?'--':v.toFixed(1);
 red.textContent=f(d.red);p1.textContent=f(d.p1);p2.textContent=f(d.p2);
 rr.textContent='ADC '+d.rawR;r1.textContent='ADC '+d.raw1;r2.textContent='ADC '+d.raw2;
 ub.textContent=d.vbat.toFixed(1)+' В';pw.textContent=d.duty+' %';bp.style.width=d.duty+'%';
 rl.textContent=d.relay?'ЗАМКНУТО':'РАЗОМКНУТО';
 rl.style.color=d.relay?'#3ddc84':'#6b7c8d';
 if(!touching){s_sp.value=d.sp;lv('sp',d.sp);s_pw.value=d.pwr;lv('pw',d.pwr);
  s_ct.value=d.cut;lv('ct',d.cut);}
 rst.disabled=!d.latched;
 const s=st;
 if(d.latched){s.className='st s-err';s.textContent='АВАРИЯ '+d.fault+' · '+d.ftext.toUpperCase();}
 else if(d.uv){s.className='st s-idle';s.textContent='ПРОСАДКА · НАГРЕВ СНЯТ';}
 else if(d.warm){s.className='st s-idle';s.textContent='РЕДУКТОР ПРОГРЕТ · ОБЕСТОЧЕНО';}
 else if(d.state==2){s.className='st s-run';s.textContent='НАГРЕВ';}
 else if(d.state==1){s.className='st s-run';s.textContent='ЗАПУСК';}
 else{s.className='st s-idle';s.textContent='ОЖИДАНИЕ';}
 [d.red??0,d.p1??0,d.p2??0].forEach((v,i)=>{H[i].push(v);if(H[i].length>MAX)H[i].shift();});
 draw();log.textContent=d.log.join('\n');}
 catch(e){dot.style.color='#ff4d4d';dot.textContent='● OFFLINE';}}
setInterval(tick,1000);tick();
</script></body></html>)HTML";

String jn(float v, bool ok) { return (!ok || isnan(v)) ? String("null") : String(v, 1); }

void handleApi() {
  String j = "{";
  j += "\"red\":"   + jn(SR.t, SR.valid);
  j += ",\"p1\":"   + jn(S3.t, S3.valid);
  j += ",\"p2\":"   + jn(S4.t, S4.valid);
  j += ",\"rawR\":" + String(SR.raw);
  j += ",\"raw1\":" + String(S3.raw);
  j += ",\"raw2\":" + String(S4.raw);
  j += ",\"duty\":" + String(duty);
  j += ",\"vbat\":" + String(vbat, 1);
  j += ",\"sp\":"   + String((int)spPlate);
  j += ",\"pwr\":"  + String(dutyMax);
  j += ",\"cut\":"  + String((int)redOff);
  j += ",\"relay\":"   + String(relayOn ? "true" : "false");
  j += ",\"warm\":"    + String(warmedUp ? "true" : "false");
  j += ",\"uv\":"      + String(uvBlock ? "true" : "false");
  j += ",\"latched\":" + String(faultLatched ? "true" : "false");
  j += ",\"state\":"   + String((int)state);
  j += ",\"fault\":"   + String(faultCode);
  j += ",\"ftext\":\"" + String(faultText(faultCode)) + "\"";
  j += ",\"log\":[";
  for (uint8_t i = 0; i < logCount; i++) { if (i) j += ","; j += "\"" + logBuf[i] + "\""; }
  j += "]}";
  server.send(200, "application/json", j);
}

void handleSet() {
  bool ch = false;
  if (server.hasArg("sp")) {
    float v = constrain(server.arg("sp").toFloat(), 60.0f, 130.0f);
    if (v != spPlate) { spPlate = v; ch = true; }
  }
  if (server.hasArg("pw")) {
    uint8_t v = constrain(server.arg("pw").toInt(), 20, 100);
    if (v != dutyMax) { dutyMax = v; ch = true; }
  }
  if (server.hasArg("ct")) {
    float v = constrain(server.arg("ct").toFloat(), 40.0f, 80.0f);
    if (v != redOff) { redOff = v; redOn = v - 10.0; ch = true; }
  }
  if (ch) {
    saveSettings();
    addLog("уставки: SP " + String((int)spPlate) + " C, PWR " + String(dutyMax) +
           " %, CUT " + String((int)redOff) + " C");
  }
  server.send(200, "text/plain", "ok");
}

void setup() {
  pinMode(PIN_RELAY, OUTPUT); digitalWrite(PIN_RELAY, LOW);
  pinMode(PIN_GATE,  OUTPUT); digitalWrite(PIN_GATE,  LOW);
  pinMode(PIN_LED,   OUTPUT); digitalWrite(PIN_LED,   LOW);

  Serial.begin(115200);
  analogSetPinAttenuation(PIN_RT3,  ADC_11db);
  analogSetPinAttenuation(PIN_RT4,  ADC_11db);
  analogSetPinAttenuation(PIN_RT2,  ADC_11db);
  analogSetPinAttenuation(PIN_VBAT, ADC_11db);

  prefs.begin("gbo", false);
  spPlate = prefs.getFloat("sp", 75.0);
  dutyMax = prefs.getUChar("pwr", 80);
  redOff  = prefs.getFloat("cut", 60.0);
  redOn   = redOff - 10.0;

#if ESP_IDF_VERSION_MAJOR >= 5
  esp_task_wdt_config_t wcfg = { .timeout_ms = 5000, .idle_core_mask = 0, .trigger_panic = true };
  esp_task_wdt_reconfigure(&wcfg);
#else
  esp_task_wdt_init(5, true);
#endif
  esp_task_wdt_add(NULL);

  WiFi.mode(WIFI_STA);
  if (strlen(STA_PASS)) {
    WiFi.begin(STA_SSID, STA_PASS);
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 8000) { delay(200); esp_task_wdt_reset(); }
  }
  if (WiFi.status() == WL_CONNECTED) addLog("сеть магнитолы, " + WiFi.localIP().toString());
  else { WiFi.mode(WIFI_AP); WiFi.softAP(AP_SSID, AP_PASS);
         addLog("своя точка " + WiFi.softAPIP().toString()); }

  server.on("/", []() { server.send_P(200, "text/html; charset=utf-8", PAGE); });
  server.on("/api", handleApi);
  server.on("/set", handleSet);
  server.on("/reset", []() { server.send(200, "text/plain", resetFault() ? "ok" : "hot"); });
  server.begin();

  readAll();
  addLog("старт: SP " + String((int)spPlate) + " C, PWR " + String(dutyMax) +
         " %, CUT " + String((int)redOff) + " C");
}

uint32_t tSens = 0, tCtrl = 0, tLog = 0;

void loop() {
  esp_task_wdt_reset();
  server.handleClient();
  uint32_t now = millis();

  if (now - tSens >= 200)        { tSens = now; readAll(); }
  if (now - tCtrl >= CTRL_DT_MS) { tCtrl = now; control(); }
  pwmTask();

  if (faultLatched)  digitalWrite(PIN_LED, (now / 150) & 1);
  else if (uvBlock)  digitalWrite(PIN_LED, (now / 400) & 1);
  else if (warmedUp) digitalWrite(PIN_LED, (now / 800) & 1);
  else               digitalWrite(PIN_LED, state == ST_RUN && duty > 0);

  if (now - tLog >= 1000) {
    tLog = now;
    Serial.printf("P1=%.1f(%d) P2=%.1f(%d) RED=%.1f(%d) U=%.1f duty=%u st=%d flt=%u\n",
                  S3.t, S3.raw, S4.t, S4.raw, SR.t, SR.raw, vbat, duty, (int)state, faultCode);
  }
}
