package ru.slista.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «С листа» для Android: игра (assets/index.html) в WebView плюс мост MIDI.
 * WebView не поддерживает Web MIDI, поэтому ноты с клавиатуры принимает системный
 * android.media.midi, а в страницу они попадают через window.__amidi (см. midibridge.js).
 */
public class MainActivity extends Activity {
    private WebView web;
    private volatile boolean destroyed = false;
    private MidiManager midi;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, Opened> opened = new HashMap<>();

    private static class Opened {
        final String name;
        final MidiDevice device;
        final List<MidiOutputPort> ports = new ArrayList<>();
        Opened(String name, MidiDevice device) { this.name = name; this.device = device; }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        hideBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidMidi");

        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            midi = (MidiManager) getSystemService(Context.MIDI_SERVICE);
        }
        if (midi != null) {
            for (MidiDeviceInfo info : midi.getDevices()) open(info);
            midi.registerDeviceCallback(new MidiManager.DeviceCallback() {
                @Override public void onDeviceAdded(MidiDeviceInfo info) { open(info); }
                @Override public void onDeviceRemoved(MidiDeviceInfo info) { close(info.getId()); }
            }, ui);
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    /** Строка для JS: список подключённых входов. */
    private class Bridge {
        @JavascriptInterface
        public String devices() {
            StringBuilder b = new StringBuilder("[");
            synchronized (opened) {
                boolean first = true;
                for (Map.Entry<Integer, Opened> e : opened.entrySet()) {
                    if (e.getValue().ports.isEmpty()) continue;
                    if (!first) b.append(',');
                    first = false;
                    b.append("{\"id\":\"").append(e.getKey()).append("\",\"name\":\"")
                     .append(e.getValue().name.replace("\\", "").replace("\"", "'")).append("\"}");
                }
            }
            return b.append(']').toString();
        }
    }

    private void open(final MidiDeviceInfo info) {
        if (info.getOutputPortCount() == 0) return; // у устройства нет выхода — нот от него не будет
        final int id = info.getId();
        synchronized (opened) { if (opened.containsKey(id)) return; }
        midi.openDevice(info, device -> {
            if (device == null) return;
            String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
            if (name == null) name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_PRODUCT);
            if (name == null) name = "MIDI";
            Opened o = new Opened(name, device);
            for (int p = 0; p < info.getOutputPortCount(); p++) {
                MidiOutputPort port = device.openOutputPort(p);
                if (port == null) continue;
                port.connect(new Parser(id));
                o.ports.add(port);
            }
            synchronized (opened) { opened.put(id, o); }
            notifyChanged();
        }, ui);
    }

    private void close(int id) {
        Opened o;
        synchronized (opened) { o = opened.remove(id); }
        if (o == null) return;
        for (MidiOutputPort p : o.ports) { try { p.close(); } catch (Exception ignored) { } }
        try { o.device.close(); } catch (Exception ignored) { }
        notifyChanged();
    }

    private void notifyChanged() {
        ui.post(() -> { if (!destroyed) web.evaluateJavascript("window.__amidi&&window.__amidi.changed()", null); });
    }

    /** Разбирает поток байтов MIDI (с running status) и пересылает нажатия и отпускания нот. */
    private class Parser extends MidiReceiver {
        private final int id;
        private int status = 0, d1 = -1;
        Parser(int id) { this.id = id; }

        @Override
        public void onSend(byte[] data, int offset, int count, long timestamp) {
            final StringBuilder js = new StringBuilder();
            for (int i = offset; i < offset + count; i++) {
                int b = data[i] & 0xFF;
                if (b >= 0xF8) continue;                    // системные real-time (clock и т. п.)
                if (b >= 0x80) { status = b < 0xF0 ? b : 0; d1 = -1; continue; }
                int cmd = status & 0xF0;
                if (cmd != 0x80 && cmd != 0x90) continue;  // нужны только ноты
                if (d1 < 0) { d1 = b; continue; }
                js.append("window.__amidi&&window.__amidi.msg('").append(id).append("',")
                  .append(status).append(',').append(d1).append(',').append(b).append(");");
                d1 = -1;
            }
            if (js.length() > 0) ui.post(() -> { if (!destroyed) web.evaluateJavascript(js.toString(), null); });
        }
    }

    private void hideBars() {
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    /** Кнопка «Назад» работает как Esc в игре (пауза / шаг назад по меню). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            web.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{code:'Escape',key:'Escape'}))", null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause() { super.onPause(); web.onPause(); }
    @Override protected void onResume() { super.onResume(); web.onResume(); hideBars(); }

    @Override
    protected void onDestroy() {
        destroyed = true;
        synchronized (opened) {
            for (Integer id : new ArrayList<>(opened.keySet())) close(id);
        }
        web.destroy();
        super.onDestroy();
    }
}
