package org.wwhdrecomp.wwhd;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONObject;

/** Progress and controls for a durable phone setup job. */
public class SetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button resume;
    private Button pause;
    private Button continueAnyway;
    private String continueAction;
    private Button play;
    private Button addIcon;
    private Button disc;
    private Button common;
    private Button restore;
    private final ArrayList<Button> pickers = new ArrayList<>();
    private boolean phoneSetup;
    private String actionMessage;
    private String pickerJob;
    private String shaderWarmJob;
    private static final int FOLDER = 10, ARCHIVE = 11, IMAGE = 12, DISC = 13, COMMON = 14, RESTORE = 15;
    static final String EXTRA_SHOW_SETUP = "org.wwhdrecomp.wwhd.SHOW_SETUP";

    /** A game is ready: an active phone build that needs no rebuild and no setup in progress, or the
     *  PC route's game folder. */
    private boolean readyToPlay() {
        try {
            if (!phoneSetup) {
                File external = getExternalFilesDir(null);
                return external != null && new File(external, "game/code/cking.rpx").isFile();
            }
            if (!new File(AndroidGame.storage(this), "active.json").isFile() || AndroidGame.needsRebuild(this))
                return false;
            File job = SetupStore.current(this);
            File host = job == null ? null : new File(job, "host.json");
            return host == null || !host.isFile() || SetupStore.read(host).optString("state").equals("complete");
        } catch (Exception unreadable) { return false; }
    }

    private boolean shortcutChecked;

    /** Once per completed build: the home-screen game icon with the game's own picture. Android
     *  refuses the request unless the screen is in front, so it waits for focus and counts as made
     *  only once accepted. */
    private void offerShortcut() {
        if (shortcutChecked || !hasWindowFocus()) return;
        shortcutChecked = true;
        if (!phoneSetup) {  // the PC route: its game folder, once
            File external = getExternalFilesDir(null);
            File game = external == null ? null : new File(external, "game");
            android.content.SharedPreferences prefs = getSharedPreferences("setup", MODE_PRIVATE);
            if (game == null || !new File(game, "meta/iconTex.tga").isFile() || prefs.getBoolean("pc_icon_offered", false)) return;
            if (GameShortcut.offer(this, game)) prefs.edit().putBoolean("pc_icon_offered", true).apply();
            return;
        }
        new Thread(() -> {
            AndroidGame.Selection selection = AndroidGame.selected(this);  // (hashes the build: off the UI thread)
            if (selection == null || selection.game == null) return;
            android.content.SharedPreferences prefs = getSharedPreferences("setup", MODE_PRIVATE);
            String generation = selection.library.getName();
            if (generation.equals(prefs.getString("shortcut_offered", ""))) return;
            runOnUiThread(() -> {
                if (GameShortcut.offer(this, selection.game)) prefs.edit().putString("shortcut_offered", generation).apply();
            });
        }, "wwhd-game-shortcut").start();
    }

    /** The setup screen's button: the game icon again (refused or removed), or where it is. */
    private void addIcon() {
        new Thread(() -> {
            File game = GameWidget.activeGame(this);
            boolean placed = android.appwidget.AppWidgetManager.getInstance(this)
                .getAppWidgetIds(new android.content.ComponentName(this, GameWidget.class)).length > 0;
            runOnUiThread(() -> {
                if (game == null) android.widget.Toast.makeText(this, R.string.game_icon_none, android.widget.Toast.LENGTH_SHORT).show();
                else if (placed) android.widget.Toast.makeText(this, R.string.game_icon_present, android.widget.Toast.LENGTH_LONG).show();
                else GameShortcut.offer(this, game);
            });
        }, "wwhd-game-icon").start();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pickerJob = state.getString("picker_job");
        try (java.io.InputStream python = getAssets().open("python-build.json");
             java.io.InputStream compiler = getAssets().open("toolchain-apk.json");
             java.io.InputStream sdk = getAssets().open("runtime-sdk.json")) { phoneSetup = true; }
        catch (Exception unavailable) { phoneSetup = false; }
        // the app icon starts the game once there is one to play; the launcher's "Game setup"
        // shortcut (and an update that needs a rebuild, or a setup still running) opens this screen
        if (state == null && Intent.ACTION_MAIN.equals(getIntent().getAction()) &&
                !getIntent().getBooleanExtra(EXTRA_SHOW_SETUP, false) && readyToPlay()) {
            startActivity(new Intent(this, WwhdActivity.class));
            finish();
            return;
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        status = new TextView(this);
        status.setTextSize(18);
        layout.addView(status);
        pickers.add(button(layout, "Choose extracted game folder", () -> pick(FOLDER)));
        pickers.add(button(layout, "Choose WUA archive", () -> pick(ARCHIVE)));
        pickers.add(button(layout, "Choose WUD / WUX disc image", () -> pick(IMAGE)));
        restore = button(layout, "Restore access to selected dump", () -> pick(RESTORE));
        disc = button(layout, "Choose disc key file", () -> pick(DISC));
        common = button(layout, "Choose common key file", () -> pick(COMMON));
        resume = button(layout, "Start / resume / retry setup", () -> control(SetupService.RESUME));
        pause = button(layout, "Pause setup", () -> control(SetupService.PAUSE));
        continueAnyway = button(layout, "Continue anyway", () -> { if (continueAction != null) control(continueAction); });
        continueAnyway.setVisibility(android.view.View.GONE);
        play = button(layout, "Play current build (phone or PC)", () -> startActivity(new Intent(this, WwhdActivity.class)));
        addIcon = button(layout, getString(R.string.game_icon_add), this::addIcon);
        TextView checkpoints = new TextView(this);
        checkpoints.setText("Setup keeps a private copy of your input. Leave room for that copy, extracted files, compiled output and at least 1 GiB working reserve. Full-game space and time still need phone measurements. Keys are selected as files and kept private.\n\nYou can close this screen while setup runs. Import resumes from verified complete files; an interrupted file copy or extraction restarts. Python and compiler resource preparation pause during unpacking and restart the incomplete stage. Checksum validation and translation respond to pause requests; an interrupted translation restarts. Compilation and linking also respond to pause requests. An interrupted command restarts; verified objects are kept. Saves stay in their current folder.\n\nThe optional PC route still uses the game folder copied over USB.");
        layout.addView(checkpoints);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private boolean replaceable(File job) throws Exception {
        if (job == null) return true;
        File host = new File(job, "host.json");
        if (!host.isFile()) return true;
        String state = SetupStore.read(host).optString("state");
        return state.equals("selected") || state.equals("failed") || state.equals("complete") ||
            (state.equals("paused") && new File(job, "manual-pause.json").isFile());
    }

    private void pick(int request) {
        try {
            actionMessage = null;
            File job = SetupStore.current(this);
            if (!replaceable(job)) throw new Exception("Pause setup and wait for its checkpoint before changing input");
            pickerJob = job == null ? null : job.getName();
            boolean folder = request == FOLDER;
            if (request == RESTORE) {
                if (job == null || new File(job, "job.json").exists()) throw new Exception("This job already uses a private input copy");
                folder = SetupStore.read(new File(job, "source.json")).getString("kind").equals("folder");
            }
            Intent intent = new Intent(folder ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
            if (!folder) intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, request);
        } catch (Exception failure) { actionMessage = "Cannot choose input: " + failure.getMessage(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        try {
            actionMessage = null;
            File current = SetupStore.current(this);
            if (!replaceable(current)) throw new Exception("Pause setup before changing input");
            if ((request == RESTORE || request == DISC || request == COMMON) &&
                (current == null || !current.getName().equals(pickerJob)))
                throw new Exception("The selected job changed. Open its picker again.");
            Uri uri = data.getData();
            if (request == RESTORE) {
                if (new File(current, "job.json").exists()) throw new Exception("The input has already been copied privately");
                JSONObject source = SetupStore.read(new File(current, "source.json"));
                if (!uri.toString().equals(source.getString("uri")))
                    throw new Exception("Choose the same previously selected file or folder to restore access. Use the dump-selection buttons for a different input.");
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                actionMessage = "Access restored. Tap Start / resume to retry using completed private copies.";
            } else if (request == DISC || request == COMMON) {
                if (current == null) throw new Exception("Choose a disc image first");
                JSONObject source = SetupStore.read(new File(current, "source.json"));
                if (!source.getString("kind").equals("image")) throw new Exception("Keys are only needed for disc images");
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                SetupImport.selectKey(current, request == DISC ? "disc" : "common", uri);
                if (new File(current, "job.json").isFile())
                    actionMessage = "Replacement key selected. Tap Start / resume to copy it privately and retry. Your dump copy is retained.";
            } else if (request == FOLDER || request == ARCHIVE || request == IMAGE) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                String id = UUID.randomUUID().toString().replace("-", "");
                File job = SetupStore.job(this, id);
                SetupStore.write(new File(job, "source.json"), new JSONObject().put("schema", 1)
                    .put("kind", request == FOLDER ? "folder" : request == ARCHIVE ? "archive" : "image").put("uri", uri.toString()));
                SetupStore.write(new File(job, "manual-pause.json"), new JSONObject().put("schema", 1));
                SetupStore.write(new File(job, "host.json"), new JSONObject().put("schema", 1).put("state", "selected"));
                SetupStore.select(this, id);
            }
        } catch (Exception failure) { actionMessage = "Cannot keep selected input: " + failure.getMessage(); }
        finally { pickerJob = null; }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("picker_job", pickerJob);
        super.onSaveInstanceState(state);
    }

    private Button button(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
        return button;
    }

    private void control(String action) {
        try {
            actionMessage = null;
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
            SetupService.start(this, action);
        } catch (Exception failure) { actionMessage = "Cannot start setup: " + failure.getMessage(); }
    }

    private static void setTextIfChanged(TextView view, String text) {
        // Replacing status then appending feedback on every poll continually
        // emits accessibility changes, even when nothing has changed.
        if (!text.contentEquals(view.getText())) view.setText(text);
    }

    private boolean keyReady(File job, JSONObject source, String key, boolean correcting, java.util.Set<String> grants) throws Exception {
        if (source == null) return false;
        if (correcting) {
            File pending = SetupImport.keyReplacement(job, key);
            if (pending.isFile()) return grants.contains(SetupStore.read(pending).getString("uri"));
            return new File(job, "input/" + key + ".key").isFile();
        }
        return grants.contains(source.optString(key + "_uri"));
    }

    /** Current {thermal status, battery temperature in tenths of °C}; -1 where unavailable. */
    private int[] sensorReadings() {
        int thermal = -1, temperature = -1;
        try {
            android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
            if (power != null) thermal = power.getCurrentThermalStatus();
        } catch (RuntimeException ignored) { /* shown as n/a */ }
        try {
            Intent battery = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) temperature = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
        } catch (RuntimeException ignored) { /* shown as n/a */ }
        return new int[] {thermal, temperature};
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            try {
                File job = SetupStore.current(SetupActivity.this);
                JSONObject source = job != null && new File(job, "source.json").isFile() ? SetupStore.read(new File(job, "source.json")) : null;
                boolean importing = phoneSetup && source != null && !new File(job, "job.json").isFile();
                JSONObject hostState = job != null && new File(job, "host.json").isFile() ? SetupStore.read(new File(job, "host.json")) : null;
                boolean correctingKeys = !importing && phoneSetup && source != null && source.optString("kind").equals("image") &&
                    hostState != null && (hostState.optString("state").equals("failed") ||
                    (hostState.optString("state").equals("paused") && new File(job, "manual-pause.json").isFile()));
                boolean needsKeys = (importing && source.optString("kind").equals("image")) || correctingKeys;
                java.util.HashSet<String> grants = new java.util.HashSet<>();
                if (importing || correctingKeys) for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions())
                    if (permission.isReadPermission()) grants.add(permission.getUri().toString());
                boolean discReady = keyReady(job, source, "disc", correctingKeys, grants);
                boolean commonReady = keyReady(job, source, "common", correctingKeys, grants);
                restore.setVisibility(importing ? android.view.View.VISIBLE : android.view.View.GONE);
                restore.setEnabled(importing && replaceable(job));
                disc.setVisibility(needsKeys ? android.view.View.VISIBLE : android.view.View.GONE);
                common.setVisibility(needsKeys ? android.view.View.VISIBLE : android.view.View.GONE);
                setTextIfChanged(disc, discReady ? "Disc key selected — change" : source != null && source.has("disc_uri") ? "Disc key needs access — choose again" : "Choose disc key file");
                setTextIfChanged(common, commonReady ? "Common key selected — change" : source != null && source.has("common_uri") ? "Common key needs access — choose again" : "Choose common key file");
                disc.setEnabled(needsKeys && replaceable(job));
                common.setEnabled(needsKeys && replaceable(job));
                resume.setEnabled(phoneSetup && job != null && (!needsKeys || (discReady && commonReady)));
                pause.setEnabled(phoneSetup && job != null);
                for (Button picker : pickers) picker.setEnabled(phoneSetup && replaceable(job));
                File external = getExternalFilesDir(null);
                play.setEnabled(new File(AndroidGame.storage(SetupActivity.this), "active.json").isFile() ||
                    (external != null && new File(external, "game/code/cking.rpx").isFile()));
                addIcon.setEnabled(play.isEnabled());
                String text;
                if (!phoneSetup) {
                    text = "This APK supports the PC build route. Phone setup needs an APK containing Python and the compiler.";
                    if (play.isEnabled()) offerShortcut();
                }
                else if (job == null) text = "Choose your game dump to set up on this phone.";
                else {
                    File host = new File(job, "host.json");
                    JSONObject value = host.isFile() ? SetupStore.read(host) : new JSONObject().put("state", "ready to start");
                    text = "Setup: " + value.getString("state");
                    if (value.optString("state").equals("complete")) offerShortcut();
                    if (value.optString("state").equals("warming shaders")) { play.setEnabled(false); addIcon.setEnabled(false); }
                    if (value.optString("state").equals("warming shaders") &&
                        !job.getAbsolutePath().equals(shaderWarmJob)) {
                        shaderWarmJob = job.getAbsolutePath();
                        startActivity(new Intent(SetupActivity.this, ShaderPrepareActivity.class)
                            .putExtra("result", new File(job, "shader-warm-result.json").getAbsolutePath()));
                    }
                    String reason = value.optString("state").equals("paused") ? value.optString("reason", null) : null;
                    if (value.has("reason")) {
                        int[] sensors = sensorReadings();
                        text += "\n" + SetupPolicy.description(value.getString("reason"), sensors[0], sensors[1]);
                    }
                    if ("heat".equals(reason))
                        text += "\nIf the phone does not feel hot, tap Continue anyway. Setup then ignores heat until this run ends; Android's critical heat level still pauses it.";
                    else if (SetupPolicy.overridable(reason))
                        text += "\nTap Continue anyway to run on battery until this run ends.";
                    continueAction = !SetupPolicy.overridable(reason) ? null :
                        reason.equals("heat") ? SetupService.CONTINUE_HEAT : SetupService.CONTINUE_BATTERY;
                    if (value.has("error")) text += "\n" + value.getString("error");
                    if (importing && !grants.contains(source.optString("uri")))
                        text += "\nDump access is missing. Restore access to the same selected dump if another file copy is needed. Completed private copies are retained.";
                    if (needsKeys) text += "\n" + (resume.isEnabled() ? correctingKeys ?
                        "Private dump retained. Change either key if needed, then tap Start / resume to retry." :
                        "Both key files selected. Tap Start." : "Select both key files before starting.");
                    if (value.optString("state").equals("importing")) {
                        File imported = new File(job, "import-progress.json");
                        if (imported.isFile()) {
                            JSONObject progress = SetupStore.read(imported);
                            text += "\nCopied " + progress.getLong("copied") / (1024 * 1024) + " MiB; " + progress.getInt("complete") + "/" + progress.getInt("files") + " files";
                        }
                    }
                    File progress = new File(job, "state.json");
                    if (value.optString("state").equals("running") && progress.isFile()) {
                        JSONObject event = SetupStore.read(progress).optJSONObject("last_event");
                        String compiling = event == null || !event.optString("stage").equals("compile") ? null :
                            SetupPolicy.compileProgress(event.optString("state"), event.optInt("compiled"), event.optInt("reused"),
                                event.optInt("total"), event.optLong("eta_seconds", -1));
                        if (compiling != null) text += "\n" + compiling;
                        else if (event != null && event.optString("stage").equals("shaders") && event.has("fraction"))
                            text += "\nPreparing shaders: " + Math.round(100 * event.optDouble("fraction")) + "%\n" + event.optString("detail");
                        else if (event != null) text += "\n" + event.optString("stage", "setup") + ": " + event.optString("state");
                    }
                }
                if (job == null || !phoneSetup) continueAction = null;
                continueAnyway.setVisibility(continueAction != null ? android.view.View.VISIBLE : android.view.View.GONE);
                if (actionMessage != null) text += "\n" + actionMessage;
                setTextIfChanged(status, text);
            } catch (Exception failure) { setTextIfChanged(status, "Cannot read setup progress: " + failure.getMessage()); }
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        refresh.run();
        new Thread(() -> {
            try {
                File job = SetupStore.current(this);
                if (job == null || new File(job, "manual-pause.json").exists()) return;
                File host = new File(job, "host.json");
                if (!host.isFile()) return;
                String state = SetupStore.read(host).optString("state");
                boolean changed = state.equals("complete") && AndroidGame.needsRebuild(this);
                if (SetupRecoveryPolicy.shouldStart(state, false, true, changed))
                    runOnUiThread(() -> {
                        try { SetupService.start(this, null); }
                        catch (Exception failure) { status.setText("Tap Resume to continue setup: " + failure.getMessage()); }
                    });
            } catch (Exception ignored) { /* refresh shows a readable record error */ }
        }, "wwhd-setup-check").start();
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
