package com.pwmgen;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONObject;

/**
 * Нативный осциллограф — вся отрисовка вне WebView (нет блокировки JS).
 */
public class ScopeView extends View {

    private static final int BG = Color.rgb(2, 2, 8);
    private static final int GRID = Color.rgb(10, 16, 32);
    private static final int CH1 = Color.rgb(0, 255, 136);
    private static final int CH2 = Color.rgb(255, 102, 0);
    private static final int CH3 = Color.rgb(0, 170, 255);
    private static final int CH3X2 = Color.rgb(170, 68, 255);
    private static final int DIM = Color.rgb(42, 53, 80);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect clip = new Rect();

    private int freq = 20000, duty = 50, f3 = 1_000_000, fc = 1000, d3 = 50, n3 = 5, ph = 0;
    private boolean ch3Linked = true, x2 = false, zoom = false;
    private float timeScale = 1f, viewStart = 0f;

    public ScopeView(Context ctx) {
        super(ctx);
        init();
    }

    public ScopeView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    public void updateFromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            freq = o.optInt("freq", freq);
            duty = o.optInt("duty", duty);
            f3 = o.optInt("f3", f3);
            fc = o.optInt("fc", fc);
            d3 = o.optInt("d3", d3);
            n3 = o.optInt("n3", n3);
            ph = o.optInt("ph", ph);
            ch3Linked = o.optInt("lk", ch3Linked ? 1 : 0) == 1;
            x2 = o.optInt("x2", x2 ? 1 : 0) == 1;
            zoom = o.optInt("zoom", zoom ? 1 : 0) == 1;
            timeScale = (float) o.optDouble("ts", timeScale);
            viewStart = (float) o.optDouble("vs", viewStart);
            if (timeScale < 0.01f) timeScale = 0.01f;
            postInvalidateOnAnimation();
        } catch (Exception ignored) {}
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int W = getWidth();
        int H = getHeight();
        if (W < 2 || H < 2) return;

        canvas.drawColor(BG);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(GRID);
        for (int i = 0; i <= 10; i++) {
            float x = i * W / 10f;
            canvas.drawLine(x, 0, x, H, paint);
        }
        for (int i = 0; i <= 6; i++) {
            float y = i * H / 6f;
            canvas.drawLine(0, y, W, y, paint);
        }

        float rowH = H / 3f;
        float pad = rowH * 0.1f;
        float hi = pad;
        float lo = rowH - pad;

        final int baseFreq = 20000;
        final float baseCycles = 5f;
        float windowTime = baseCycles / baseFreq / timeScale;
        float cycles = Math.max(0.5f, windowTime * freq);

        canvas.save();
        clip.set(0, 0, W, H);
        canvas.clipRect(clip);

        if (zoom) {
            drawZoomMode(canvas, W, H, baseFreq, baseCycles, hi, lo);
        } else {
            drawPwm(canvas, 0, duty, CH1, cycles, W, rowH, hi, lo, 0f);
            drawPwm(canvas, rowH, duty, CH2, cycles, W, rowH, hi, lo, 0.5f);

            Float burstSpacing = null;
            if (!ch3Linked) {
                float cw = W / cycles;
                burstSpacing = cw * (freq / Math.max(50f, fc));
            }
            drawBurst(canvas, rowH * 2, d3, CH3, n3, ph, cycles, W, rowH, hi, lo, burstSpacing);
            if (x2) {
                int ph2 = (ph + 500) % 1000;
                drawBurst(canvas, rowH * 2, d3, CH3X2, n3, ph2, cycles, W, rowH, hi, lo, burstSpacing);
            }
        }
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(8f * getResources().getDisplayMetrics().density);
        paint.setColor(DIM);
        float periodMs = 1000f / Math.max(1, freq);
        float windowMs = periodMs * cycles;
        String tbTxt = windowMs >= 1
            ? String.format("%.1fms/div", windowMs)
            : String.format("%.0fus/div", windowMs * 1000);
        canvas.drawText(tbTxt, W - paint.measureText(tbTxt) - 8, H - 6, paint);

        String ch3label = ch3Linked
            ? "CH3 " + fmtHz(f3) + "Hz ×" + n3 + " D:" + d3 + "% φ" + ph + "%"
            : "CH3⊥ " + fmtHz(f3) + "Hz ×" + n3 + " D:" + d3 + "% FC:" + fmtHz(fc) + "Hz";
        drawLabel(canvas, "CH1 " + fmtHz(freq) + "Hz D:" + duty + "%", CH1, 0, W, rowH);
        drawLabel(canvas, "CH2 " + fmtHz(freq) + "Hz D:" + duty + "%", CH2, rowH, W, rowH);
        drawLabel(canvas, ch3label, CH3, rowH * 2, W, rowH);
    }

    private void drawLabel(Canvas c, String txt, int col, float yTop, int W, float rowH) {
        paint.setColor(Color.argb(153, Color.red(col), Color.green(col), Color.blue(col)));
        c.drawRect(0, yTop, W, yTop + 1, paint);
        paint.setColor(col);
        paint.setTextSize(10f * getResources().getDisplayMetrics().density);
        c.drawText(txt, 8, yTop + 14, paint);
    }

    private void drawZoomMode(Canvas c, int W, int H, int baseFreq, float baseCycles,
                              float hi, float lo) {
        float pad2 = H * 0.15f;
        float hi2 = pad2;
        float lo2 = H - pad2;

        float windowTime3 = baseCycles / baseFreq / timeScale;
        float cycles3 = Math.max(0.5f, windowTime3 * f3);
        float cw3 = W / cycles3;
        float phOff3 = ph / 1000f * cw3;
        float hiW3 = cw3 * d3 / 100f;

        float ch1CycleW = W / Math.max(0.5f, windowTime3 * freq);
        float offsetPx = viewStart * W;

        paint.setStrokeWidth(2.5f);
        drawZoomBursts(c, W, hi2, lo2, ch1CycleW, phOff3, cw3, hiW3, offsetPx, cycles3, CH3, 0f);
        if (x2) {
            drawZoomBursts(c, W, hi2, lo2, ch1CycleW, phOff3, cw3, hiW3, offsetPx, cycles3, CH3X2, 0.5f);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CH3);
        paint.setTextSize(11f * getResources().getDisplayMetrics().density);
        c.drawText("CH3 | " + fmtHz(f3) + "Hz D:" + d3 + "% ×" + n3 + " φ" + ph + "%",
            8, 14, paint);
    }

    private void drawZoomBursts(Canvas c, int W, float hi2, float lo2,
                                float ch1CycleW, float phOff3, float cw3, float hiW3,
                                float offsetPx, float cycles3, int color, float phaseOffCh1) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(2.5f);
        int zMaxI = 200;
        float extraPhOff = phaseOffCh1 * ch1CycleW;
        for (int i = -3; i < zMaxI && i < cycles3 * (f3 / (float) Math.max(1, freq)) + 4; i++) {
            float burstStart = i * ch1CycleW + phOff3 + extraPhOff - offsetPx;
            for (int j = 0; j < n3; j++) {
                float ix = burstStart + j * cw3;
                if (ix > -cw3 && ix < W + cw3) {
                    c.drawLine(ix, lo2, ix, hi2, paint);
                    c.drawLine(ix, hi2, ix + hiW3, hi2, paint);
                    c.drawLine(ix + hiW3, hi2, ix + hiW3, lo2, paint);
                }
            }
        }
        c.drawLine(0, lo2, W, lo2, paint);
    }

    private void drawPwm(Canvas c, float yTop, int dutyPct, int color, float cycles,
                         int W, float rowH, float hi, float lo, float phaseShift) {
        float cw = W / cycles;
        float phOff = phaseShift * cw;
        float hiW = cw * dutyPct / 100f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(2f);
        for (int i = -1; i < cycles + 1; i++) {
            float x0 = i * cw + phOff;
            c.drawLine(x0, yTop + lo, x0, yTop + hi, paint);
            c.drawLine(x0, yTop + hi, x0 + hiW, yTop + hi, paint);
            c.drawLine(x0 + hiW, yTop + hi, x0 + hiW, yTop + lo, paint);
            c.drawLine(x0 + hiW, yTop + lo, x0 + cw, yTop + lo, paint);
        }
    }

    private void drawBurst(Canvas c, float yTop, int duty3, int color, int count, int phase,
                           float cycles, int W, float rowH, float hi, float lo,
                           Float spacingPx) {
        float cw = W / cycles;
        float spacing = spacingPx != null ? spacingPx : cw;
        float phOff = phase / 1000f * cw;

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(color);
        paint.setStrokeWidth(2f);

        float freqRatio = f3 / (float) Math.max(1, freq);
        float impW = Math.max(2f, cw / freqRatio);
        float hiW3 = impW * duty3 / 100f;
        float firstBurst = (phOff % spacing) - spacing;

        for (int i = 0; i < 200 && i * spacing + firstBurst < W + spacing * 2; i++) {
            float bStart = firstBurst + i * spacing;
            for (int j = 0; j < count; j++) {
                float ix = bStart + j * impW;
                if (ix + hiW3 > 0 && ix < W) {
                    float x0 = Math.max(0, ix);
                    float x1 = Math.min(W, ix + hiW3);
                    c.drawLine(x0, yTop + lo, x0, yTop + hi, paint);
                    c.drawLine(x0, yTop + hi, x1, yTop + hi, paint);
                    c.drawLine(x1, yTop + hi, x1, yTop + lo, paint);
                }
            }
        }
        c.drawLine(0, yTop + lo, W, yTop + lo, paint);
    }

    private static String fmtHz(int v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000.0);
        if (v >= 1000) return (v / 1000) + "k";
        return String.valueOf(v);
    }
}
