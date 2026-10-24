from machine import Pin
from rp2 import PIO, StateMachine, asm_pio
import time
import network
import socket
import _thread

for pio_idx in range(2):
    try:
        PIO(pio_idx).remove_program()
    except:
        pass

# ------------------------------------------------------------------
# Параметры CH1/CH2
# ------------------------------------------------------------------
freq          = 20000
duty          = 50
mode          = 0
freq_steps    = [10, 100, 1000, 10000, 50000]
freq_step_idx = 0
duty_steps    = [1, 10]
duty_step_idx = 0

# ------------------------------------------------------------------
# Параметры CH3
# ------------------------------------------------------------------
ch3_count     = 5        # кол-во импульсов (1–10)
ch3_phase_pct = 0        # сдвиг фазы % от периода CH1 (0–99)
ch3_freq      = 1_000_000 # несущая Гц
ch3_duty      = 50        # скважность % (1–99)
ch3_mode      = 0         # 0=фаза, 1=кол-во, 2=частота, 3=скважность
channel       = 0         # 0=CH1/CH2, 1=CH3

ch3_linked    = True
ch3_carrier   = 1000
ch3_period_divider = 1    # PD: 1=каждый период CH1, 2..20=каждый N-й
_ch3_sm1_freq = 125000000 # частота SM5/SM7 (для перезапуска без apply_ch3)

CH3_COUNT_MIN = 1
CH3_COUNT_MAX = 100
CH3_FREQ_MIN  = 1_000
CH3_FREQ_MAX  = 10_000_000
CH3_FREQ_STEPS = [1_000, 10_000, 100_000, 500_000, 1_000_000]
CH3_FREQ_STEP_IDX = 0
SYS_CLK       = 125_000_000
TICK_NS       = 1_000_000_000 // SYS_CLK  # 8 нс

# ------------------------------------------------------------------
# Пины
# ------------------------------------------------------------------

# ------------------------------------------------------------------
# PIO0 SM0: CH1/CH2 (проверенная рабочая программа)
# GP0=CH1, GP1=CH2
# ------------------------------------------------------------------
def make_prog_ch12():
    @asm_pio(set_init=(PIO.OUT_LOW, PIO.OUT_LOW))
    def prog_ch12():
        pull(block)
        mov(x, osr)
        mov(isr, x)
        pull(block)
        mov(y, osr)
        mov(osr, y)
        wrap_target()
        set(pins, 0b01)
        mov(x, isr)
        label("hi1")
        jmp(x_dec, "hi1")
        set(pins, 0b00)
        mov(y, osr)
        label("lo1")
        jmp(y_dec, "lo1")
        set(pins, 0b10)
        mov(x, isr)
        label("hi2")
        jmp(x_dec, "hi2")
        set(pins, 0b00)
        mov(y, osr)
        label("lo2")
        jmp(y_dec, "lo2")
        wrap()
    return prog_ch12



def make_prog_ch12_inv():
    @asm_pio(set_init=(PIO.OUT_LOW, PIO.OUT_LOW))
    def prog_ch12_inv():
        pull(block)
        mov(x, osr)
        mov(isr, x)
        pull(block)
        mov(y, osr)
        mov(osr, y)
        wrap_target()
        set(pins, 0b10)
        mov(x, isr)
        label("a1")
        jmp(x_dec, "a1")
        set(pins, 0b11)
        mov(y, osr)
        label("b1")
        jmp(y_dec, "b1")
        set(pins, 0b01)
        mov(x, isr)
        label("a2")
        jmp(x_dec, "a2")
        set(pins, 0b11)
        mov(y, osr)
        label("b2")
        jmp(y_dec, "b2")
        wrap()
    return prog_ch12_inv


# ------------------------------------------------------------------
# PIO1 SM5 (+SM7 для X2): CH3 — пачка импульсов
# GP4=CH3. CH3 на PIO1: PIO0 переполнен (CH12 SM0 + PLL SM3).
# ------------------------------------------------------------------
@asm_pio(set_init=PIO.OUT_LOW)
def prog_ch3_static():
    # Инициализация: загружаем phase в ISR
    pull(block)
    out(isr, 32)      # ISR = phase_ticks

    # count-1 в Y (постоянно)
    pull(block)
    mov(y, osr)       # Y = count-1

    wrap_target()
    label("wrap")
    set(pins, 0)

    # Ждём фронт CH1
    wait(0, gpio, 0)
    wait(1, gpio, 0)

    # Задержка фазы
    mov(x, isr)
    label("ph")
    jmp(x_dec, "ph")

    # Пачка: Y+1 импульсов
    # hi и lo закодированы в программе через [delay]
    # Обновляем count из FIFO (noblock)
    pull(noblock)
    mov(y, osr)       # Y = новый count-1

    wrap()


def make_prog_ch3(hi_ticks, lo_ticks, invert=False, sync_pin=None):
    """Пачка CH3 с делителем периодов CH1.
    Синхропин задаётся через in_base каждой SM (wait(..., pin, 0)), а не
    абсолютным gpio — поэтому SM5 и SM7 (X2) используют ОДНУ программу.
    Это критично: CYW43 (WiFi) тоже занимает память инструкций PIO1,
    и две копии по 15 инструкций её переполняли."""
    hi = max(0, min(31, hi_ticks - 1))
    lo = max(0, min(31, lo_ticks - 1))
    pin_hi = 0 if invert else 1
    pin_lo = 1 if invert else 0
    # set(pins) пишет GP4 (бит0) и GP5 (бит1). GP5 — строб для осциллографа,
    # всегда прямой: HIGH на всё время пачки, не зависит от инверсии.
    v_hi   = 2 | pin_hi
    v_lo   = 2 | pin_lo
    v_idle = pin_lo

    @asm_pio(set_init=(PIO.OUT_LOW, PIO.OUT_LOW), out_shiftdir=PIO.SHIFT_RIGHT,
             fifo_join=PIO.JOIN_TX)
    def prog_ch3():
        pull(block)
        out(y, 32)        # Y = count-1 (один раз при старте)

        wrap_target()
        # Слово = (phase << 5) | (divider-1). PIO ХРАНИТ последнее слово в ISR
        # и НИКОГДА не блокируется: pull(noblock) при пустом FIFO берёт X,
        # а в X перед этим положено старое слово. Раньше pull(block) вставал
        # на пустом FIFO (GC / запись flash / медленный фидер), после чего
        # счёт периодов начинался с произвольного фронта -> сетка пропусков
        # съезжала и пачки «моргали» в пропущенных периодах.
        mov(x, isr)       # X = предыдущее слово (запасной вариант)
        pull(noblock)     # OSR = новое слово или X
        mov(isr, osr)     # ISR = текущее слово (переживает весь цикл)
        out(x, 5)         # X = divider-1, в OSR остаётся phase
        label("sync_loop")
        wait(0, pin, 0)   # pin 0 относительно in_base этой SM
        wait(1, pin, 0)
        jmp(x_dec, "sync_loop")   # X>0 -> ждём следующий фронт
        # Задержка фазы
        mov(x, osr)
        label("ph")
        jmp(x_dec, "ph")
        # Пачка: count импульсов
        mov(x, y)
        label("b")
        set(pins, v_hi) [hi]
        set(pins, v_lo) [lo]
        jmp(x_dec, "b")
        set(pins, v_idle)   # конец пачки: GP5 -> LOW
        wrap()
        # 16 инструкций, ОДНА копия на обе SM.
        # Число инструкций до фронта и от фронта до пачки не изменилось —
        # калибровка фазы (-6) остаётся верной.

    return prog_ch3


PIO1_CTRL_SET = 0x50300000 + 0x2000   # атомарный SET-алиас PIO1 CTRL
PIO1_SM3_PINCTRL = 0x50300000 + 0x124  # PINCTRL машины SM7
CH3_PHASE_MASK = (1 << 27) - 1

def _ch3_make_word(phase_ticks):
    """Упаковка для PIO: младшие 5 бит = divider-1, старшие 27 = phase."""
    ph = max(1, min(CH3_PHASE_MASK, phase_ticks))
    return (ph << 5) | ((ch3_period_divider - 1) & 0x1F)


def make_prog_ch3_x2(hi_ticks, lo_ticks, invert=False):
    """Та же программа — X2 реализуется через SM7, а не через программу."""
    return make_prog_ch3(hi_ticks, lo_ticks, invert)


def make_prog_ch3_free(hi_ticks, lo_ticks, invert=False):
    """CH3 АВТОНОМНЫЙ — без CH1. Грузится на PIO0, рядом с CH1/2 (но не CYW43)."""
    hi = max(0, min(31, hi_ticks - 1))
    lo = max(0, min(31, lo_ticks - 1))
    pin_hi = 0 if invert else 1
    pin_lo = 1 if invert else 0

    @asm_pio(set_init=PIO.OUT_LOW)
    def prog_ch3_free():
        pull(block)
        out(isr, 32)      # ISR = count-1
        pull(block)
        out(y, 32)        # Y   = pause_ticks

        wrap_target()
        mov(x, isr)
        label("burst")
        set(pins, pin_hi) [hi]
        set(pins, pin_lo) [lo]
        jmp(x_dec, "burst")

        set(pins, pin_lo)
        mov(x, y)
        label("pause")
        jmp(x_dec, "pause")
        wrap()

    return prog_ch3_free


# ─── ДИАГНОСТИКА (команда DB:1 / DB:0): пачек/с на GP4 в консоль ───
@asm_pio()
def _dbg_edge_cnt():
    label("top")
    wait(0, pin, 0)
    wait(1, pin, 0)
    jmp(x_dec, "top")
    jmp("top")

_DBG_SET_X0  = 0xE020   # set(x, 0)
_DBG_MOV_ISR = 0xA0C1   # mov(isr, x)
_DBG_PUSH    = 0x8020   # push()
dbg_on  = False
_dbg_sm = None
_dbg_ms = 0

def _dbg_start():
    global _dbg_sm
    if not dbg_on:
        return
    try:
        _dbg_sm = StateMachine(2, _dbg_edge_cnt, freq=125_000_000, in_base=Pin(4))
        _dbg_sm.exec(_DBG_SET_X0)
        _dbg_sm.active(1)
    except Exception as e:
        _dbg_sm = None
        print("DBG ERR:", e)

def _dbg_stop():
    global _dbg_sm
    if _dbg_sm is not None:
        try: _dbg_sm.active(0)
        except: pass
        _dbg_sm = None
    try: PIO(0).remove_program(_dbg_edge_cnt)
    except: pass

def _dbg_tick():
    global _dbg_ms
    if _dbg_sm is None:
        return
    now = time.ticks_ms()
    dt = time.ticks_diff(now, _dbg_ms)
    if dt < 1000:
        return
    _dbg_ms = now
    _dbg_sm.active(0)
    _dbg_sm.exec(_DBG_MOV_ISR)
    _dbg_sm.exec(_DBG_PUSH)
    n = (-_dbg_sm.get()) & 0xFFFFFFFF
    _dbg_sm.exec(_DBG_SET_X0)
    _dbg_sm.active(1)
    bursts = n * 1000 // dt // max(1, ch3_count)
    expect = freq * (2 if ch3_x2 else 1) // ch3_period_divider
    print("DBG пропуск={} пачек/с={} ожидается={} {}".format(
        ch3_period_divider - 1, bursts, expect,
        "OK" if abs(bursts - expect) <= max(3, expect // 50) else "<-- не совпадает"))

sm  = None
sm1 = None
sm1_b2 = None    # PIO1 SM7 — X2
sm_free = None    # PIO0 SM1 — автономный режим CH3
_ch3_free_prog = None
ch3_rebuilding = False  # True пока apply_ch3 пересобирает SM5 — _ch3_push ждёт
_cached_ph1 = 1
_cached_ph1_b2 = 1
_ch3_last_word = -1
_ch3_last_word_b2 = -1


def set_outover(gpio_num, invert):
    """Устанавливает инверсию выхода GPIO через регистр OUTOVER."""
    import machine
    addr = 0x40014000 + gpio_num * 8 + 4
    ctrl = machine.mem32[addr]
    ctrl = ctrl & 0xFFFFCFFF
    if invert:
        ctrl = ctrl | 0x2000
    machine.mem32[addr] = ctrl


def restart_ch12():
    global sm, inv
    if sm is not None:
        sm.active(0)
        time.sleep_ms(2)
    time.sleep_ms(2)

    d = max(1, min(49, duty))
    x_ticks = max(0, 2*d - 2)
    y_ticks = max(0, 96 - x_ticks)
    sm_freq = int(freq * 204)
    sm_freq = max(2000, min(SYS_CLK, sm_freq))

    try: PIO(0).remove_program()
    except: pass
    if inv:
        sm = StateMachine(0, make_prog_ch12_inv(), freq=sm_freq, set_base=Pin(0))
    else:
        sm = StateMachine(0, make_prog_ch12(), freq=sm_freq, set_base=Pin(0))
    sm.put(x_ticks)
    sm.put(y_ticks)
    sm.active(1)
    print("F:", freq, "D:", duty, "% INV:", inv)
    if dbg_on:
        _dbg_start()   # remove_program() выше стёр и счётчик


_ch3_loaded_prog = None
_ch3_loaded_prog_x2 = None
_ch3_phase_base = 0  # 0 или 500 — смещение фазы для активного sync_pin

def apply_ch3():
    """Пересобирает SM5 (и SM7 для X2) CH3."""
    global sm1, sm1_b2, ch3_rebuilding, _ch3_loaded_prog, _ch3_loaded_prog_x2
    global _ch3_dirty, _ch3_phase_base, _ch3_sm1_freq
    global _cached_ph1, _cached_ph1_b2, _ch3_last_word, _ch3_last_word_b2
    ch3_rebuilding = True
    time.sleep_ms(2)
    if sm1 is not None:
        sm1.active(0)
    if sm1_b2 is not None:
        try: sm1_b2.active(0)
        except: pass
        sm1_b2 = None
    if _ch3_loaded_prog_x2 is not None and _ch3_loaded_prog_x2 is not _ch3_loaded_prog:
        try: PIO(1).remove_program(_ch3_loaded_prog_x2)
        except: pass
    _ch3_loaded_prog_x2 = None
    if _ch3_loaded_prog is not None:
        try: PIO(1).remove_program(_ch3_loaded_prog)
        except: pass
        _ch3_loaded_prog = None

    ticks_per_period = min(62, SYS_CLK // ch3_freq)
    ticks_per_period = max(6, ticks_per_period)

    total = ticks_per_period - 2
    hi_ticks = max(1, min(total - 1, total * ch3_duty // 100))
    lo_ticks = max(1, total - hi_ticks)
    if hi_ticks > 31: hi_ticks = 31
    if lo_ticks > 31: lo_ticks = 31
    sm1_freq = min(SYS_CLK, ch3_freq * (hi_ticks + lo_ticks + 2))

    ch1_period_ticks = sm1_freq // freq

    # ─── Двойная синхронизация: выбор sync_pin по фазе ───
    base_sp = 1 if inv else 0
    alt_sp  = 1 - base_sp
    if ch3_phase_pct < 500:
        sp = base_sp
        _ch3_phase_base = 0
    else:
        sp = alt_sp
        _ch3_phase_base = 500

    local_pct = ch3_phase_pct - _ch3_phase_base
    phase_raw = max(0, (ch1_period_ticks * local_pct) // 1000 - 6)
    phase_ticks = max(1, phase_raw)

    _ch3_loaded_prog = make_prog_ch3(hi_ticks, lo_ticks, invert=inv)

    _ch3_sm1_freq = sm1_freq

    # ─── SM5: основная пачка (синхропин через in_base) ───
    sm1 = StateMachine(5, _ch3_loaded_prog, freq=sm1_freq,
                       set_base=Pin(4), in_base=Pin(sp))
    _w = _ch3_make_word(phase_ticks)
    sm1.put(ch3_count - 1)
    sm1.put(_w)
    _cached_ph1 = phase_ticks
    _ch3_last_word = _w

    # ─── SM7: X2 — ТА ЖЕ программа, другой in_base (экономит память PIO1) ───
    if ch3_x2 and ch3_linked:
        x2_sp = alt_sp if ch3_phase_pct < 500 else base_sp
        _ch3_loaded_prog_x2 = _ch3_loaded_prog
        sm1_b2 = StateMachine(7, _ch3_loaded_prog, freq=sm1_freq,
                              set_base=Pin(4), in_base=Pin(x2_sp))
        sm1_b2.put(ch3_count - 1)
        sm1_b2.put(_w)
        _cached_ph1_b2 = phase_ticks
        _ch3_last_word_b2 = _w
        # Синхронный старт SM5+SM7 в один такт (как pio_enable_sm_mask_in_sync):
        # оба счётчика периодов стартуют одновременно -> пачки X2 идут парой
        # в соседних полупериодах и не разъезжаются при пропусках.
        # SET-алиас атомарный — бит SM драйвера CYW43 не затирается.
        import machine
        # SM7: SET_COUNT=1 -> пишет только GP4. Строб на GP5 даёт только SM5,
        # иначе в X2 осциллограф ловил бы попеременно обе пачки пары.
        _pc = machine.mem32[PIO1_SM3_PINCTRL]
        machine.mem32[PIO1_SM3_PINCTRL] = (_pc & 0xE3FFFFFF) | (1 << 26)
        _m = (1 << 1) | (1 << 3)          # SM5 = PIO1 SM1, SM7 = PIO1 SM3
        machine.mem32[PIO1_CTRL_SET] = _m | (_m << 4) | (_m << 8)
    elif ch3_linked:
        sm1.active(1)

    ch3_rebuilding = False
    _ch3_dirty = True

    # Если автономный режим — грузим автономную программу на PIO0
    if not ch3_linked:
        apply_ch3_free()

    real_hi_pct = (hi_ticks + 1) * 100 // (hi_ticks + lo_ticks + 2)
    print("CH3: {}Hz D:{}% phase:{}% count:{} x2:{} linked:{} div:{}".format(
        ch3_freq, real_hi_pct, ch3_phase_pct, ch3_count, ch3_x2, ch3_linked, ch3_period_divider))


def _ch3_set_divider(val):
    """Смена делителя периодов.
    Без X2: SM не пересоздаётся — новое слово уходит через _ch3_push(),
    PIO подхватит его в начале следующего цикла (сразу после пачки).
    С X2: SM5 и SM7 подхватили бы новое значение в разные циклы и пара
    разъехалась бы навсегда -> синхронный перезапуск через apply_ch3()."""
    global ch3_period_divider
    new = max(1, min(21, val))
    if new == ch3_period_divider:
        return
    ch3_period_divider = new
    if ch3_x2 and ch3_linked and sm1_b2 is not None:
        apply_ch3()


def apply_ch3_free():
    """Автономный CH3 на PIO0 SM1 — не трогает PIO1 (CYW43)."""
    global sm_free, _ch3_free_prog
    if sm_free is not None:
        try: sm_free.active(0)
        except: pass
    if _ch3_free_prog is not None:
        try: PIO(0).remove_program(_ch3_free_prog)
        except: pass
        _ch3_free_prog = None

    ticks_per_period = min(62, SYS_CLK // ch3_freq)
    ticks_per_period = max(6, ticks_per_period)
    total = ticks_per_period - 2
    hi_ticks = max(1, min(total - 1, total * ch3_duty // 100))
    lo_ticks = max(1, total - hi_ticks)
    if hi_ticks > 31: hi_ticks = 31
    if lo_ticks > 31: lo_ticks = 31
    sm_freq = min(SYS_CLK, ch3_freq * (hi_ticks + lo_ticks + 2))
    carrier = max(50, min(100000, ch3_carrier))
    burst_ticks = ch3_count * (hi_ticks + lo_ticks + 2)
    carrier_period = sm_freq // carrier
    pause_ticks = max(1, carrier_period - burst_ticks)

    _ch3_free_prog = make_prog_ch3_free(hi_ticks, lo_ticks, invert=inv)
    sm_free = StateMachine(1, _ch3_free_prog, freq=sm_freq, set_base=Pin(4))
    sm_free.put(ch3_count - 1)
    sm_free.put(pause_ticks)
    sm_free.active(1)
    print("CH3 FREE on PIO0 SM1: count={} carrier={}Hz".format(ch3_count, carrier))


def stop_ch3_free():
    """Останавливает автономную программу CH3."""
    global sm_free, _ch3_free_prog
    if sm_free is not None:
        try: sm_free.active(0)
        except: pass
        sm_free = None
    if _ch3_free_prog is not None:
        try: PIO(0).remove_program(_ch3_free_prog)
        except: pass
        _ch3_free_prog = None


def switch_ch3_mode():
    """LK:1 — linked CH3 на PIO1. LK:0 — sm_free на PIO0."""
    global sm1, ch3_rebuilding
    if ch3_linked:
        ch3_rebuilding = True
        time.sleep_ms(2)
        stop_ch3_free()
        apply_ch3()
    else:
        ch3_rebuilding = True
        time.sleep_ms(2)
        if sm1 is not None:
            try: sm1.active(0)
            except: pass
        apply_ch3_free()


def restart_ch3():
    apply_ch3()


def print_state():
    if channel == 0:
        if mode == 0:
            print("=== FREQ ===", freq, "Hz  step:", freq_steps[freq_step_idx])
        else:
            print("=== DUTY ===", duty, "%  step:", duty_steps[duty_step_idx])
    else:
        if ch3_mode == 0:
            print("=== CH3 PHASE ===", ch3_phase_pct, "%")
        elif ch3_mode == 1:
            print("=== CH3 COUNT ===", ch3_count)
        elif ch3_mode == 2:
            print("=== CH3 FREQ ===", ch3_freq, "Hz  step:", CH3_FREQ_STEPS[CH3_FREQ_STEP_IDX])
        else:
            print("=== CH3 DUTY ===", ch3_duty, "%")



import sys
import select

# ------------------------------------------------------------------
# Дополнительные параметры
# ------------------------------------------------------------------
ch3_x2    = False
inv       = False   # инверсия всех каналов
pll_on    = False

# ФАПЧ из POBEDA — SM3 на PIO0, ждёт фронт GP2 → считает до HIGH GP26
from machine import Pin as _Pin

@asm_pio()
def prog_phase():
    wrap_target()
    wait(0, gpio, 2)
    wait(1, gpio, 2)
    set(y, 18) [31]
    label("d")
    jmp(y_dec, "d") [31]
    mov(x, invert(null))
    label("count")
    jmp(pin, "done")
    jmp(x_dec, "count")
    label("done")
    mov(isr, invert(x))
    push(noblock)
    wrap()

@asm_pio()
def prog_phase_back():
    wrap_target()
    wait(0, gpio, 2)
    wait(1, gpio, 2)
    wait(1, gpio, 26)
    mov(x, invert(null))
    label("count_back")
    jmp(pin, "still_high")
    jmp("done")
    label("still_high")
    jmp(x_dec, "count_back")
    label("done")
    mov(isr, invert(x))
    push(noblock)
    wrap()

pll_sm        = None
pll_sm_back   = None
pll_target    = 0
pll_target_norm = 0
pll_base_period_pio = 0
pll_sensitivity = 10
pll_last_mc_freq = 0
pll_last_mc_phase = 0
pll_target_p3 = 0
pll_last_ms   = 0
pll_phase_now = 0
pll_phase_smooth = 0
pll_phase_raw = 0
pll_diag_min = 0
pll_diag_max = 0
pll_diag_cnt = 0
pll_buf       = [0] * 8
pll_buf_idx   = 0
pll_buf_count = 0

pll_mode      = 0
pll_freq_base = 0
pll_freq_last_ms = 0
pll_search_dir = 1
pll_prev_abs_diff = 0
pll_reject_count = 0
pll_phase_smooth = 0
pll_fresh_count = 0
pll_signal_ok = 0
pll_return_block_ms = 0
pll_last_save_ms = 0
pll_stable_count = 0
pll_toward_base_cnt = 0
pll_last_step_dir = 0
pll_pre_step_diff = 0
pll_pre_step_freq = 0
pll_prev_smooth = 0
pll_diag_mode = False
pll_diag_ms = 0
pll_diag_step = 0
pll_capture = []
pll_diag_ms = 0
pll_diag_step = 0
pll_spread_count = 0

PIO0_SM0_CLKDIV = 0x502000c8


def set_ch12_freq_fast(new_freq):
    """Меняет частоту CH1/CH2 через CLKDIV без рестарта SM."""
    global _ch3_dirty
    import machine
    sm_freq = int(new_freq * 204)
    sm_freq = max(2000, min(SYS_CLK, sm_freq))
    div = SYS_CLK / sm_freq
    div_int = int(div)
    div_frac = int((div - div_int) * 256) & 0xFF
    if div_int < 1: div_int = 1
    if div_int > 65535: div_int = 65535
    new_val = (div_int << 16) | (div_frac << 8)
    if machine.mem32[PIO0_SM0_CLKDIV] != new_val:
        machine.mem32[PIO0_SM0_CLKDIV] = new_val
        _ch3_dirty = True


def set_ch12_duty_fast(new_duty):
    """Меняет duty плавно через серию микро-шагов по 1% с интервалом."""
    global sm, duty
    if sm is None:
        return
    target = max(1, min(99, new_duty))
    current = duty
    if abs(target - current) <= 1:
        duty = target
        _apply_duty_step(target)
        return
    direction = 1 if target > current else -1
    step_val = current
    while step_val != target:
        step_val += direction
        duty = step_val
        _apply_duty_step(step_val)
        time.sleep_us(500)


def _apply_duty_step(step_duty):
    """Один микро-шаг изменения duty — минимальный рестарт SM."""
    global sm
    if sm is None:
        return
    d = max(1, min(49, step_duty))
    x_ticks = max(0, 2*d - 2)
    y_ticks = max(0, 96 - x_ticks)
    sm.active(0)
    sm.restart()
    sm.put(x_ticks)
    sm.put(y_ticks)
    sm.active(1)

def pll_init():
    global pll_sm, pll_sm_back, pll_target_p3
    pll_target_p3 = ch3_phase_pct
    try:
        gp26 = _Pin(26, _Pin.IN)
        pll_sm = StateMachine(3, prog_phase, freq=125_000_000, jmp_pin=gp26)
        pll_sm.active(1)
        pll_sm_back = None
    except Exception as e:
        pll_sm = None
        pll_sm_back = None
        print("PLL init error:", e)

def pll_deinit():
    global pll_sm, pll_sm_back
    try:
        if pll_sm:
            pll_sm.active(0)
            try: pll_sm.deinit()
            except: pass
            pll_sm = None
    except: pass
    try:
        if pll_sm_back:
            pll_sm_back.active(0)
            try: pll_sm_back.deinit()
            except: pass
            pll_sm_back = None
    except: pass
    try: PIO(0).remove_program(prog_phase)
    except: pass

def pll_update():
    global ch3_phase_pct, pll_target, pll_target_p3
    global pll_last_ms, pll_phase_now, pll_phase_raw, pll_buf, pll_buf_idx, pll_buf_count
    global freq, pll_freq_base
    global pll_search_dir, pll_prev_abs_diff, pll_freq_last_ms
    global pll_reject_count, pll_spread_count
    global pll_phase_smooth
    global _paused, _prev_ph, _noise_cnt, _kz_warmup, pll_diag_mode, pll_diag_ms, pll_diag_step, pll_capture
    global pll_fresh_count
    global pll_target_norm, pll_base_period_pio
    global pll_sensitivity, pll_last_mc_freq, pll_last_mc_phase
    global pll_return_block_ms
    global pll_toward_base_cnt
    global pll_last_step_dir, pll_pre_step_diff, pll_pre_step_freq
    global pll_prev_smooth
    global _ch3_dirty
    global pll_signal_ok
    global pll_last_save_ms
    global pll_stable_count

    now = time.ticks_ms()
    if time.ticks_diff(now, pll_last_ms) < 1:
        return False
    pll_last_ms = now

    if pll_sm is None or pll_sm.rx_fifo() == 0:
        return False

    phase_front = 0
    while pll_sm.rx_fifo() > 0:
        phase_front = pll_sm.get()

    phase_back = 0
    if pll_sm_back is not None and pll_sm_back.rx_fifo() > 0:
        while pll_sm_back.rx_fifo() > 0:
            phase_back = pll_sm_back.get()

    if pll_mode == 1 and 10 < phase_back < 10000:
        phase = (phase_front + phase_back) // 2
    else:
        phase = phase_front

    pll_phase_raw = phase

    global pll_diag_min, pll_diag_max, pll_diag_cnt
    if pll_diag_cnt == 0:
        pll_diag_min = phase
        pll_diag_max = phase
    else:
        if phase < pll_diag_min: pll_diag_min = phase
        if phase > pll_diag_max: pll_diag_max = phase
    pll_diag_cnt += 1

    _period_now = 125_000_000 // max(freq, 1000)
    _period_pio = _period_now // 2
    if phase < 100 or phase > _period_pio:
        pll_reject_count += 1
        if pll_signal_ok < 200:
            pll_signal_ok += 3
        if pll_reject_count > 5000 and pll_mode == 1:
            if pll_freq_base >= 1000:
                freq = pll_freq_base
                set_ch12_freq_fast(freq)
            pll_reject_count = 0
        return False

    if pll_phase_smooth > 0 and abs(phase - pll_phase_smooth) > max(200, pll_phase_smooth // 3):
        pll_reject_count += 1
        if pll_signal_ok < 200:
            pll_signal_ok += 3
        return False

    _bs = len(pll_buf)
    _reject_threshold = max(200, _period_pio * 8 // 100)
    if pll_buf_count >= _bs:
        cur_avg = sum(pll_buf) // _bs
        if abs(phase - cur_avg) > _reject_threshold:
            pll_reject_count += 1
            if pll_signal_ok < 200:
                pll_signal_ok += 3
            if pll_reject_count > 6:
                if 10 < phase < _period_pio:
                    pll_buf[:] = [phase] * _bs
                    pll_buf_idx = 0
                    pll_buf_count = _bs
                pll_reject_count = 0
                return False
            return False
    pll_reject_count = 0

    if pll_signal_ok > 0:
        pll_signal_ok -= 1

    if pll_signal_ok > 10:
        if not ch3_x2:
            return False
        if pll_signal_ok > 40:
            return False

    pll_buf[pll_buf_idx] = phase
    pll_buf_idx = (pll_buf_idx + 1) % _bs
    if pll_buf_count < _bs:
        pll_buf_count += 1
    pll_fresh_count += 1

    avg = sum(pll_buf) // pll_buf_count
    pll_phase_now = avg

    if pll_phase_smooth == 0:
        pll_phase_smooth = avg
    else:
        pll_phase_smooth = (pll_phase_smooth * 7 + avg) // 8

    if pll_buf_count >= max(2, _bs // 2):
        bmin = min(pll_buf[:pll_buf_count])
        bmax = max(pll_buf[:pll_buf_count])
        _spread_threshold = max(160, _period_pio * 4 // 100)
        if bmax - bmin > _spread_threshold:
            pll_spread_count += 1
            if pll_spread_count > 10:
                if 10 < phase < _period_pio:
                    pll_buf[:] = [phase] * _bs
                    pll_buf_idx = 0
                    pll_buf_count = _bs
                pll_spread_count = 0
                return False
            return False
    pll_spread_count = 0

    if pll_target == 0:
        if pll_buf_count >= _bs:
            pll_target = avg
            pll_base_period_pio = _period_pio
            pll_target_norm = pll_target * 1000 // _period_pio if _period_pio > 0 else 0
            pll_target_p3 = ch3_phase_pct
            save_settings()
        return False

    if freq < 1000:
        freq = pll_freq_base if pll_freq_base >= 1000 else 27000
        set_ch12_freq_fast(freq)
        return False
    if pll_freq_base > 0 and pll_freq_base < 1000:
        pll_freq_base = freq
    period_ticks = 125_000_000 // freq
    half_period_ticks = period_ticks // 2

    diff = avg - pll_target
    if diff > half_period_ticks:  diff -= period_ticks
    elif diff < -half_period_ticks:  diff += period_ticks

    if pll_base_period_pio > 0 and _period_pio > 0:
        _smooth_norm = pll_phase_smooth * 1000 // _period_pio
        diff_freq = (_smooth_norm - pll_target_norm) * pll_base_period_pio // 1000
    else:
        diff_freq = diff

    if pll_mode == 0:
        shift_ticks = pll_phase_smooth - pll_target
        if shift_ticks > half_period_ticks:
            shift_ticks -= period_ticks
        elif shift_ticks < -half_period_ticks:
            shift_ticks += period_ticks
        shift_p3 = shift_ticks * 2000 // period_ticks
        new_p3 = (pll_target_p3 + shift_p3) % 1000
        if new_p3 < 0:
            new_p3 += 1000
        if new_p3 == ch3_phase_pct:
            return False
        delta = new_p3 - ch3_phase_pct
        if delta > 500: delta -= 1000
        elif delta < -500: delta += 1000
        if delta > 1: delta = 1
        elif delta < -1: delta = -1
        old_p3 = ch3_phase_pct
        _pll_apply_phase(old_p3, old_p3 + delta)
        return True
    else:
        if pll_base_period_pio > 0 and _period_pio > 0:
            shift_p3 = _smooth_norm - pll_target_norm
            if shift_p3 > 1000: shift_p3 -= 2000
            elif shift_p3 < -1000: shift_p3 += 2000
        else:
            shift_ticks = pll_phase_smooth - pll_target
            if shift_ticks > half_period_ticks:
                shift_ticks -= period_ticks
            elif shift_ticks < -half_period_ticks:
                shift_ticks += period_ticks
            shift_p3 = shift_ticks * 2000 // period_ticks
        new_p3 = (pll_target_p3 + shift_p3) % 1000
        if new_p3 < 0:
            new_p3 += 1000
        if new_p3 != ch3_phase_pct:
            delta = new_p3 - ch3_phase_pct
            if delta > 500: delta -= 1000
            elif delta < -500: delta += 1000
            if delta > 1: delta = 1
            elif delta < -1: delta = -1
            old_p3 = ch3_phase_pct
            _pll_apply_phase(old_p3, old_p3 + delta)

        if pll_phase_smooth < _period_pio * 20 // 100 or (pll_signal_ok > 10 and not ch3_x2):
            return True

        if time.ticks_diff(now, pll_freq_last_ms) < 200:
            return True
        pll_freq_last_ms = now

        if _period_pio <= 0:
            return False

        _RESONANCE_TARGET = 416
        _sn = pll_phase_smooth * 1000 // _period_pio
        _err = _RESONANCE_TARGET - _sn

        if abs(_err) < 2:
            pll_stable_count += 1
            pll_freq_base = freq
            if pll_stable_count >= 5:
                pll_target_p3 = ch3_phase_pct
                pll_target_norm = _sn
                pll_target = pll_phase_smooth
                pll_base_period_pio = _period_pio
            if time.ticks_diff(now, pll_last_save_ms) > 30000:
                pll_last_save_ms = now
                save_settings()
            return True

        pll_stable_count = 0
        _Kp = 1
        _step = _Kp * _err
        if _step > 100: _step = 100
        elif _step < -100: _step = -100
        if _step == 0:
            _step = 1 if _err > 0 else -1

        new_freq = freq + _step
        if new_freq > 50000: new_freq = 50000
        if new_freq < 15000: new_freq = 15000

        if new_freq != freq:
            freq = new_freq
            set_ch12_freq_fast(freq)

        if pll_diag_mode and len(pll_capture) < 500:
            pll_capture.append('KZ sn={} err={} st={} f={}'.format(_sn, _err, _step, freq))

        return True



def apply_ch3_x2():
    """Применяет ch3 с учётом x2."""
    if sm1 is not None:
        sm1.active(0)
        time.sleep_ms(1)
    try:
        PIO(1).remove_program()
    except:
        pass
    time.sleep_ms(1)
    ticks_per_period = min(62, SYS_CLK // ch3_freq)
    ticks_per_period = max(4, ticks_per_period)
    sm1_freq = min(SYS_CLK, ch3_freq * ticks_per_period)
    useful = max(2, ticks_per_period - 2)
    hi_ticks = max(1, min(useful - 1, useful * ch3_duty // 100))
    lo_ticks = max(1, useful - hi_ticks)
    ch1_period_ticks = sm1_freq // freq
    phase_ticks = max(0, min(ch1_period_ticks - 2, (ch1_period_ticks * ch3_phase_pct) // 1000 - 6))
    count = ch3_count
    if ch3_x2:
        prog = make_prog_ch3_x2(hi_ticks, lo_ticks)
    else:
        prog = make_prog_ch3(hi_ticks, lo_ticks)
    import rp2
    sm_new = StateMachine(4, prog, freq=sm1_freq, set_base=Pin(4))
    sm_new.put(phase_ticks)
    sm_new.put(count - 1)
    sm_new.active(1)
    globals()['sm1'] = sm_new

def save_settings():
    try:
        import ujson
        save_freq = pll_freq_base if (pll_mode == 1 and pll_freq_base > 0) else freq
        if save_freq < 10 or save_freq > 500000: save_freq = 27000
        with open("settings.json", "w") as f:
            f.write(ujson.dumps({
                "freq": save_freq, "duty": duty,
                "ch3_freq": ch3_freq, "ch3_duty": ch3_duty,
                "ch3_count": ch3_count, "ch3_phase_pct": ch3_phase_pct,
                "ch3_x2": ch3_x2, "ch3_linked": ch3_linked, "ch3_carrier": ch3_carrier, "ch3_pd": ch3_period_divider, "inv": inv,
                "pll_on": pll_on, "pll_target": pll_target, "pll_target_p3": pll_target_p3,
                "pll_target_norm": pll_target_norm, "pll_base_period_pio": pll_base_period_pio,
                "pll_mode": pll_mode, "pll_freq_base": pll_freq_base
            }))
    except:
        pass

def load_settings():
    global freq, duty, ch3_freq, ch3_duty, ch3_count, ch3_phase_pct, ch3_x2, inv
    global ch3_linked, ch3_carrier, ch3_period_divider
    global pll_on, pll_target, pll_target_p3, pll_mode, pll_freq_base
    global pll_base_period_pio, pll_target_norm
    try:
        import ujson
        with open("settings.json", "r") as f:
            s = ujson.loads(f.read())
        freq          = s.get("freq", freq)
        duty          = s.get("duty", duty)
        ch3_freq      = s.get("ch3_freq", ch3_freq)
        ch3_duty      = s.get("ch3_duty", ch3_duty)
        ch3_count     = s.get("ch3_count", ch3_count)
        ch3_phase_pct = s.get("ch3_phase_pct", ch3_phase_pct)
        ch3_x2        = s.get("ch3_x2", ch3_x2)
        ch3_linked    = s.get("ch3_linked", ch3_linked)
        ch3_carrier   = s.get("ch3_carrier", ch3_carrier)
        ch3_period_divider = max(1, min(21, s.get("ch3_pd", 1)))
        inv           = s.get("inv", inv)
        pll_on        = s.get("pll_on", False)
        pll_target    = s.get("pll_target", 0)
        pll_target_p3 = s.get("pll_target_p3", 0)
        pll_target_norm = s.get("pll_target_norm", 0)
        pll_base_period_pio = s.get("pll_base_period_pio", 0)
        pll_mode      = s.get("pll_mode", 0)
        pll_freq_base = s.get("pll_freq_base", 0)
        if freq < 10 or freq > 500000: freq = 27000
        if pll_freq_base < 10: pll_freq_base = freq
        if pll_target != 0 and pll_target_norm == 0:
            pll_target = 0
        if pll_on and pll_target_p3 > 0:
            ch3_phase_pct = pll_target_p3
    except:
        pass

def send_status():
    global pll_diag_min, pll_diag_max, pll_diag_cnt
    sys.stdout.write('{"f1":'+str(freq)+',"d1":'+str(duty)+',"f3":'+str(ch3_freq)+',"d3":'+str(ch3_duty)+',"n3":'+str(ch3_count)+',"p3":'+str(ch3_phase_pct)+',"x2":'+str(1 if ch3_x2 else 0)+',"iv":'+str(1 if inv else 0)+',"pl":'+str(1 if pll_on else 0)+',"lk":'+str(1 if ch3_linked else 0)+',"fc":'+str(ch3_carrier)+',"ph":'+str(pll_phase_now)+',"tgt":'+str(pll_target)+',"rw":'+str(pll_phase_raw)+',"pm":'+str(pll_mode)+',"mn":'+str(pll_diag_min)+',"mx":'+str(pll_diag_max)+',"cn":'+str(pll_diag_cnt)+',"pd":'+str(ch3_period_divider - 1)+',"h3":'+str(ch3_period_divider - 1)+'}\n')
    pll_diag_min = 0
    pll_diag_max = 0
    pll_diag_cnt = 0

_OTA_TMP = 'main.tmp'
_ota_f = None
_ota_left = 0
_ota_got = 0
_ota_expect_size = 0
_ota_expect_crc = 0
_ota_state = 0  # 0 idle, 1 receiving, 2 received (wait UF:1)
_uf_last_ms = 0
_paused = False
_prev_ph = 0
_noise_cnt = 0
_kz_warmup = 0

def _ota_active():
    return _ota_state != 0

def _ota_scrub_legacy():
    import os as _os
    for n in (_OTA_TMP, 'main.tmp', 'update.py', 'update.tmp', 'backup.py'):
        try: _os.remove(n)
        except: pass

def _ota_stop(cancel=False):
    global _ota_f, _ota_left, _ota_got, _ota_state, _ota_expect_size, _ota_expect_crc
    if _ota_f:
        try: _ota_f.close()
        except: pass
        _ota_f = None
    if cancel:
        _ota_scrub_legacy()
    _ota_left = 0
    _ota_got = 0
    _ota_expect_size = 0
    _ota_expect_crc = 0
    _ota_state = 0

def _ota_extract_ver(data):
    for q in (b"'", b'"'):
        pat = q + b"VER:"
        i = 0
        while i < len(data):
            j = data.find(pat, i)
            if j < 0:
                break
            start = j + 1
            end = start
            while end < len(data) and data[end:end+1] != q:
                end += 1
            tag = data[start:end].decode()
            if tag.startswith('VER:') and len(tag) > 12:
                return tag
            i = end + 1
    return None

def _ota_extract_ver_file(path):
    try:
        with open(path, 'rb') as f:
            return _ota_extract_ver(f.read(4096))
    except:
        return None

def _ota_crc32_byte(crc, b):
    crc ^= b
    for _ in range(8):
        crc = (crc >> 1) ^ 0xEDB88320 if crc & 1 else crc >> 1
    return crc

def _ota_crc32_file(path):
    crc = 0xFFFFFFFF
    try:
        with open(path, 'rb') as f:
            while True:
                chunk = f.read(2048)
                if not chunk:
                    break
                for b in chunk:
                    crc = _ota_crc32_byte(crc, b)
        return (crc ^ 0xFFFFFFFF) & 0xffffffff
    except:
        return None

def _ota_feed(data):
    """Пишет бинарный блок в main.tmp. Возвращает хвост для текстовых команд."""
    global _ota_f, _ota_left, _ota_got, _ota_state, _uf_last_ms
    if _ota_state != 1 or _ota_f is None or _ota_left <= 0:
        return data
    n = len(data)
    if n > _ota_left:
        n = _ota_left
    if n > 0:
        _ota_f.write(data[:n])
        _ota_got += n
        _ota_left -= n
        _uf_last_ms = time.ticks_ms()
    rest = data[n:]
    if _ota_left <= 0:
        try:
            _ota_f.flush()
            _ota_f.close()
        except:
            pass
        _ota_f = None
        _ota_state = 2
        print('OTA:RECEIVED:' + str(_ota_got))
        if tcp_client:
            try: tcp_client.send(('OTA:RECEIVED:' + str(_ota_got) + '\n').encode())
            except: pass
    return rest

def _ota_finalize(_reply):
    global _ota_state
    import os as _os
    import gc
    import machine
    if _ota_state != 2:
        _reply('OTA:ERR:NOTREADY')
        return
    _ota_state = 0
    try:
        st = _os.stat(_OTA_TMP)
        if st[6] != _ota_expect_size:
            _os.remove(_OTA_TMP)
            _reply('OTA:ERR:SIZE:' + str(st[6]) + '/' + str(_ota_expect_size))
            return
        gc.collect()
        crc = _ota_crc32_file(_OTA_TMP)
        if crc is None:
            _os.remove(_OTA_TMP)
            _reply('OTA:ERR:CRC:READ')
            return
        _reply('OTA:CRC:' + '{:08X}'.format(crc))
        if crc != _ota_expect_crc:
            _os.remove(_OTA_TMP)
            _reply('OTA:ERR:CRC:' + '{:08X}'.format(crc))
            return
        tag = _ota_extract_ver_file(_OTA_TMP)
        if tag:
            _reply('OTA:FILEVER:' + tag)
        _reply('OTA:VERIFIED:' + str(st[6]))
        try: _os.remove('main.py')
        except: pass
        for old in ('update.py', 'update.tmp', 'backup.py'):
            try: _os.remove(old)
            except: pass
        try:
            _os.rename(_OTA_TMP, 'main.py')
        except OSError as e:
            try: _os.remove(_OTA_TMP)
            except: pass
            _reply('OTA:ERR:SWAP:' + str(e))
            return
        _reply('OTA:OK')
        _reply('OTA:REBOOT')
        gc.collect()
        time.sleep_ms(200)
        machine.reset()
    except Exception as e:
        try: _os.remove(_OTA_TMP)
        except: pass
        _reply('OTA:ERR:' + str(e))

def process_cmd(line):
    global freq, duty, ch3_freq, ch3_duty, ch3_count, ch3_phase_pct
    global ch3_x2, ch3_linked, ch3_carrier, inv, pll_on, pll_target, pll_target_p3, pll_last_ms, pll_phase_now, pll_buf, pll_buf_idx, pll_buf_count
    global pll_mode, pll_freq_base, pll_search_dir, pll_prev_abs_diff
    global pll_diag_mode, pll_diag_ms, pll_diag_step, pll_capture
    global pll_phase_smooth
    global _uf_last_ms, _paused, _prev_ph, _noise_cnt, _ch3_dirty
    global _ota_f, _ota_left, _ota_got, _ota_state, _ota_expect_size, _ota_expect_crc
    global dbg_on
    raw = line.rstrip('\r')
    cmd = raw.strip()

    def _reply(msg):
        print(msg)
        if tcp_client:
            try: tcp_client.send((msg + '\n').encode())
            except: pass
        if udp_client_addr:
            try: udp_sock.sendto((msg + '\n').encode(), udp_client_addr)
            except: pass

    if cmd == "UF:1":
        _ota_finalize(_reply)
        return
    if cmd == "UF:2":
        _ota_stop(cancel=True)
        _reply('OTA:CANCEL')
        return
    if cmd.startswith("UF:0"):
        parts = cmd.split(':')
        expect_bytes = 0
        expect_crc = 0
        if len(parts) >= 3:
            try: expect_bytes = int(parts[2])
            except: pass
        if len(parts) >= 4:
            try: expect_crc = int(parts[3], 16)
            except: pass
        if expect_bytes < 100:
            _reply('OTA:ERR:SIZE')
            return
        _ota_stop(cancel=True)
        try:
            _ota_scrub_legacy()
            _ota_f = open(_OTA_TMP, 'wb')
            _ota_left = expect_bytes
            _ota_got = 0
            _ota_expect_size = expect_bytes
            _ota_expect_crc = expect_crc
            _ota_state = 1
            _uf_last_ms = time.ticks_ms()
            _reply('OTA:START:' + str(expect_bytes))
        except Exception as e:
            _ota_stop(cancel=True)
            _reply('OTA:ERR:' + str(e))
        return
    if cmd == "UF:3":
        _ota_scrub_legacy()
        _reply('OTA:CLEAN')
        return
    if not cmd:
        return
    if cmd == "VER":
        _reply('VER:10_32:2026-09-11:main.py'); return
    if cmd == "ST:1":
        _paused = True; print('*** PAUSED ***'); return
    if cmd == "ST:0":
        _paused = False; print('*** RESUMED ***'); return
    if cmd == "RB:1":
        import machine; machine.soft_reset()

    if cmd == "GET":
        if tcp_client:
            send_status_wifi(force=True)
        else:
            send_status()
        return
    try:
        key, val = cmd.split(":")
        val = int(val)
    except:
        return
    changed12 = False
    changed3  = False
    only_freq = False
    only_duty = False
    only_phase = False
    if key == "F1":
        if pll_on:
            send_status()
            return
        old_freq = freq
        freq = max(10, min(500000, val))
        if freq != old_freq:
            only_freq = True
            pll_freq_base = freq
            pll_target = 0
    elif key == "D1":
        old_duty = duty
        duty = max(1, min(99, val))
        if duty != old_duty:
            only_duty = True
    elif key == "F3":
        ch3_freq = max(CH3_FREQ_MIN, min(CH3_FREQ_MAX, val))
        changed3 = True
    elif key == "D3":
        ch3_duty = max(1, min(99, val))
        changed3 = True
    elif key == "N3":
        ch3_count = max(CH3_COUNT_MIN, min(CH3_COUNT_MAX, val))
        changed3 = True
    elif key == "P3":
        old_p3 = ch3_phase_pct
        ch3_phase_pct = max(0, min(999, val))
        if ch3_phase_pct != old_p3:
            if (old_p3 < 500) == (ch3_phase_pct < 500):
                only_phase = True
            else:
                changed3 = True
    elif key == "H3":
        # Приложение: 0 = бить каждый период, 1 = через один, ... 20 = через 20
        _ch3_set_divider(val + 1)
        _save_defer()
        send_status()
        return
    elif key == "PD":
        # Прямой делитель: 1 = каждый период, 2 = через один, ...
        _ch3_set_divider(val)
        _save_defer()
        send_status()
        return
    elif key == "DB":
        dbg_on = bool(val)
        if dbg_on:
            _dbg_start()
        else:
            _dbg_stop()
        print("DBG:", "ON" if dbg_on else "OFF")
        return
    elif key == "X2":
        ch3_x2 = bool(val)
        if pll_on:
            pll_target = 0
            pll_target_norm = 0
            pll_base_period_pio = 0
            pll_target_p3 = ch3_phase_pct
            pll_phase_smooth = 0
            pll_signal_ok = 0
            pll_stable_count = 0
            pll_buf[:] = [0] * len(pll_buf)
            pll_buf_idx = 0
            pll_buf_count = 0
        changed3 = True
    elif key == "LK":
        ch3_linked = bool(val)
        switch_ch3_mode()
        save_settings()
        send_status()
        return
    elif key == "FC":
        ch3_carrier = max(50, min(100000, val))
        if not ch3_linked:
            apply_ch3_free()
        save_settings()
        send_status()
        return
    elif key == "IV":
        inv = bool(val)
        changed12 = True
        changed3  = True
    elif key == "PL":
        pll_on = bool(val)
        pll_target    = 0
        pll_target_norm = 0
        pll_base_period_pio = 0
        pll_target_p3 = ch3_phase_pct
        pll_freq_base = freq
        pll_last_ms   = 0
        pll_phase_now = 0
        pll_phase_smooth = 0
        pll_signal_ok = 0
        pll_stable_count = 0
        pll_buf[:]    = [0] * len(pll_buf)
        pll_buf_idx   = 0
        pll_buf_count = 0
        if pll_on:
            pll_deinit()
            pll_init()
        else:
            pll_deinit()
        save_settings()
    elif key == "PM":
        pll_mode = 1 if val else 0
        pll_target    = 0
        pll_target_norm = 0
        pll_base_period_pio = 0
        pll_target_p3 = ch3_phase_pct
        pll_freq_base = freq
        pll_last_ms   = 0
        pll_phase_now = 0
        pll_phase_smooth = 0
        pll_signal_ok = 0
        pll_stable_count = 0
        pll_buf[:]    = [0] * len(pll_buf)
        pll_buf_idx   = 0
        pll_buf_count = 0
        save_settings()
        send_status()
        return
    elif key == "SC":
        if val == 1:
            pll_capture.clear()
            pll_diag_mode = True
            print('CAPTURE: ON — каждое изменение freq записывается')
        elif val == 2:
            pll_diag_mode = False
            print('CAPTURE:', len(pll_capture), 'events')
            for row in pll_capture:
                sys.stdout.write(row + '\n')
            pll_capture.clear()
        elif val == 3:
            print('RAW CAPTURE: 500 samples...')
            raw = []
            sm = pll_sm
            if sm:
                while sm.rx_fifo() > 0: sm.get()
                for _ in range(500):
                    t0 = time.ticks_us()
                    while sm.rx_fifo() == 0:
                        if time.ticks_diff(time.ticks_us(), t0) > 5000: break
                    if sm.rx_fifo() > 0:
                        raw.append(sm.get())
                for i, v in enumerate(raw):
                    sys.stdout.write('{} {}\n'.format(i, v))
                print('RAW: done,', len(raw), 'samples')
            else:
                print('RAW: pll_sm not active')
        else:
            pll_diag_mode = False
            pll_capture.clear()
            print('CAPTURE: off')
        return
    if changed12:
        restart_ch12()
        apply_ch3()
    elif only_freq:
        set_ch12_freq_fast(freq)
    elif only_duty:
        set_ch12_duty_fast(duty)
    elif only_phase:
        _ch3_dirty = True
        _ch3_recalc()
    elif changed3:
        apply_ch3()
    _save_defer()  # отложенная запись — flash не блокирует WiFi при слайдере
    if tcp_client:
        _wifi_mark_dirty()
    else:
        send_status()

# ─── Отложенная запись настроек (debounce 3 сек) ───
# Flash-запись на RP2040 блокирует CPU на ~50мс (erase 4KB sector).
# При слайдере (25 команд/сек × 50мс) WiFi-драйвер CYW43 заморожен
# 100% времени → TCP таймаут → обрыв связи на телефоне.
# Решение: save_settings() вызывается ТОЛЬКО после 3 сек тишины.
_save_pending = False
_save_ms = 0

def _save_defer():
    global _save_pending, _save_ms
    _save_pending = True
    _save_ms = time.ticks_ms()

def _save_tick():
    """Вызывается в main loop. Если прошло 2 сек без команд — пишем flash."""
    global _save_pending
    if _save_pending and time.ticks_diff(time.ticks_ms(), _save_ms) > 2000:
        _save_pending = False
        save_settings()

def _save_now():
    """Принудительное сохранение — при обрыве связи."""
    global _save_pending
    if _save_pending:
        _save_pending = False
        save_settings()

# ------------------------------------------------------------------
# Старт (WiFi поднимается даже если PIO упал — иначе плата «мертвая»)
# ------------------------------------------------------------------
try:
    load_settings()
    restart_ch12()
    apply_ch3()
    if pll_on:
        pll_init()
    print("FW 10_32")
except Exception as e:
    print("BOOT ERR:", e)
try:
    send_status()
except:
    pass

# ------------------------------------------------------------------
# WiFi точка доступа
# ------------------------------------------------------------------
WIFI_SSID     = "PWMGen"
WIFI_PASSWORD = "12345678"
WIFI_PORT     = 8888
UDP_PORT      = 8889   # Android: команды в реальном времени (TCP :8888 — ноутбук/OTA)

ap = network.WLAN(network.AP_IF)
try:
    ap.active(False)
    time.sleep_ms(500)
except:
    pass
for _wifi_retry in range(10):
    try:
        ap.active(True)
        time.sleep_ms(300)
        if ap.active(): break
    except Exception as e:
        print("WiFi retry:", _wifi_retry, e)
        time.sleep_ms(1000)
ap.config(ssid=WIFI_SSID, security=0)
while not ap.active():
    time.sleep_ms(100)
for _ in range(50):
    if ap.ifconfig()[0] != '0.0.0.0':
        break
    time.sleep_ms(100)
print("WiFi AP:", ap.ifconfig())

# ═══════════════════════════════════════════════════════════
# Впиши имя и пароль домашнего WiFi (между кавычками):
HOME_SSID = ""
HOME_PASS = ""
STATIC_IP      = "192.168.0.222"
STATIC_NETMASK = "255.255.255.0"
STATIC_GATEWAY = "192.168.0.1"
STATIC_DNS     = "192.168.0.1"
# ═══════════════════════════════════════════════════════════
sta = None
sta_ok = False
_sta_t0 = 0
if HOME_SSID:
    sta = network.WLAN(network.STA_IF)
    sta.active(True)
    try: sta.ifconfig((STATIC_IP, STATIC_NETMASK, STATIC_GATEWAY, STATIC_DNS))
    except: pass
    sta.connect(HOME_SSID, HOME_PASS)
    _sta_t0 = time.ticks_ms()
    print("WiFi STA: подключаюсь к", HOME_SSID, "(фон)")

tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
tcp_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
tcp_server.bind(('0.0.0.0', WIFI_PORT))
tcp_server.listen(1)
tcp_server.setblocking(False)
tcp_client = None
tcp_buf = ""

udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
udp_sock.bind(('0.0.0.0', UDP_PORT))
udp_sock.setblocking(False)
udp_client_addr = None
_last_udp_status_ms = 0

buf = ""
last_status_ms = time.ticks_ms()
poll = select.poll()
poll.register(sys.stdin, select.POLLIN)

# ─── WiFi: команды и статус РАЗДЕЛЕНЫ (как C#-прокси на ноутбуке) ───
# Раньше: после КАЖДОЙ команды force send_status_wifi → ~200 байт × 25/сек
#         забивало AP + блокировало recv пока process_cmd() не закончит.
# Теперь: команды обрабатываются по одной за итерацию loop,
#         статус — отдельно (GET, период 500мс, или _wifi_status_dirty).
_last_wifi_reply_ms = 0
_wifi_status_dirty = False
_WIFI_HEAVY_PREFIX = ('F3:', 'D3:', 'FC:')

def _status_json():
    return '{"f1":'+str(freq)+',"d1":'+str(duty)+',"f3":'+str(ch3_freq)+',"d3":'+str(ch3_duty)+',"n3":'+str(ch3_count)+',"p3":'+str(ch3_phase_pct)+',"x2":'+str(1 if ch3_x2 else 0)+',"iv":'+str(1 if inv else 0)+',"pl":'+str(1 if pll_on else 0)+',"lk":'+str(1 if ch3_linked else 0)+',"fc":'+str(ch3_carrier)+',"ph":'+str(pll_phase_now)+',"tgt":'+str(pll_target)+',"pm":'+str(pll_mode)+',"pd":'+str(ch3_period_divider - 1)+',"h3":'+str(ch3_period_divider - 1)+'}\n'

def send_status_wifi(force=False):
    global tcp_client, _last_wifi_reply_ms, _wifi_status_dirty
    if not tcp_client or _ota_active():
        return
    now = time.ticks_ms()
    if not force and time.ticks_diff(now, _last_wifi_reply_ms) < 50:
        return
    _last_wifi_reply_ms = now
    _wifi_status_dirty = False
    try:
        tcp_client.send(_status_json().encode())
    except OSError as e:
        if e.args[0] != 11:
            _close_wifi_client()
    except:
        _close_wifi_client()

def send_status_udp(force=False):
    global udp_client_addr, _last_udp_status_ms, _wifi_status_dirty
    if not udp_client_addr:
        return
    now = time.ticks_ms()
    if not force and time.ticks_diff(now, _last_udp_status_ms) < 100:
        return
    _last_udp_status_ms = now
    _wifi_status_dirty = False
    try:
        udp_sock.sendto(_status_json().encode(), udp_client_addr)
    except:
        pass

def _wifi_coalesce_heavy():
    """F3/D3/FC — в буфере оставляем только последнюю (лишние apply_ch3 не нужны)."""
    global tcp_buf
    if tcp_buf.count('\n') < 2:
        return
    parts = tcp_buf.split('\n')
    incomplete = parts[-1]
    lines = [l for l in parts[:-1] if l]
    if len(lines) <= 1:
        return
    last_idx = {}
    skip = set()
    for i, ln in enumerate(lines):
        for p in _WIFI_HEAVY_PREFIX:
            if ln.startswith(p):
                if p in last_idx:
                    skip.add(last_idx[p])
                last_idx[p] = i
                break
    if not skip:
        return
    kept = [ln for i, ln in enumerate(lines) if i not in skip]
    tcp_buf = '\n'.join(kept) + '\n' + incomplete

def _wifi_mark_dirty():
    global _wifi_status_dirty
    _wifi_status_dirty = True

def _close_wifi_client():
    global tcp_client, tcp_buf
    if _ota_active():
        _ota_stop(cancel=True)
        print('OTA:CANCEL_DISCONNECT')
    _save_now()
    if tcp_client:
        try: tcp_client.close()
        except: pass
    tcp_client = None
    tcp_buf = ""

last_accept_ms = 0

# ─── CH3: обновление фазы/делителя в PIO ───
# PIO держит последнее слово сам; _ch3_push() шлёт только изменения.
_ch3_dirty = True

def _pll_apply_phase(old_p3, new_p3):
    """ФАПЧ: плавно через feeder; apply_ch3 только при переходе 0↔500 (смена sync_pin)."""
    global ch3_phase_pct, _ch3_dirty
    new_p3 = new_p3 % 1000
    ch3_phase_pct = new_p3
    if (old_p3 < 500) != (new_p3 < 500):
        apply_ch3()
    else:
        _ch3_dirty = True
        _ch3_recalc()

def _ch3_recalc():
    """Пересчёт кэшированного phase для feeder.
    Использует _ch3_phase_base (0 или 500), установленный apply_ch3()."""
    global _cached_ph1, _cached_ph1_b2, _ch3_dirty, _ch3_phase_base
    try:
        new_base = 0 if ch3_phase_pct < 500 else 500
        if new_base != _ch3_phase_base:
            apply_ch3()
            return
        ticks_per_period = min(62, SYS_CLK // ch3_freq)
        ticks_per_period = max(6, ticks_per_period)
        total = ticks_per_period - 2
        hi_t = max(1, min(total - 1, total * ch3_duty // 100))
        lo_t = max(1, total - hi_t)
        if hi_t > 31: hi_t = 31
        if lo_t > 31: lo_t = 31
        sm1f = min(SYS_CLK, ch3_freq * (hi_t + lo_t + 2))
        ch1_pt = sm1f // freq

        local_pct = ch3_phase_pct - _ch3_phase_base
        if local_pct < 0: local_pct = 0
        phase_raw = max(0, (ch1_pt * local_pct) // 1000 - 6)
        phase = max(1, phase_raw)

        _cached_ph1 = phase

        if ch3_x2:
            _cached_ph1_b2 = phase

        _ch3_dirty = False
    except:
        pass

def _ch3_push():
    """Отправка слова (phase<<5 | divider-1) в SM5/SM7 — ТОЛЬКО при изменении.
    PIO хранит последнее слово сам, поэтому постоянная докормка и второе ядро
    больше не нужны. Кладём только в пустой FIFO: в очереди максимум одно
    слово, PIO всегда получает самое свежее значение."""
    global _ch3_last_word, _ch3_last_word_b2
    if ch3_rebuilding or not ch3_linked or sm1 is None:
        return
    if _ch3_dirty:
        _ch3_recalc()
        if ch3_rebuilding or sm1 is None:
            return
    w = _ch3_make_word(_cached_ph1)
    if w != _ch3_last_word and sm1.tx_fifo() == 0:
        sm1.put(w)
        _ch3_last_word = w
    if ch3_x2 and sm1_b2 is not None:
        w2 = _ch3_make_word(_cached_ph1_b2)
        if w2 != _ch3_last_word_b2 and sm1_b2.tx_fifo() == 0:
            sm1_b2.put(w2)
            _ch3_last_word_b2 = w2

while True:
    # USB команды
    res = poll.poll(0)
    if res:
        ch = sys.stdin.read(1)
        if ch == "\n":
            process_cmd(buf)
            buf = ""
        else:
            buf += ch

    # WiFi — новое подключение (только если клиента нет)
    if tcp_client is None and time.ticks_diff(time.ticks_ms(), last_accept_ms) > 200:
        last_accept_ms = time.ticks_ms()
        try:
            tcp_client, addr = tcp_server.accept()
            tcp_client.setblocking(False)
            tcp_buf = ""
            print("WiFi client:", addr)
            send_status_wifi(force=True)
        except OSError as e:
            if e.args[0] != 11:
                try: tcp_server.close()
                except: pass
                time.sleep_ms(100)
                tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                tcp_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                tcp_server.bind(('0.0.0.0', WIFI_PORT))
                tcp_server.listen(1)
                tcp_server.setblocking(False)
        except Exception:
            try: tcp_server.close()
            except: pass
            time.sleep_ms(100)
            tcp_server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            tcp_server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            tcp_server.bind(('0.0.0.0', WIFI_PORT))
            tcp_server.listen(1)
            tcp_server.setblocking(False)

    # WiFi — чтение и обработка команд (TCP — OTA и совместимость)
    if _ota_active() and time.ticks_diff(time.ticks_ms(), _uf_last_ms) > 120000:
        _ota_stop(cancel=True)
        print('OTA:TIMEOUT')

    if tcp_client:
        _ota_reads = 64 if _ota_state == 1 else 1
        _ota_closed = False
        for _ in range(_ota_reads):
            try:
                data = tcp_client.recv(4096 if _ota_state == 1 else 512)
                if data:
                    if _ota_state == 1:
                        tail = _ota_feed(data)
                        if tail:
                            tcp_buf += tail.decode('utf-8', 'ignore')
                    else:
                        tcp_buf += data.decode('utf-8', 'ignore')
                    if _ota_state != 1:
                        _wifi_coalesce_heavy()
                    while '\n' in tcp_buf:
                        line, tcp_buf = tcp_buf.split('\n', 1)
                        process_cmd(line)
                else:
                    _close_wifi_client()
                    _ota_closed = True
                    break
            except OSError as e:
                if e.args[0] == 11:
                    break
                _close_wifi_client()
                _ota_closed = True
                break
            except:
                _close_wifi_client()
                _ota_closed = True
                break
        if _ota_closed:
            pass

    # UDP — Android (фаза, N3, частота). OTA только по TCP.
    try:
        data, addr = udp_sock.recvfrom(256)
        if data:
            udp_client_addr = addr
            for line in data.decode('utf-8', 'ignore').split('\n'):
                line = line.strip()
                if not line or line.startswith('UF:'):
                    continue
                process_cmd(line)
                _wifi_mark_dirty()
    except OSError as e:
        if e.args[0] != 11:
            pass
    except:
        pass

    # Проверка STA (фоновая, не блокирует)
    if sta and not sta_ok:
        if sta.isconnected():
            sta_ok = True
            print("WiFi STA:", sta.ifconfig())
            print(">>> http://" + sta.ifconfig()[0] + ":" + str(WIFI_PORT))
        elif time.ticks_diff(time.ticks_ms(), _sta_t0) > 20000:
            sta.active(False)
            sta = None
            print("WiFi STA: таймаут")

    if pll_on and not _paused and not _ota_active():
        pll_update()

    try:
        _ch3_push()   # новая фаза/делитель CH3 -> PIO (только при изменении)
    except:
        pass
    if _dbg_sm is not None:
        try: _dbg_tick()
        except Exception as e: print("DBG tick ERR:", e)

    _save_tick()  # отложенная запись flash (через 3 сек тишины)

    now = time.ticks_ms()
    if not _ota_active() and time.ticks_diff(now, last_status_ms) > 500:
        last_status_ms = now
        send_status()
        if tcp_client:
            send_status_wifi()
        if udp_client_addr:
            send_status_udp()
    elif _wifi_status_dirty and not _ota_active():
        if tcp_client and not _ota_active():
            send_status_wifi()
        if udp_client_addr:
            send_status_udp()
