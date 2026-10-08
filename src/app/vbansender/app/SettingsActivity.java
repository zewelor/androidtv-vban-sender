package app.vbansender.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.vbansender.OutputConfig;

/** One-time receiver setup and a separate entry to the retained developer proof. */
public final class SettingsActivity extends Activity {
    private EditText receiver;
    private EditText port;
    private EditText stream;
    private EditText frames;
    private Button save;
    private TextView status;
    private LinearLayout layout;
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = AppConfig.prefs(this);
        ScrollView scroll = new ScrollView(this);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(80, 35, 80, 35);
        TextView title = new TextView(this);
        title.setText("VBAN — Settings");
        title.setTextSize(30);
        layout.addView(title);
        receiver = field("Receiver IPv4 address", prefs.getString("receiver", ""), false);
        port = field("UDP port", prefs.getString("port", "6980"), true);
        stream = field("Stream name", prefs.getString("stream", "vban-audio"), false);
        frames = field("Packet frames (128 or 256 recommended)", prefs.getString("frames", "256"), true);
        status = new TextView(this);
        status.setTextSize(19);
        layout.addView(status);
        save = new Button(this);
        save.setText("Save receiver");
        layout.addView(save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    if (!editable()) {
                        throw new IllegalStateException("Turn VBAN OFF and wait for Stop before editing");
                    }
                    String address = receiver.getText().toString().trim();
                    String udp = port.getText().toString().trim();
                    String name = stream.getText().toString().trim();
                    String packets = frames.getText().toString().trim();
                    new OutputConfig(new String[] {address, udp, name, packets});
                    AppConfig.save(prefs.edit().putString("receiver", address).putString("port", udp)
                            .putString("stream", name).putString("frames", packets));
                    status.setText("Receiver saved. Press Back to return to VBAN.");
                } catch (Exception error) {
                    status.setText("Error: " + error.getMessage());
                }
            }
        });
        Button developer = new Button(this);
        developer.setText("Local ADB / developer tools");
        developer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SettingsActivity.this, ProofActivity.class)
                        .putExtra("proof_seconds", 10));
            }
        });
        layout.addView(developer);
        TextView audio = new TextView(this);
        audio.setText("ON uses stereo PCM. OFF restores your standard audio setting.\n"
                + "Netflix uses standard output. Receiver discovery is not used.");
        audio.setTextSize(18);
        layout.addView(audio);
        scroll.addView(layout);
        setContentView(scroll);
        receiver.requestFocus();
    }

    private EditText field(String label, String value, boolean numeric) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(18);
        layout.addView(caption);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(20);
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        edit.setText(value);
        edit.setSelectAllOnFocus(true);
        layout.addView(edit);
        return edit;
    }

    private boolean editable() {
        return !prefs.getBoolean("enabled", false)
                && "OFF".equals(prefs.getString("controller_state", "OFF"))
                && prefs.getString("active_run", "").isEmpty()
                && !prefs.contains("original_surround");
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean editable = editable();
        receiver.setEnabled(editable);
        port.setEnabled(editable);
        stream.setEnabled(editable);
        frames.setEnabled(editable);
        save.setEnabled(editable);
        status.setText(!editable ? "Turn VBAN OFF and wait for Stop before editing."
                : getSharedPreferences("proof", MODE_PRIVATE).getBoolean("authorized", false)
                    ? "Local ADB ready. Save the receiver, then return to VBAN."
                    : "Save the receiver and use Local ADB / developer tools to authorize this app.");
    }
}
