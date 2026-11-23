package com.example.btdialer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ניסוי: חיוג דרך בלוטות' בפרוטוקול HFP.
 * האפליקציה מתחברת לערוץ ה"שער" (Audio Gateway) של הטלפון ושולחת פקודות AT.
 * השיחה עצמה (הקול) לא עוברת דרך המכשיר הזה.
 */
public class MainActivity extends Activity {
    private static final UUID HFP_AG = UUID.fromString("0000111F-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter adapter;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private Spinner spinner;
    private EditText number;
    private TextView logView;
    private ScrollView scroll;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean connected = false;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("חייגן בלוטות' (ניסוי)");
        title.setTextSize(22);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("1. זווג את המכשיר עם הפלאפון בהגדרות הבלוטות'.\n2. בחר אותו כאן ולחץ התחבר.\n3. הקלד מספר ולחץ חייג.");
        root.addView(hint);

        spinner = new Spinner(this);
        root.addView(spinner);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(btn("רענן רשימה", v -> loadDevices()));
        row1.addView(btn("התחבר", v -> connect()));
        row1.addView(btn("נתק", v -> disconnect()));
        root.addView(row1);

        number = new EditText(this);
        number.setHint("מספר טלפון");
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        number.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        number.setTextSize(24);
        root.addView(number);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(btn("חייג", v -> dial()));
        row2.addView(btn("סיים שיחה", v -> hangup()));
        root.addView(row2);

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            log("אין בלוטות' במכשיר הזה.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 1);
        } else {
            loadDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            loadDevices();
        } else {
            log("חסרה הרשאת בלוטות'. אשר אותה בהגדרות האפליקציה.");
        }
    }

    @SuppressLint("MissingPermission")
    private void loadDevices() {
        if (adapter == null) return;
        if (!adapter.isEnabled()) {
            log("הבלוטות' כבוי. הדלק אותו ולחץ רענן.");
            return;
        }
        devices.clear();
        List<String> names = new ArrayList<>();
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                devices.add(d);
                names.add(d.getName() + "  (" + d.getAddress() + ")");
            }
        }
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        log("נמצאו " + devices.size() + " מכשירים מזווגים.");
    }

    @SuppressLint("MissingPermission")
    private void connect() {
        final int idx = spinner.getSelectedItemPosition();
        if (idx < 0 || idx >= devices.size()) {
            log("בחר מכשיר מהרשימה (קודם צריך לזווג).");
            return;
        }
        final BluetoothDevice d = devices.get(idx);
        new Thread(() -> {
            try {
                disconnectQuiet();
                adapter.cancelDiscovery();
                log("מתחבר אל " + d.getName() + " ...");
                socket = d.createRfcommSocketToServiceRecord(HFP_AG);
                socket.connect();
                in = socket.getInputStream();
                out = socket.getOutputStream();
                log("הערוץ נפתח. מבצע לחיצת יד HFP...");
                cmd("AT+BRSF=0");
                cmd("AT+CIND=?");
                cmd("AT+CIND?");
                String r = cmd("AT+CMER=3,0,0,1");
                connected = r.contains("OK");
                log(connected ? "מחובר. אפשר לחייג." : "לחיצת היד לא הושלמה. ייתכן שהפלאפון לא תומך.");
            } catch (Exception e) {
                connected = false;
                log("החיבור נכשל: " + e.getMessage()
                        + "\nייתכן שהפלאפון לא מציע שירות כזה, או שהמכשיר כבר מחובר אליו כאוזניה. נסה לנתק אותו קודם בהגדרות הבלוטות'.");
            }
        }).start();
    }

    private void dial() {
        final String n = number.getText().toString().trim();
        if (!n.matches("[0-9+*#]{3,20}")) {
            log("מספר לא תקין.");
            return;
        }
        if (!connected) {
            log("קודם התחבר.");
            return;
        }
        new Thread(() -> {
            try {
                String r = cmd("ATD" + n + ";");
                log(r.contains("OK") ? "פקודת החיוג התקבלה בפלאפון." : "הפלאפון סירב לפקודת החיוג.");
            } catch (IOException e) {
                connected = false;
                log("שגיאה: " + e.getMessage());
            }
        }).start();
    }

    private void hangup() {
        if (!connected) {
            log("אין חיבור.");
            return;
        }
        new Thread(() -> {
            try {
                cmd("AT+CHUP");
            } catch (IOException e) {
                connected = false;
                log("שגיאה: " + e.getMessage());
            }
        }).start();
    }

    private void disconnect() {
        disconnectQuiet();
        log("נותק.");
    }

    private void disconnectQuiet() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    private synchronized String cmd(String c) throws IOException {
        log("> " + c);
        out.write((c + "\r").getBytes("US-ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + 5000;
        byte[] buf = new byte[256];
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int n = in.read(buf);
                if (n < 0) break;
                sb.append(new String(buf, 0, n, "US-ASCII"));
                String s = sb.toString();
                if (s.contains("OK") || s.contains("ERROR")) break;
            } else {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        String resp = sb.toString().replace("\r", " ").replace("\n", " ").trim();
        log("< " + (resp.isEmpty() ? "(אין תגובה)" : resp));
        return sb.toString();
    }

    private void log(final String m) {
        runOnUiThread(() -> {
            logView.append(m + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnectQuiet();
    }
}
